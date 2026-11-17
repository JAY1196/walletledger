
package com.walletledger;

import com.walletledger.reconciliation.ReconciliationProperties;
import com.walletledger.reconciliation.ReconciliationRunner;
import com.walletledger.reconciliation.SqlReconciliationChecker;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class NegativeUserWalletReconciliationTest {

    private static final String DATABASE = "walletledger_test";
    private static final String USERNAME = "test_owner";
    private static final String PASSWORD = "test_password";

    private static final UUID SYSTEM_WALLET_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Container
    private static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>("mysql:8.4")
                    .withDatabaseName(DATABASE)
                    .withUsername(USERNAME)
                    .withPassword(PASSWORD)
                    .withCommand("--log-bin-trust-function-creators=ON");

    private static DataSource dataSource;
    private static ReconciliationRunner runner;

    @BeforeAll
    static void migrateAndConfigureRunner() {

        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("com.mysql.cj.jdbc.Driver");
        source.setUrl(MYSQL.getJdbcUrl());
        source.setUsername(MYSQL.getUsername());
        source.setPassword(MYSQL.getPassword());

        dataSource = source;

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        runner = new ReconciliationRunner(
                dataSource,
                new SqlReconciliationChecker(),
                new ReconciliationProperties(
                        "0 0 * * * *",
                        "negative-user-wallet-test",
                        0
                )
        );
    }

    @Test
    void negativeUserWalletIsTheOnlyDetectedViolation() throws Exception {

        UUID userId = UUID.randomUUID();
        UUID userWalletId = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();

        boolean constraintDisabled = false;

        try {

            try (Connection connection = dataSource.getConnection()) {

                assertBalanceConstraintExists(connection);

                try (Statement statement = connection.createStatement()) {
                    statement.execute(
                            "ALTER TABLE wallets " +
                            "ALTER CHECK chk_wallets_balance NOT ENFORCED"
                    );
                }

                constraintDisabled = true;

                // Keep all seed DML atomic. If seeding fails, rollback
                // prevents partially inserted test data.
                connection.setAutoCommit(false);

                try {

                    insertUser(connection, userId);

                    insertWallet(
                            connection,
                            userWalletId,
                            userId,
                            "USER",
                            -100
                    );

                    updateWalletBalance(
                            connection,
                            SYSTEM_WALLET_ID,
                            100
                    );

                    insertCompletedWithdrawal(
                            connection,
                            transactionId,
                            userId,
                            userWalletId,
                            SYSTEM_WALLET_ID
                    );

                    insertLedgerEntry(
                            connection,
                            transactionId,
                            userWalletId,
                            "DEBIT",
                            100
                    );

                    insertLedgerEntry(
                            connection,
                            transactionId,
                            SYSTEM_WALLET_ID,
                            "CREDIT",
                            100
                    );

                    connection.commit();

                } catch (Exception exception) {
                    connection.rollback();
                    throw exception;
                } finally {
                    connection.setAutoCommit(true);
                }
            }

            // Reconciliation runs while the USER wallet is negative.
            assertTrue(runner.runOnce());

            assertLatestRunHasOnlyNegativeWalletViolation();

        } finally {

            if (constraintDisabled) {
                cleanupAndRestore(
                        userId,
                        userWalletId,
                        transactionId
                );
            }
        }
    }

    private static void assertLatestRunHasOnlyNegativeWalletViolation()
            throws Exception {

        String sql = """
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
                """;

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {

            assertTrue(resultSet.next(), "Expected a reconciliation run");

            assertEquals(
                    "VIOLATIONS_FOUND",
                    resultSet.getString("status")
            );

            assertEquals(
                    0,
                    resultSet.getLong("transaction_ledger_violation_count")
            );

            assertEquals(
                    0,
                    resultSet.getLong("reversal_violation_count")
            );

            assertEquals(
                    0,
                    resultSet.getLong("wallet_balance_violation_count")
            );

            assertEquals(
                    1,
                    resultSet.getLong("negative_user_wallet_violation_count")
            );

            assertEquals(
                    0,
                    resultSet.getLong("global_balance_violation_count")
            );

            assertEquals(
                    1,
                    resultSet.getLong("total_violation_count")
            );
        }
    }

    private static void assertBalanceConstraintExists(
            Connection connection) throws Exception {

        String sql = """
                SELECT COUNT(*)
                FROM information_schema.table_constraints
                WHERE constraint_schema = DATABASE()
                  AND table_name = 'wallets'
                  AND constraint_name = 'chk_wallets_balance'
                  AND constraint_type = 'CHECK'
                """;

        try (PreparedStatement statement =
                     connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {

            assertTrue(resultSet.next());

            assertEquals(
                    1,
                    resultSet.getInt(1),
                    "Expected chk_wallets_balance to exist"
            );
        }
    }

    private static void insertUser(
            Connection connection,
            UUID userId) throws Exception {

        String sql = """
                INSERT INTO users (id, name, email)
                VALUES (?, 'Negative Balance Test', ?)
                """;

        try (PreparedStatement statement =
                     connection.prepareStatement(sql)) {

            statement.setBytes(1, uuidToBytes(userId));
            statement.setString(
                    2,
                    "negative-wallet-" + userId + "@test.local"
            );

            statement.executeUpdate();
        }
    }

    private static void insertWallet(
            Connection connection,
            UUID walletId,
            UUID userId,
            String walletType,
            long balance) throws Exception {

        String sql = """
                INSERT INTO wallets (
                    id,
                    user_id,
                    wallet_type,
                    currency,
                    balance,
                    version
                )
                VALUES (?, ?, ?, 'INR', ?, 0)
                """;

        try (PreparedStatement statement =
                     connection.prepareStatement(sql)) {

            statement.setBytes(1, uuidToBytes(walletId));
            statement.setBytes(2, uuidToBytes(userId));
            statement.setString(3, walletType);
            statement.setLong(4, balance);

            statement.executeUpdate();
        }
    }

    private static void updateWalletBalance(
            Connection connection,
            UUID walletId,
            long balance) throws Exception {

        String sql = """
                UPDATE wallets
                SET balance = ?
                WHERE id = ?
                """;

        try (PreparedStatement statement =
                     connection.prepareStatement(sql)) {

            statement.setLong(1, balance);
            statement.setBytes(2, uuidToBytes(walletId));

            assertEquals(1, statement.executeUpdate());
        }
    }

    private static void insertCompletedWithdrawal(
            Connection connection,
            UUID transactionId,
            UUID userId,
            UUID userWalletId,
            UUID systemWalletId) throws Exception {

        String sql = """
                INSERT INTO transactions (
                    id,
                    type,
                    status,
                    idempotency_key,
                    request_hash,
                    amount,
                    initiated_by,
                    source_wallet_id,
                    destination_wallet_id
                )
                VALUES (?, 'WITHDRAWAL', 'COMPLETED', ?, ?, 100, ?, ?, ?)
                """;

        try (PreparedStatement statement =
                     connection.prepareStatement(sql)) {

            statement.setBytes(1, uuidToBytes(transactionId));
            statement.setString(
                    2,
                    "negative-wallet-test-" + transactionId
            );
            statement.setBytes(3, new byte[32]);
            statement.setBytes(4, uuidToBytes(userId));
            statement.setBytes(5, uuidToBytes(userWalletId));
            statement.setBytes(6, uuidToBytes(systemWalletId));

            statement.executeUpdate();
        }
    }

    private static void insertCompletedCompensatingTransfer(
            Connection connection,
            UUID transactionId,
            UUID userId,
            UUID userWalletId,
            UUID systemWalletId) throws Exception {

        String sql = """
                INSERT INTO transactions (
                    id,
                    type,
                    status,
                    idempotency_key,
                    request_hash,
                    amount,
                    initiated_by,
                    source_wallet_id,
                    destination_wallet_id
                )
                VALUES (?, 'TRANSFER', 'COMPLETED', ?, ?, 100, ?, ?, ?)
                """;

        try (PreparedStatement statement =
                     connection.prepareStatement(sql)) {

            statement.setBytes(1, uuidToBytes(transactionId));
            statement.setString(
                    2,
                    "negative-wallet-compensation-" + transactionId
            );
            statement.setBytes(3, new byte[32]);
            statement.setBytes(4, uuidToBytes(userId));
            statement.setBytes(5, uuidToBytes(systemWalletId));
            statement.setBytes(6, uuidToBytes(userWalletId));

            statement.executeUpdate();
        }
    }

    private static void insertLedgerEntry(
            Connection connection,
            UUID transactionId,
            UUID walletId,
            String direction,
            long amount) throws Exception {

        String sql = """
                INSERT INTO ledger_entries (
                    transaction_id,
                    wallet_id,
                    direction,
                    amount
                )
                VALUES (?, ?, ?, ?)
                """;

        try (PreparedStatement statement =
                     connection.prepareStatement(sql)) {

            statement.setBytes(1, uuidToBytes(transactionId));
            statement.setBytes(2, uuidToBytes(walletId));
            statement.setString(3, direction);
            statement.setLong(4, amount);

            statement.executeUpdate();
        }
    }

    private static boolean transactionExists(
            Connection connection,
            UUID transactionId) throws Exception {

        String sql = """
                SELECT COUNT(*)
                FROM transactions
                WHERE id = ?
                """;

        try (PreparedStatement statement =
                     connection.prepareStatement(sql)) {

            statement.setBytes(1, uuidToBytes(transactionId));

            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next());
                return resultSet.getLong(1) == 1;
            }
        }
    }

    private static void cleanupAndRestore(
            UUID userId,
            UUID userWalletId,
            UUID originalTransactionId) throws Exception {

        try (Connection connection = dataSource.getConnection()) {

            connection.setAutoCommit(false);

            try {

                // Never UPDATE or DELETE ledger_entries.
                // Append a compensating transaction instead.
                if (transactionExists(connection, originalTransactionId)) {

                    UUID compensationId = UUID.randomUUID();

                    insertCompletedCompensatingTransfer(
                            connection,
                            compensationId,
                            userId,
                            userWalletId,
                            SYSTEM_WALLET_ID
                    );

                    insertLedgerEntry(
                            connection,
                            compensationId,
                            SYSTEM_WALLET_ID,
                            "DEBIT",
                            100
                    );

                    insertLedgerEntry(
                            connection,
                            compensationId,
                            userWalletId,
                            "CREDIT",
                            100
                    );

                    // Both wallets now have a net ledger balance of zero.
                    updateWalletBalance(connection, userWalletId, 0);
                    updateWalletBalance(connection, SYSTEM_WALLET_ID, 0);
                }

                connection.commit();

            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }

            // The negative balance has been corrected before enforcement.
            try (Statement statement = connection.createStatement()) {
                statement.execute(
                        "ALTER TABLE wallets " +
                        "ALTER CHECK chk_wallets_balance ENFORCED"
                );
            }

            // Test records intentionally remain in this disposable
            // container. Testcontainers destroys it after the class.
        }
    }

    private static byte[] uuidToBytes(UUID uuid) {

        ByteBuffer buffer = ByteBuffer.allocate(16);

        buffer.putLong(uuid.getMostSignificantBits());
        buffer.putLong(uuid.getLeastSignificantBits());

        return buffer.array();
    }
}
