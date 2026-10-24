package com.walletledger;

import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
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
class DatabaseReversalConstraintTest {

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
    void failedReversalDoesNotBurnSlotAndSecondReversalIsRejected()
            throws SQLException {

        try (Connection connection = DriverManager.getConnection(
                mysql.getJdbcUrl(),
                mysql.getUsername(),
                mysql.getPassword())) {

            connection.setAutoCommit(false);

            try {
                UUID caller = UUID.randomUUID();
                UUID destinationOwner = UUID.randomUUID();

                UUID sourceWallet = UUID.randomUUID();
                UUID destinationWallet = UUID.randomUUID();

                UUID originalId = UUID.randomUUID();

                insertUser(connection, caller);
                insertUser(connection, destinationOwner);

                insertWallet(connection, sourceWallet, caller);
                insertWallet(connection, destinationWallet, destinationOwner);

                // Original transfer.
                insertTransaction(
                        connection,
                        originalId,
                        caller,
                        sourceWallet,
                        destinationWallet,
                        "original-key",
                        "TRANSFER",
                        "COMPLETED",
                        null
                );

                // A FAILED reversal must not reserve the reversal slot.
                insertTransaction(
                        connection,
                        UUID.randomUUID(),
                        caller,
                        destinationWallet,
                        sourceWallet,
                        "failed-reversal-key",
                        "REVERSAL",
                        "FAILED",
                        originalId
                );

                // First non-failed reversal is allowed.
                insertTransaction(
                        connection,
                        UUID.randomUUID(),
                        caller,
                        destinationWallet,
                        sourceWallet,
                        "successful-reversal-key",
                        "REVERSAL",
                        "COMPLETED",
                        originalId
                );

                // A second non-failed reversal of the same original must fail.
                SQLException error = assertThrows(
                        SQLException.class,
                        () -> insertTransaction(
                                connection,
                                UUID.randomUUID(),
                                caller,
                                destinationWallet,
                                sourceWallet,
                                "second-reversal-key",
                                "REVERSAL",
                                "PENDING",
                                originalId
                        )
                );

                // Duplicate unique key: MySQL 1062 / SQLSTATE 23000.
                assertEquals(1062, error.getErrorCode());
                assertEquals("23000", error.getSQLState());

                assertTrue(
                        error.getMessage().contains(
                                "uq_transactions_reversal_once"
                        ),
                        "Expected reversal unique constraint, got: "
                                + error.getMessage()
                );
            } finally {
                connection.rollback();
            }
        }
    }

    private static void insertUser(
            Connection connection, UUID id) throws SQLException {

        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO users (id, name, email)
                VALUES (?, 'Reversal Test User', ?)
                """)) {
            statement.setBytes(1, uuidToBytes(id));
            statement.setString(2, "test-" + id + "@walletledger.test");
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
            UUID id,
            UUID caller,
            UUID source,
            UUID destination,
            String key,
            String type,
            String status,
            UUID originalId) throws SQLException {

        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO transactions (
                    id, type, status, idempotency_key, request_hash,
                    amount, initiated_by, source_wallet_id,
                    destination_wallet_id, original_txn_id
                )
                VALUES (?, ?, ?, ?, ?, 100, ?, ?, ?, ?)
                """)) {

            statement.setBytes(1, uuidToBytes(id));
            statement.setString(2, type);
            statement.setString(3, status);
            statement.setString(4, key);
            statement.setBytes(5, new byte[32]);
            statement.setBytes(6, uuidToBytes(caller));
            statement.setBytes(7, uuidToBytes(source));
            statement.setBytes(8, uuidToBytes(destination));

            if (originalId == null) {
                statement.setNull(9, Types.BINARY);
            } else {
                statement.setBytes(9, uuidToBytes(originalId));
            }

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