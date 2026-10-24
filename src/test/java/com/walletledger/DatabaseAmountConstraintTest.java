package com.walletledger;

import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Testcontainers
class DatabaseAmountConstraintTest {

    @Container
    static final MySQLContainer mysql = createContainer();

    private static MySQLContainer createContainer() {
        MySQLContainer container = new MySQLContainer("mysql:8.4")
                .withDatabaseName("walletledger_test")
                .withUsername("test_owner")
                .withPassword("test_password");

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
    void zeroAndNegativeAmountsAreRejected() throws SQLException {
        UUID caller = UUID.randomUUID();
        UUID destinationUser = UUID.randomUUID();
        UUID sourceWallet = UUID.randomUUID();
        UUID destinationWallet = UUID.randomUUID();

        try (Connection connection = DriverManager.getConnection(
                mysql.getJdbcUrl(),
                mysql.getUsername(),
                mysql.getPassword())) {

            connection.setAutoCommit(false);

            try {
                insertUser(connection, caller);
                insertUser(connection, destinationUser);

                insertWallet(connection, sourceWallet, caller);
                insertWallet(connection, destinationWallet, destinationUser);

                for (long amount : new long[]{0L, -1L}) {
                    SQLException error = assertThrows(
                            SQLException.class,
                            () -> insertTransaction(
                                    connection,
                                    UUID.randomUUID(),
                                    caller,
                                    sourceWallet,
                                    destinationWallet,
                                    "amount-test-" + amount,
                                    amount
                            ),
                            "Amount " + amount + " should be rejected"
                    );

                    // MySQL CHECK constraint violation.
                    assertEquals(3819, error.getErrorCode());
                    assertEquals("HY000", error.getSQLState());
                }
            } finally {
                connection.rollback();
            }
        }
    }

    private static void insertUser(
            Connection connection, UUID id) throws SQLException {

        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO users (id, name, email) VALUES (?, ?, ?)")) {
            statement.setBytes(1, uuidToBytes(id));
            statement.setString(2, "Amount Test User");
            statement.setString(3, "test-" + id + "@walletledger.test");
            statement.executeUpdate();
        }
    }

    private static void insertWallet(
            Connection connection, UUID walletId, UUID userId)
            throws SQLException {

        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO wallets (id, user_id, wallet_type, currency, balance)
                VALUES (?, ?, 'USER', 'INR', 0)
                """)) {
            statement.setBytes(1, uuidToBytes(walletId));
            statement.setBytes(2, uuidToBytes(userId));
            statement.executeUpdate();
        }
    }

    private static void insertTransaction(
            Connection connection,
            UUID transactionId,
            UUID caller,
            UUID sourceWallet,
            UUID destinationWallet,
            String key,
            long amount) throws SQLException {

        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO transactions (
                    id, type, status, idempotency_key, request_hash,
                    amount, initiated_by, source_wallet_id, destination_wallet_id
                )
                VALUES (?, 'TRANSFER', 'PENDING', ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setBytes(1, uuidToBytes(transactionId));
            statement.setString(2, key);
            statement.setBytes(3, new byte[32]);
            statement.setLong(4, amount);
            statement.setBytes(5, uuidToBytes(caller));
            statement.setBytes(6, uuidToBytes(sourceWallet));
            statement.setBytes(7, uuidToBytes(destinationWallet));
            statement.executeUpdate();
        }
    }

    private static byte[] uuidToBytes(UUID uuid) {
        return ByteBuffer.allocate(16)
                .putLong(uuid.getMostSignificantBits())
                .putLong(uuid.getLeastSignificantBits())
                .array();
    }
}