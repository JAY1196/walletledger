
package com.walletledger;

import com.walletledger.entity.LedgerEntry;
import com.walletledger.entity.LedgerEntryDirection;
import com.walletledger.entity.Transaction;
import com.walletledger.entity.TransactionStatus;
import com.walletledger.entity.TransactionType;
import com.walletledger.entity.User;
import com.walletledger.entity.Wallet;
import com.walletledger.entity.WalletType;
import com.walletledger.repository.LedgerEntryRepository;
import com.walletledger.repository.TransactionRepository;
import com.walletledger.repository.UserRepository;
import com.walletledger.repository.WalletRepository;
import com.walletledger.service.LedgerInvariantChecker;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class LedgerInvariantCheckerIntegrationTest {

    @Autowired
    private LedgerInvariantChecker checker;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WalletRepository walletRepository;

    @Test
    @Transactional
    void balancedCompletedTransactionHasNoViolations() {
        Transaction transaction = createTransaction(
                TransactionStatus.COMPLETED, 100L);

        addEntry(transaction, LedgerEntryDirection.DEBIT, 100L);
        addEntry(transaction, LedgerEntryDirection.CREDIT, 100L);

        assertTrue(checker.findViolations().isEmpty());
    }

    @Test
    @Transactional
    void unbalancedCompletedTransactionIsDetected() {
        Transaction transaction = createTransaction(
                TransactionStatus.COMPLETED, 100L);

        addEntry(transaction, LedgerEntryDirection.DEBIT, 100L);
        addEntry(transaction, LedgerEntryDirection.CREDIT, 90L);

        List<String> violations = checker.findViolations();

        assertTrue(
                violations.stream().anyMatch(v ->
                        v.contains(transaction.getId().toString())),
                "Expected the unbalanced transaction to be flagged");
    }

    @Test
    @Transactional
    void completedTransactionWithoutEntriesIsDetected() {
        Transaction transaction = createTransaction(
                TransactionStatus.COMPLETED, 100L);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> checker.assertNoViolations());

        assertTrue(
                exception.getMessage().contains(
                        transaction.getId().toString()),
                "The full invariant check should flag the transaction with missing entries");
    }

    @Test
    @Transactional
    void balancedReversedTransactionHasNoViolations() {
        Transaction transaction = createTransaction(
                TransactionStatus.COMPLETED, 100L);

        transaction.markReversed((short) 200, "{}");
        transactionRepository.saveAndFlush(transaction);

        addEntry(transaction, LedgerEntryDirection.DEBIT, 100L);
        addEntry(transaction, LedgerEntryDirection.CREDIT, 100L);

        assertTrue(
                checker.findViolations().isEmpty(),
                "A balanced reversed transaction should have no violations");
    }

    @Test
    @Transactional
    void pendingAndFailedTransactionsWithoutEntriesAreIgnored() {
        createTransaction(TransactionStatus.PENDING, 100L);
        createTransaction(TransactionStatus.FAILED, 200L);

        assertTrue(
                checker.findViolations().isEmpty(),
                "Pending and failed transactions without postings should be ignored");
    }

    @Test
    @Transactional
    void walletBalanceMatchingLedgerHistoryHasNoViolations() {
        UUID userId = UUID.randomUUID();
        String suffix = UUID.randomUUID().toString();

        userRepository.saveAndFlush(new User(
                userId,
                "Wallet Reconciliation User",
                "reconcile-match-" + suffix + "@walletledger.test"
        ));

        UUID walletId = UUID.randomUUID();
        Wallet wallet = new Wallet(walletId, userId, WalletType.USER);
        wallet.credit(100L);
        walletRepository.saveAndFlush(wallet);

        Transaction transaction = createTransaction(
                TransactionStatus.COMPLETED, 100L);

        ledgerEntryRepository.saveAndFlush(new LedgerEntry(
                transaction.getId(),
                walletId,
                LedgerEntryDirection.CREDIT,
                100L
        ));

        assertFalse(
                checker.findWalletBalanceViolations().stream()
                        .anyMatch(v -> v.contains(walletId.toString())),
                "Matching cached and ledger balances should not be flagged");
    }

    @Test
    @Transactional
    void walletBalanceMismatchWithLedgerHistoryIsDetected() {
        UUID userId = UUID.randomUUID();
        String suffix = UUID.randomUUID().toString();

        userRepository.saveAndFlush(new User(
                userId,
                "Wallet Reconciliation User",
                "reconcile-mismatch-" + suffix + "@walletledger.test"
        ));

        UUID walletId = UUID.randomUUID();
        Wallet wallet = new Wallet(walletId, userId, WalletType.USER);
        wallet.credit(100L);
        walletRepository.saveAndFlush(wallet);

        Transaction transaction = createTransaction(
                TransactionStatus.COMPLETED, 100L);

        ledgerEntryRepository.saveAndFlush(new LedgerEntry(
                transaction.getId(),
                walletId,
                LedgerEntryDirection.CREDIT,
                80L
        ));

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> checker.assertNoViolations());

        assertTrue(
                exception.getMessage().contains(walletId.toString()),
                "The full invariant check should flag the corrupted cached balance");
    }

    @Test
    @Transactional
    void walletWithNonzeroBalanceAndNoLedgerEntriesIsDetected() {
        UUID userId = UUID.randomUUID();
        String suffix = UUID.randomUUID().toString();

        userRepository.saveAndFlush(new User(
                userId,
                "Missing Ledger User",
                "missing-ledger-" + suffix + "@walletledger.test"
        ));

        UUID walletId = UUID.randomUUID();
        Wallet wallet = new Wallet(walletId, userId, WalletType.USER);
        wallet.credit(100L);
        walletRepository.saveAndFlush(wallet);

        assertTrue(
                checker.findWalletBalanceViolations().stream()
                        .anyMatch(v -> v.contains(walletId.toString())),
                "A nonzero wallet balance without ledger entries must be detected");
    }

    @Test
    @Transactional
    void globalBalanceMismatchIsDetected() {
        BigDecimal initialTotal = walletRepository.sumAllWalletBalances();

        UUID userId = UUID.randomUUID();
        String suffix = UUID.randomUUID().toString();

        userRepository.saveAndFlush(new User(
                userId,
                "Global Balance User",
                "global-balance-" + suffix + "@walletledger.test"
        ));

        UUID walletId = UUID.randomUUID();
        Wallet wallet = new Wallet(walletId, userId, WalletType.USER);
        wallet.credit(100L);
        walletRepository.saveAndFlush(wallet);

        BigDecimal updatedTotal = walletRepository.sumAllWalletBalances();

        assertEquals(
                0,
                updatedTotal.subtract(initialTotal)
                        .compareTo(BigDecimal.valueOf(100L)),
                "Adding 100 paise to a wallet should increase the global total by 100 paise");

        assertFalse(
                checker.findGlobalBalanceViolations().isEmpty(),
                "A nonzero global wallet balance sum must be detected");
    }

    @Test
    @Transactional
    void systemWalletCanReconcileToANegativeBalance() {
        Wallet systemWallet = walletRepository.findById(
                UUID.fromString("00000000-0000-0000-0000-000000000002")
        ).orElseThrow();

        long originalBalance = systemWallet.getBalance();

        Transaction transaction = createTransaction(
                TransactionStatus.COMPLETED, 25L);

        ledgerEntryRepository.saveAndFlush(new LedgerEntry(
                transaction.getId(),
                systemWallet.getId(),
                LedgerEntryDirection.DEBIT,
                25L
        ));

        systemWallet.debit(25L);
        walletRepository.saveAndFlush(systemWallet);

        assertEquals(
                originalBalance - 25L,
                systemWallet.getBalance());

        assertFalse(
                checker.findWalletBalanceViolations().stream()
                        .anyMatch(v ->
                                v.contains(systemWallet.getId().toString())),
                "The SYSTEM wallet should reconcile even when its balance is negative");
    }

    private Transaction createTransaction(
            TransactionStatus desiredStatus, long amount) {

        UUID callerId = UUID.randomUUID();
        UUID destinationOwnerId = UUID.randomUUID();
        UUID sourceWalletId = UUID.randomUUID();
        UUID destinationWalletId = UUID.randomUUID();
        String suffix = UUID.randomUUID().toString();

        userRepository.saveAndFlush(new User(
                callerId,
                "Invariant Test Caller",
                "invariant-caller-" + suffix + "@walletledger.test"
        ));

        userRepository.saveAndFlush(new User(
                destinationOwnerId,
                "Invariant Test Destination",
                "invariant-destination-" + suffix + "@walletledger.test"
        ));

        walletRepository.saveAndFlush(
                new Wallet(sourceWalletId, callerId, WalletType.USER));

        walletRepository.saveAndFlush(
                new Wallet(destinationWalletId, destinationOwnerId, WalletType.USER));

        Transaction transaction = new Transaction(
                UUID.randomUUID(),
                TransactionType.TRANSFER,
                "invariant-" + suffix,
                new byte[32],
                amount,
                callerId,
                sourceWalletId,
                destinationWalletId,
                null
        );

        if (desiredStatus == TransactionStatus.COMPLETED) {
            transaction.complete((short) 201, "{}");
        } else if (desiredStatus == TransactionStatus.FAILED) {
            transaction.fail((short) 422, "{}");
        }

        return transactionRepository.saveAndFlush(transaction);
    }

    private void addEntry(
            Transaction transaction,
            LedgerEntryDirection direction,
            long amount) {

        UUID walletId = direction == LedgerEntryDirection.DEBIT
                ? transaction.getSourceWalletId()
                : transaction.getDestinationWalletId();

        ledgerEntryRepository.saveAndFlush(
                new LedgerEntry(
                        transaction.getId(),
                        walletId,
                        direction,
                        amount
                )
        );
    }
}
