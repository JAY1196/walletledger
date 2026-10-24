package com.walletledger;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Testcontainers
class DatabaseMigrationSmokeTest {

    @Container
    static final MySQLContainer mysql = createMySqlContainer();

    private static MySQLContainer createMySqlContainer() {
        MySQLContainer container = new MySQLContainer("mysql:8.4")
                .withDatabaseName("walletledger_test")
                .withUsername("test_owner")
                .withPassword("test_password");

        // Set this separately: withCommand returns a generic container type.
        container.withCommand("--log-bin-trust-function-creators=ON");

        return container;
    }

    @Test
    void appliesMigrationsAndSeedsSystemBank() throws SQLException {
        Flyway.configure()
                .dataSource(
                        mysql.getJdbcUrl(),
                        mysql.getUsername(),
                        mysql.getPassword()
                )
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (Connection connection = DriverManager.getConnection(
                mysql.getJdbcUrl(),
                mysql.getUsername(),
                mysql.getPassword())) {

            assertEquals(2, scalarInt(connection,
                    "SELECT COUNT(*) FROM flyway_schema_history " +
                    "WHERE success = TRUE AND version IN ('1', '2')"));

            assertEquals(6, scalarInt(connection,
                    "SELECT COUNT(*) FROM information_schema.tables " +
                    "WHERE table_schema = DATABASE() " +
                    "AND table_type = 'BASE TABLE' " +
                    "AND table_name <> 'flyway_schema_history'"));

            assertEquals(4, scalarInt(connection,
                    "SELECT COUNT(*) FROM information_schema.triggers " +
                    "WHERE trigger_schema = DATABASE()"));

            assertEquals(1, scalarInt(connection,
                    "SELECT COUNT(*) FROM users " +
                    "WHERE email = 'system-bank@walletledger.internal'"));

            assertEquals(1, scalarInt(connection,
                    "SELECT COUNT(*) FROM wallets " +
                    "WHERE wallet_type = 'SYSTEM' AND currency = 'INR'"));

            assertEquals(0L, scalarLong(connection,
                    "SELECT balance FROM wallets WHERE wallet_type = 'SYSTEM'"));
        }
    }

    private static int scalarInt(Connection connection, String sql)
            throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
        }
    }

    private static long scalarLong(Connection connection, String sql)
            throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }
}