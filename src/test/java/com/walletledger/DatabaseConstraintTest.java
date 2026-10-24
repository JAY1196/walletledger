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

import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Testcontainers
class DatabaseConstraintTest {

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
    void userWalletCannotHaveNegativeBalance() throws SQLException {
        UUID userId = UUID.randomUUID();
        UUID walletId = UUID.randomUUID();

        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);

            try {
                insertUser(connection, userId);

                SQLException error = assertThrows(
                        SQLException.class,
                        () -> insertWallet(
                                connection, walletId, userId, "USER", -1L
                        )
                );

                assertEquals(3819, error.getErrorCode());
                assertEquals("HY000", error.getSQLState());
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    void systemWalletMayHaveNegativeBalance() throws SQLException {
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);

            try {
                int updated;

                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE wallets SET balance = -100 " +
                        "WHERE wallet_type = 'SYSTEM'")) {
                    updated = statement.executeUpdate();
                }

                assertEquals(1, updated);

                try (Statement statement = connection.createStatement();
                     ResultSet result = statement.executeQuery(
                             "SELECT balance FROM wallets " +
                             "WHERE wallet_type = 'SYSTEM'")) {
                    result.next();
                    assertEquals(-100L, result.getLong("balance"));
                }
            } finally {
                // Do not persist the test's change to the seeded bank wallet.
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
            statement.setString(2, "Constraint Test User");
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

    private static byte[] uuidToBytes(UUID uuid) {
        return ByteBuffer.allocate(16)
                .putLong(uuid.getMostSignificantBits())
                .putLong(uuid.getLeastSignificantBits())
                .array();
    }
}