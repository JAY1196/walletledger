
package com.walletledger;

import com.walletledger.entity.*;
import com.walletledger.reconciliation.ReconciliationRunner;
import com.walletledger.repository.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ReconciliationRunnerIntegrationTest {

    private static final UUID SYSTEM_WALLET_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Autowired
    private ReconciliationRunner reconciliationRunner;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    // ---------------------------------------------------------
    // TEST 1: Clean database
    // ---------------------------------------------------------

    @Test
    void cleanDatabaseReconciliationRecordsSuccess() {

        boolean executed = reconciliationRunner.runOnce();

        assertTrue(executed,
                "Reconciliation should acquire the lock and execute");

        assertLatestRunCounts(
                "SUCCESS",
                0,
                0,
                0,
                0,
                0,
                0
        );
    }

    // ---------------------------------------------------------
    // TEST 2: Cached wallet balance corruption
    // ---------------------------------------------------------

    @Test
    void corruptedWalletBalancesAreDetectedAndRecorded() {

        UUID firstUserId = UUID.randomUUID();
        UUID secondUserId = UUID.randomUUID();

        UUID firstWalletId = UUID.randomUUID();
        UUID secondWalletId = UUID.randomUUID();

        UUID firstTransactionId = UUID.randomUUID();
        UUID secondTransactionId = UUID.randomUUID();

        User firstUser = new User(
                firstUserId,
                "Reconciliation Test User 1",
                "recon-test-" + firstUserId + "@example.com"
        );

        User secondUser = new User(
                secondUserId,
                "Reconciliation Test User 2",
                "recon-test-" + secondUserId + "@example.com"
        );

        userRepository.saveAllAndFlush(List.of(firstUser, secondUser));

        Wallet firstWallet = new Wallet(
                firstWalletId, firstUserId, WalletType.USER);

        Wallet secondWallet = new Wallet(
                secondWalletId, secondUserId, WalletType.USER);

        walletRepository.saveAllAndFlush(
                List.of(firstWallet, secondWallet));

        Transaction firstTransaction = new Transaction(
                firstTransactionId,
                TransactionType.TOPUP,
                "recon-test-" + firstTransactionId,
                new byte[32],
                100,
                firstUserId,
                SYSTEM_WALLET_ID,
                firstWalletId,
                null
        );

        Transaction secondTransaction = new Transaction(
                secondTransactionId,
                TransactionType.TOPUP,
                "recon-test-" + secondTransactionId,
                new byte[32],
                100,
                secondUserId,
                SYSTEM_WALLET_ID,
                secondWalletId,
                null
        );

        firstTransaction.complete((short) 200, "{}");
        secondTransaction.complete((short) 200, "{}");

        transactionRepository.saveAllAndFlush(
                List.of(firstTransaction, secondTransaction));

        ledgerEntryRepository.saveAllAndFlush(List.of(
                new LedgerEntry(
                        firstTransactionId,
                        SYSTEM_WALLET_ID,
                        LedgerEntryDirection.DEBIT,
                        100
                ),
                new LedgerEntry(
                        firstTransactionId,
                        firstWalletId,
                        LedgerEntryDirection.CREDIT,
                        100
                ),
                new LedgerEntry(
                        secondTransactionId,
                        SYSTEM_WALLET_ID,
                        LedgerEntryDirection.DEBIT,
                        100
                ),
                new LedgerEntry(
                        secondTransactionId,
                        secondWalletId,
                        LedgerEntryDirection.CREDIT,
                        100
                )
        ));

        // Deliberately corrupt cached balances.
        // Expected ledger balances: 100 and 100.
        // Corrupted cached balances: 101 and 99.
        jdbcTemplate.update(
                "UPDATE wallets SET balance = ? WHERE id = UUID_TO_BIN(?)",
                -200L,
                SYSTEM_WALLET_ID.toString()
        );

        jdbcTemplate.update(
                "UPDATE wallets SET balance = ? WHERE id = UUID_TO_BIN(?)",
                101L,
                firstWalletId.toString()
        );

        jdbcTemplate.update(
                "UPDATE wallets SET balance = ? WHERE id = UUID_TO_BIN(?)",
                99L,
                secondWalletId.toString()
        );

        assertTrue(
                reconciliationRunner.runOnce(),
                "Reconciliation should execute on the corrupted data"
        );

        assertLatestRunCounts(
                "VIOLATIONS_FOUND",
                0, // transaction-ledger
                0, // reversal
                2, // wallet balance
                0, // negative USER wallet
                0, // global balance
                2  // total
        );
    }

    // ---------------------------------------------------------
    // TEST 3: Check 1 - Completed transaction without ledger
    // ---------------------------------------------------------

    @Test
    void completedTransactionWithoutLedgerEntriesIsDetected() {

        UUID userId = UUID.randomUUID();
        UUID walletId = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();

        userRepository.saveAndFlush(new User(
                userId,
                "Missing Ledger Test",
                "missing-ledger-" + userId + "@example.com"
        ));

        walletRepository.saveAndFlush(
                new Wallet(walletId, userId, WalletType.USER)
        );

        Transaction transaction = new Transaction(
                transactionId,
                TransactionType.TOPUP,
                "missing-ledger-" + transactionId,
                new byte[32],
                100,
                userId,
                SYSTEM_WALLET_ID,
                walletId,
                null
        );

        transaction.complete((short) 200, "{}");
        transactionRepository.saveAndFlush(transaction);

        // Deliberately do not create ledger entries.
        assertTrue(reconciliationRunner.runOnce());

        assertLatestRunCounts(
                "VIOLATIONS_FOUND",
                1, // transaction-ledger
                0, // reversal
                0, // wallet balance
                0, // negative USER wallet
                0, // global balance
                1  // total
        );
    }

    // ---------------------------------------------------------
    // TEST 4: Check 1 - Unbalanced ledger entries
    // ---------------------------------------------------------

    @Test
    void unbalancedTransactionLedgerEntriesAreDetected() {

        UUID userId = UUID.randomUUID();
        UUID walletId = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();

        userRepository.saveAndFlush(new User(
                userId,
                "Unbalanced Ledger Test",
                "unbalanced-ledger-" + userId + "@example.com"
        ));

        walletRepository.saveAndFlush(
                new Wallet(walletId, userId, WalletType.USER)
        );

        Transaction transaction = new Transaction(
                transactionId,
                TransactionType.TOPUP,
                "unbalanced-ledger-" + transactionId,
                new byte[32],
                100,
                userId,
                SYSTEM_WALLET_ID,
                walletId,
                null
        );

        transaction.complete((short) 200, "{}");
        transactionRepository.saveAndFlush(transaction);

        ledgerEntryRepository.saveAllAndFlush(List.of(
                new LedgerEntry(
                        transactionId,
                        SYSTEM_WALLET_ID,
                        LedgerEntryDirection.DEBIT,
                        100
                ),
                new LedgerEntry(
                        transactionId,
                        walletId,
                        LedgerEntryDirection.CREDIT,
                        90
                )
        ));

        // Align cached balances with the corrupted ledger.
        // This isolates the global imbalance rather than causing
        // separate wallet-balance mismatches.
        setWalletBalance(SYSTEM_WALLET_ID, -100);
        setWalletBalance(walletId, 90);

        assertTrue(reconciliationRunner.runOnce());

        assertLatestRunCounts(
                "VIOLATIONS_FOUND",
                1, // transaction-ledger
                0, // reversal
                0, // wallet balance
                0, // negative USER wallet
                1, // global balance: -100 + 90 = -10
                2  // total
        );
    }

    // ---------------------------------------------------------
    // TEST 5: Check 5 - Global wallet balance is not zero
    // ---------------------------------------------------------

    @Test
    void nonZeroGlobalWalletBalanceIsDetected() {

        // SYSTEM wallets are allowed to have negative balances.
        // Deliberately corrupt the system wallet cached balance.
        setWalletBalance(SYSTEM_WALLET_ID, -1);

        assertTrue(reconciliationRunner.runOnce());

        assertLatestRunCounts(
                "VIOLATIONS_FOUND",
                0, // transaction-ledger
                0, // reversal
                1, // wallet balance
                0, // negative USER wallet
                1, // global balance
                2  // total
        );
    }

    // ---------------------------------------------------------
    // TEST 6: MySQL named-lock protection
    // ---------------------------------------------------------

    @Test
    void overlappingReconciliationRunsAreBlockedByMysqlLock()
            throws Exception {

        String lockName = "walletledger:scheduled-reconciliation";

        try (Connection connection =
                     jdbcTemplate.getDataSource().getConnection()) {

            try (PreparedStatement statement =
                         connection.prepareStatement("SELECT GET_LOCK(?, 0)")) {

                statement.setString(1, lockName);

                try (ResultSet resultSet = statement.executeQuery()) {
                    assertTrue(resultSet.next());
                    assertEquals(1, resultSet.getInt(1));
                }
            }

            boolean secondRunExecuted = reconciliationRunner.runOnce();

            assertFalse(secondRunExecuted,
                    "Second reconciliation run must be blocked");

            try (PreparedStatement statement =
                         connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {

                statement.setString(1, lockName);

                try (ResultSet resultSet = statement.executeQuery()) {
                    assertTrue(resultSet.next());
                    assertEquals(1, resultSet.getInt(1));
                }
            }

            assertTrue(reconciliationRunner.runOnce(),
                    "Reconciliation should execute after lock release");
        }
    }

    // ---------------------------------------------------------
    // TEST 7: Reversed original without a completed reversal
    // ---------------------------------------------------------

    @Test
    void reversedOriginalWithoutCompletedReversalIsDetected() {

        UUID userId = UUID.randomUUID();
        UUID walletId = UUID.randomUUID();
        UUID originalId = UUID.randomUUID();

        userRepository.saveAndFlush(new User(
                userId,
                "Missing Reversal Test",
                "missing-reversal-" + userId + "@example.com"
        ));

        walletRepository.saveAndFlush(
                new Wallet(walletId, userId, WalletType.USER)
        );

        Transaction original = new Transaction(
                originalId,
                TransactionType.TOPUP,
                "missing-reversal-" + originalId,
                new byte[32],
                100,
                userId,
                SYSTEM_WALLET_ID,
                walletId,
                null
        );

        original.complete((short) 200, "{}");
        original.markReversed();
        transactionRepository.saveAndFlush(original);

        // Original top-up ledger remains valid and balanced.
        ledgerEntryRepository.saveAllAndFlush(List.of(
                new LedgerEntry(
                        originalId,
                        SYSTEM_WALLET_ID,
                        LedgerEntryDirection.DEBIT,
                        100
                ),
                new LedgerEntry(
                        originalId,
                        walletId,
                        LedgerEntryDirection.CREDIT,
                        100
                )
        ));

        setWalletBalance(SYSTEM_WALLET_ID, -100);
        setWalletBalance(walletId, 100);

        // No completed reversal transaction is created.
        assertTrue(reconciliationRunner.runOnce());

        assertLatestRunCounts(
                "VIOLATIONS_FOUND",
                0, // transaction-ledger
                1, // reversal
                0, // wallet balance
                0, // negative USER wallet
                0, // global balance
                1  // total
        );
    }

    // ---------------------------------------------------------
    // TEST 8: Completed reversal with original still COMPLETED
    // ---------------------------------------------------------

    @Test
    void completedReversalWithCompletedOriginalIsDetected() {

        UUID userId = UUID.randomUUID();
        UUID walletId = UUID.randomUUID();
        UUID originalId = UUID.randomUUID();
        UUID reversalId = UUID.randomUUID();

        userRepository.saveAndFlush(new User(
                userId,
                "Inconsistent Reversal Test",
                "inconsistent-reversal-" + userId + "@example.com"
        ));

        walletRepository.saveAndFlush(
                new Wallet(walletId, userId, WalletType.USER)
        );

        Transaction original = new Transaction(
                originalId,
                TransactionType.TOPUP,
                "original-" + originalId,
                new byte[32],
                100,
                userId,
                SYSTEM_WALLET_ID,
                walletId,
                null
        );

        original.complete((short) 200, "{}");
        transactionRepository.saveAndFlush(original);

        Transaction reversal = new Transaction(
                reversalId,
                TransactionType.REVERSAL,
                "reversal-" + reversalId,
                new byte[32],
                100,
                userId,
                walletId,
                SYSTEM_WALLET_ID,
                originalId
        );

        reversal.complete((short) 201, "{}");
        transactionRepository.saveAndFlush(reversal);

        // Both transactions have balanced, opposite ledger entries.
        ledgerEntryRepository.saveAllAndFlush(List.of(
                new LedgerEntry(
                        originalId,
                        SYSTEM_WALLET_ID,
                        LedgerEntryDirection.DEBIT,
                        100
                ),
                new LedgerEntry(
                        originalId,
                        walletId,
                        LedgerEntryDirection.CREDIT,
                        100
                ),
                new LedgerEntry(
                        reversalId,
                        walletId,
                        LedgerEntryDirection.DEBIT,
                        100
                ),
                new LedgerEntry(
                        reversalId,
                        SYSTEM_WALLET_ID,
                        LedgerEntryDirection.CREDIT,
                        100
                )
        ));

        // Net balances are zero and match the ledger histories.
        setWalletBalance(SYSTEM_WALLET_ID, 0);
        setWalletBalance(walletId, 0);

        // Deliberately inconsistent: original remains COMPLETED.
        assertTrue(reconciliationRunner.runOnce());

        assertLatestRunCounts(
                "VIOLATIONS_FOUND",
                0, // transaction-ledger
                2, // original ID + reversal ID
                0, // wallet balance
                0, // negative USER wallet
                0, // global balance
                2  // total
        );
    }

    // ---------------------------------------------------------
    // SHARED WALLET BALANCE HELPER
    // ---------------------------------------------------------

    private void setWalletBalance(UUID walletId, long balance) {
        jdbcTemplate.update(
                "UPDATE wallets SET balance = ? WHERE id = UUID_TO_BIN(?)",
                balance,
                walletId.toString()
        );
    }

    // ---------------------------------------------------------
    // SHARED ASSERTION HELPER
    // ---------------------------------------------------------

    private void assertLatestRunCounts(
            String expectedStatus,
            int expectedTransactionLedger,
            int expectedReversal,
            int expectedWalletBalance,
            int expectedNegativeUserWallet,
            int expectedGlobalBalance,
            int expectedTotal
    ) {

        Map<String, Object> run = jdbcTemplate.queryForMap(
                """
                SELECT
                    status,
                    transaction_ledger_violation_count,
                    reversal_violation_count,
                    wallet_balance_violation_count,
                    negative_user_wallet_violation_count,
                    global_balance_violation_count,
                    total_violation_count
                FROM reconciliation_runs
                ORDER BY id DESC
                LIMIT 1
                """
        );

        assertEquals(expectedStatus, run.get("status"));

        assertEquals(
                expectedTransactionLedger,
                ((Number) run.get("transaction_ledger_violation_count")).intValue()
        );

        assertEquals(
                expectedReversal,
                ((Number) run.get("reversal_violation_count")).intValue()
        );

        assertEquals(
                expectedWalletBalance,
                ((Number) run.get("wallet_balance_violation_count")).intValue()
        );

        assertEquals(
                expectedNegativeUserWallet,
                ((Number) run.get("negative_user_wallet_violation_count")).intValue()
        );

        assertEquals(
                expectedGlobalBalance,
                ((Number) run.get("global_balance_violation_count")).intValue()
        );

        assertEquals(
                expectedTotal,
                ((Number) run.get("total_violation_count")).intValue()
        );
    }
}
