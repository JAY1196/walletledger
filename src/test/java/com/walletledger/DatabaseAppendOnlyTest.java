package com.walletledger;

import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
class DatabaseAppendOnlyTest {

    @Container
    static final MySQLContainer mysql = createContainer();

    private static MySQLContainer createContainer() {
        MySQLContainer container = new MySQLContainer("mysql:8.4");
        container.withDatabaseName("walletledger_test");
        container.withUsername("test_owner");
        container.withPassword("test_password");
        container.withCommand("--log-bin-trust-function-creators=ON");
        return container;
    }

    @BeforeAll
    static void migrateSchema() {
        Flyway.configure()
                .dataSource(
                        mysql.getJdbcUrl(),
                        mysql.getUsername(),
                        mysql.getPassword()
                )
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @Test
    void ledgerAndAuditCannotBeUpdatedOrDeleted() throws SQLException {
        try (Connection connection = openConnection()) {
            UUID userId = UUID.randomUUID();
            UUID recipientId = UUID.randomUUID();
            UUID sourceWalletId = UUID.randomUUID();
            UUID destinationWalletId = UUID.randomUUID();
            UUID transactionId = UUID.randomUUID();

            insertUser(connection, userId);
            insertUser(connection, recipientId);
            insertWallet(connection, sourceWalletId, userId);
            insertWallet(connection, destinationWalletId, recipientId);

            insertTransaction(
                    connection,
                    transactionId,
                    userId,
                    sourceWalletId,
                    destinationWalletId
            );

            long ledgerId = insertLedgerEntry(
                    connection, transactionId, sourceWalletId
            );

            long auditId = insertAuditEntry(
                    connection, transactionId, userId
            );

            // All four attempts must be rejected by their triggers.
            assertAppendOnlyViolation(
                    connection,
                    "UPDATE ledger_entries SET amount = amount + 1 WHERE id = ?",
                    ledgerId,
                    "ledger_entries"
            );

            assertAppendOnlyViolation(
                    connection,
                    "DELETE FROM ledger_entries WHERE id = ?",
                    ledgerId,
                    "ledger_entries"
            );

            assertAppendOnlyViolation(
                    connection,
                    "UPDATE transaction_audit SET reason = 'tampered' WHERE id = ?",
                    auditId,
                    "transaction_audit"
            );

            assertAppendOnlyViolation(
                    connection,
                    "DELETE FROM transaction_audit WHERE id = ?",
                    auditId,
                    "transaction_audit"
            );

            assertEquals(1, countRows(
                    connection,
                    "SELECT COUNT(*) FROM ledger_entries WHERE id = " + ledgerId
            ));

            assertEquals(1, countRows(
                    connection,
                    "SELECT COUNT(*) FROM transaction_audit WHERE id = " + auditId
            ));
        }
    }

    private static void assertAppendOnlyViolation(
            Connection connection,
            String sql,
            long id,
            String table) throws SQLException {

        SQLException error = assertThrows(
                SQLException.class,
                () -> {
                    try (PreparedStatement statement =
                                 connection.prepareStatement(sql)) {
                        statement.setLong(1, id);
                        statement.executeUpdate();
                    }
                },
                table + " must reject this operation"
        );

        assertEquals(1644, error.getErrorCode());
        assertEquals("45000", error.getSQLState());
        assertTrue(
                error.getMessage().contains("append-only"),
                "Unexpected trigger message: " + error.getMessage()
        );
    }

    private static Connection openConnection() throws SQLException {
        return DriverManager.getConnection(
                mysql.getJdbcUrl(),
                mysql.getUsername(),
                mysql.getPassword()
        );
    }

    private static void insertUser(
            Connection connection, UUID id) throws SQLException {

        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO users (id, name, email) VALUES (?, ?, ?)")) {
            statement.setBytes(1, uuidToBytes(id));
            statement.setString(2, "Append Only Test User");
            statement.setString(3, "test-" + id + "@walletledger.test");
            statement.executeUpdate();
        }
    }

    private static void insertWallet(
            Connection connection, UUID walletId, UUID userId)
            throws SQLException {

        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO wallets (id, user_id, wallet_type, currency, balance)
                VALUES (?, ?, 'USER', 'INR', 10000)
                """)) {
            statement.setBytes(1, uuidToBytes(walletId));
            statement.setBytes(2, uuidToBytes(userId));
            statement.executeUpdate();
        }
    }

    private static void insertTransaction(
            Connection connection,
            UUID transactionId,
            UUID callerId,
            UUID sourceWalletId,
            UUID destinationWalletId) throws SQLException {

        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO transactions (
                    id, type, status, idempotency_key, request_hash,
                    amount, initiated_by, source_wallet_id, destination_wallet_id
                )
                VALUES (?, 'TRANSFER', 'COMPLETED', ?, ?, 100,
                        ?, ?, ?)
                """)) {
            statement.setBytes(1, uuidToBytes(transactionId));
            statement.setString(2, "append-only-" + transactionId);
            statement.setBytes(3, new byte[32]);
            statement.setBytes(4, uuidToBytes(callerId));
            statement.setBytes(5, uuidToBytes(sourceWalletId));
            statement.setBytes(6, uuidToBytes(destinationWalletId));
            statement.executeUpdate();
        }
    }

    private static long insertLedgerEntry(
            Connection connection,
            UUID transactionId,
            UUID walletId) throws SQLException {

        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO ledger_entries
                    (transaction_id, wallet_id, direction, amount)
                VALUES (?, ?, 'DEBIT', 100)
                """, Statement.RETURN_GENERATED_KEYS)) {
            statement.setBytes(1, uuidToBytes(transactionId));
            statement.setBytes(2, uuidToBytes(walletId));
            statement.executeUpdate();

            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private static long insertAuditEntry(
            Connection connection,
            UUID transactionId,
            UUID changedBy) throws SQLException {

        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO transaction_audit
                    (transaction_id, from_status, to_status, changed_by, reason)
                VALUES (?, NULL, 'COMPLETED', ?, 'append-only test')
                """, Statement.RETURN_GENERATED_KEYS)) {
            statement.setBytes(1, uuidToBytes(transactionId));
            statement.setBytes(2, uuidToBytes(changedBy));
            statement.executeUpdate();

            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private static int countRows(Connection connection, String sql)
            throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
        }
    }

    private static byte[] uuidToBytes(UUID uuid) {
        return ByteBuffer.allocate(16)
                .putLong(uuid.getMostSignificantBits())
                .putLong(uuid.getLeastSignificantBits())
                .array();
    }
}