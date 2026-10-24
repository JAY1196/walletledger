
package com.walletledger;

import com.walletledger.service.MySqlDeadlockRetryExecutor;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
class MySqlDeadlockRetryIntegrationTest {

    @Container
    static final MySQLContainer mysql = new MySQLContainer("mysql:8.4")
            .withDatabaseName("deadlock_test")
            .withUsername("test_owner")
            .withPassword("test_password")
            .withCommand("--log-bin-trust-function-creators=ON");

    @Test
    void retriesAfterRealMySqlDeadlock() throws Exception {
        createTestTable();

        MySqlDeadlockRetryExecutor retryExecutor =
                new MySqlDeadlockRetryExecutor();

        CountDownLatch bothTransactionsLockedFirstRow =
                new CountDownLatch(2);

        AtomicInteger deadlockErrors = new AtomicInteger();
        AtomicInteger attemptsA = new AtomicInteger();
        AtomicInteger attemptsB = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(2);

        try {
            Future<String> transactionA = pool.submit(() ->
                    retryExecutor.execute(() -> {
                        int attempt = attemptsA.incrementAndGet();

                        runTransactionAttempt(
                                1, 2, attempt == 1,
                                bothTransactionsLockedFirstRow,
                                deadlockErrors);

                        return "A succeeded";
                    }));

            Future<String> transactionB = pool.submit(() ->
                    retryExecutor.execute(() -> {
                        int attempt = attemptsB.incrementAndGet();

                        runTransactionAttempt(
                                2, 1, attempt == 1,
                                bothTransactionsLockedFirstRow,
                                deadlockErrors);

                        return "B succeeded";
                    }));

            assertEquals(
                    "A succeeded",
                    transactionA.get(25, TimeUnit.SECONDS));

            assertEquals(
                    "B succeeded",
                    transactionB.get(25, TimeUnit.SECONDS));

            assertTrue(
                    deadlockErrors.get() >= 1,
                    "MySQL should report at least one genuine error 1213");

            assertTrue(
                    attemptsA.get() + attemptsB.get() >= 3,
                    "At least one operation should have been retried");

            // Both successful transactions increment both rows.
            assertEquals(1, readValue(1));
            assertEquals(1, readValue(2));
        } finally {
            pool.shutdownNow();
        }
    }

    private static void createTestTable() throws SQLException {
        try (Connection connection = newConnection();
             Statement statement = connection.createStatement()) {

            statement.execute("""
                    CREATE TABLE IF NOT EXISTS deadlock_retry_test (
                        id INT PRIMARY KEY,
                        value INT NOT NULL
                    ) ENGINE=InnoDB
                    """);

            statement.executeUpdate(
                    "DELETE FROM deadlock_retry_test");

            statement.executeUpdate("""
                    INSERT INTO deadlock_retry_test (id, value)
                    VALUES (1, 0), (2, 0)
                    """);
        }
    }

    private static void runTransactionAttempt(
            int firstId,
            int secondId,
            boolean coordinateFirstAttempt,
            CountDownLatch bothLocked,
            AtomicInteger deadlockErrors) {

        try (Connection connection = newConnection()) {
            connection.setAutoCommit(false);

            try {
                // Lock the first row in this transaction's lock order.
                try (PreparedStatement statement =
                             connection.prepareStatement(
                                     "SELECT id FROM deadlock_retry_test "
                                             + "WHERE id = ? FOR UPDATE")) {
                    statement.setInt(1, firstId);

                    try (ResultSet result = statement.executeQuery()) {
                        if (!result.next()) {
                            throw new SQLException(
                                    "Expected test row " + firstId);
                        }
                    }
                }

                // On the first attempt, ensure both transactions hold
                // different rows before either requests the other row.
                if (coordinateFirstAttempt) {
                    bothLocked.countDown();

                    if (!bothLocked.await(10, TimeUnit.SECONDS)) {
                        throw new SQLException(
                                "Timed out coordinating deadlock test");
                    }
                }

                // The other transaction holds this row.
                try (PreparedStatement statement =
                             connection.prepareStatement(
                                     "UPDATE deadlock_retry_test "
                                             + "SET value = value + 1 "
                                             + "WHERE id = ?")) {
                    statement.setInt(1, secondId);
                    statement.executeUpdate();
                }

                connection.commit();
            } catch (SQLException | InterruptedException ex) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    ex.addSuppressed(rollbackFailure);
                }

                if (ex instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }

                if (containsDeadlockError(ex)) {
                    deadlockErrors.incrementAndGet();
                }

                throw new RuntimeException(
                        "Database transaction attempt failed", ex);
            }
        } catch (SQLException ex) {
            throw new RuntimeException(
                    "Could not open or close database connection", ex);
        }
    }

    private static boolean containsDeadlockError(Throwable failure) {
        for (Throwable current = failure;
             current != null;
             current = current.getCause()) {

            if (current instanceof SQLException sqlException
                    && sqlException.getErrorCode() == 1213) {
                return true;
            }
        }

        return false;
    }

    private static int readValue(int id) throws SQLException {
        try (Connection connection = newConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT value FROM deadlock_retry_test WHERE id = ?")) {

            statement.setInt(1, id);

            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("Missing test row " + id);
                }

                return result.getInt(1);
            }
        }
    }

    private static Connection newConnection() throws SQLException {
        return DriverManager.getConnection(
                mysql.getJdbcUrl(),
                mysql.getUsername(),
                mysql.getPassword());
    }
}
