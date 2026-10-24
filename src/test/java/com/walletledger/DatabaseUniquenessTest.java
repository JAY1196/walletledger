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

import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Testcontainers
class DatabaseUniquenessTest {

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
    void onlyOneInrWalletPerUserIsAllowed() throws SQLException {
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);

            try {
                UUID userId = UUID.randomUUID();
                insertUser(connection, userId);

                insertWallet(
                        connection, UUID.randomUUID(), userId, "USER", 0L
                );

                SQLException error = assertThrows(
                        SQLException.class,
                        () -> insertWallet(
                                connection, UUID.randomUUID(), userId,
                                "USER", 0L
                        )
                );

                assertEquals(1062, error.getErrorCode());
                assertEquals("23000", error.getSQLState());
                org.junit.jupiter.api.Assertions.assertTrue(
                        error.getMessage().contains("uq_wallets_user_currency"),
                        "Duplicate-key message should identify the wallet uniqueness constraint"
                );
            } finally {
                connection.rollback();
            }
        }
    }
    @Test
    void onlyOneSystemWalletIsAllowed() throws SQLException {
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);

            try {
                UUID userId = UUID.randomUUID();
                insertUser(connection, userId);

                SQLException error = assertThrows(
                        SQLException.class,
                        () -> insertWallet(
                                connection,
                                UUID.randomUUID(),
                                userId,
                                "SYSTEM",
                                0L
                        )
                );

                // Duplicate unique index: MySQL error 1062 / SQLSTATE 23000.
                assertEquals(1062, error.getErrorCode());
                assertEquals("23000", error.getSQLState());
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    void idempotencyKeyIsUniquePerCallerNotGlobally()
            throws SQLException {

        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);

            try {
                UUID user1 = UUID.randomUUID();
                UUID user2 = UUID.randomUUID();
                UUID wallet1 = UUID.randomUUID();
                UUID wallet2 = UUID.randomUUID();

                insertUser(connection, user1);
                insertUser(connection, user2);
                insertWallet(connection, wallet1, user1, "USER", 0L);
                insertWallet(connection, wallet2, user2, "USER", 0L);

                String sharedKey = "same-idempotency-key";

                // First request by caller 1 succeeds.
                insertTransaction(
                        connection, UUID.randomUUID(), user1,
                        wallet1, wallet2, sharedKey
                );

                // Reusing that key for caller 1 must fail.
                SQLException error = assertThrows(
                        SQLException.class,
                        () -> insertTransaction(
                                connection, UUID.randomUUID(), user1,
                                wallet1, wallet2, sharedKey
                        )
                );

                assertEquals(1062, error.getErrorCode());
                assertEquals("23000", error.getSQLState());

                // Caller 2 is allowed to use the same key.
                assertDoesNotThrow(() -> insertTransaction(
                        connection, UUID.randomUUID(), user2,
                        wallet1, wallet2, sharedKey
                ));
            } finally {
                connection.rollback();
            }
        }
    }

    private static Connection openConnection() throws SQLException {
        return DriverManager.getConnection(
                mysql.getJdbcUrl(),
                mysql.getUsername(),
                mysql.getPassword()
        );
    }

    private static void insertUser(
            Connection connection, UUID userId) throws SQLException {

        String sql = "INSERT INTO users (id, name, email) VALUES (?, ?, ?)";

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBytes(1, uuidToBytes(userId));
            statement.setString(2, "Uniqueness Test User");
            statement.setString(
                    3, "test-" + userId + "@walletledger.test"
            );
            statement.executeUpdate();
        }
    }

    private static void insertWallet(
            Connection connection,
            UUID walletId,
            UUID userId,
            String walletType,
            long balance) throws SQLException {

        String sql = """
                INSERT INTO wallets
                    (id, user_id, wallet_type, currency, balance)
                VALUES (?, ?, ?, 'INR', ?)
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBytes(1, uuidToBytes(walletId));
            statement.setBytes(2, uuidToBytes(userId));
            statement.setString(3, walletType);
            statement.setLong(4, balance);
            statement.executeUpdate();
        }
    }

    private static void insertTransaction(
            Connection connection,
            UUID transactionId,
            UUID callerId,
            UUID sourceWallet,
            UUID destinationWallet,
            String idempotencyKey) throws SQLException {

        String sql = """
                INSERT INTO transactions (
                    id, type, status, idempotency_key, request_hash,
                    amount, initiated_by, source_wallet_id,
                    destination_wallet_id
                )
                VALUES (?, 'TRANSFER', 'PENDING', ?, ?, 100, ?, ?, ?)
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBytes(1, uuidToBytes(transactionId));
            statement.setString(2, idempotencyKey);
            statement.setBytes(3, new byte[32]);
            statement.setBytes(4, uuidToBytes(callerId));
            statement.setBytes(5, uuidToBytes(sourceWallet));
            statement.setBytes(6, uuidToBytes(destinationWallet));
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