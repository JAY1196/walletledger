
package com.walletledger;

import com.walletledger.reconciliation.ReconciliationProperties;
import com.walletledger.reconciliation.ReconciliationRunner;
import com.walletledger.reconciliation.SqlReconciliationChecker;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class SnapshotConsistencyNegativeControlTest {

    private static final String DATABASE =
            "walletledger_snapshot_test";

    private static final String USERNAME = "test_owner";
    private static final String PASSWORD = "test_password";

    private static final String LOCK_NAME =
            "reconciliation-snapshot-" + UUID.randomUUID();

    private static DriverManagerDataSource dataSource;

    @Container
    private static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>("mysql:8.4")
                    .withDatabaseName(DATABASE)
                    .withUsername(USERNAME)
                    .withPassword(PASSWORD)
                    .withCommand("--log-bin-trust-function-creators=ON");

    @BeforeAll
    static void migrateDatabase() {

        dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        dataSource.setUrl(MYSQL.getJdbcUrl());
        dataSource.setUsername(MYSQL.getUsername());
        dataSource.setPassword(MYSQL.getPassword());

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @Test
    void concurrentWalletUpdateDoesNotChangeReconciliationSnapshot()
            throws Exception {

        CountDownLatch snapshotEstablished =
                new CountDownLatch(1);

        CountDownLatch allowReconciliationToContinue =
                new CountDownLatch(1);

        AtomicBoolean firstCheckPaused = new AtomicBoolean(false);

        DataSource pausingDataSource =
                new SnapshotPausingDataSource(
                        dataSource,
                        snapshotEstablished,
                        allowReconciliationToContinue,
                        firstCheckPaused
                );

        ReconciliationRunner runner =
                new ReconciliationRunner(
                        pausingDataSource,
                        new SqlReconciliationChecker(),
                        new ReconciliationProperties(
                                "0 0 * * * *",
                                LOCK_NAME,
                                0
                        )
                );

        ExecutorService executor =
                Executors.newSingleThreadExecutor();

        Future<Boolean> future = null;
        boolean walletModified = false;

        try {

            // Start reconciliation on a separate thread.
            future = executor.submit(runner::runOnce);

            // Wait until the first checker SELECT has executed.
            assertTrue(
                    snapshotEstablished.await(15, TimeUnit.SECONDS),
                    "Reconciliation did not establish its snapshot in time"
            );

            // The first consistent read has now established the snapshot.
            // Modify the SYSTEM wallet through another connection.
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement =
                         connection.prepareStatement("""
                                 UPDATE wallets
                                 SET balance = balance + 1
                                 WHERE wallet_type = 'SYSTEM'
                                 """)) {

                int updated = statement.executeUpdate();

                assertEquals(
                        1,
                        updated,
                        "Expected exactly one SYSTEM wallet"
                );

                walletModified = true;
            }

            // Let reconciliation execute the remaining four checks.
            allowReconciliationToContinue.countDown();

            assertTrue(
                    future.get(30, TimeUnit.SECONDS),
                    "Reconciliation should complete successfully"
            );

            // All checks should still see the original snapshot.
            assertSuccessfulZeroViolationHistory();

        } finally {

            // Never leave the reconciliation thread waiting.
            allowReconciliationToContinue.countDown();

            if (future != null) {
                try {
                    future.get(30, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                    // Preserve the original test failure.
                }
            }

            // Restore the SYSTEM wallet after reconciliation has finished.
            if (walletModified) {
                try (Connection connection = dataSource.getConnection();
                     PreparedStatement statement =
                             connection.prepareStatement("""
                                     UPDATE wallets
                                     SET balance = balance - 1
                                     WHERE wallet_type = 'SYSTEM'
                                     """)) {

                    assertEquals(1, statement.executeUpdate());
                }
            }

            executor.shutdownNow();
        }
    }

    private static void assertSuccessfulZeroViolationHistory()
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
             PreparedStatement statement =
                     connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {

            assertTrue(
                    resultSet.next(),
                    "Expected a reconciliation history row"
            );

            assertEquals("SUCCESS", resultSet.getString("status"));

            assertEquals(
                    0,
                    resultSet.getLong(
                            "transaction_ledger_violation_count")
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
                    0,
                    resultSet.getLong(
                            "negative_user_wallet_violation_count")
            );

            assertEquals(
                    0,
                    resultSet.getLong("global_balance_violation_count")
            );

            assertEquals(
                    0,
                    resultSet.getLong("total_violation_count")
            );
        }
    }

    /**
     * Pauses after the first reconciliation COUNT query has executed.
     * This is the first consistent read and establishes the InnoDB
     * REPEATABLE READ snapshot.
     */
    private static class SnapshotPausingDataSource
            extends DelegatingDataSource {

        private final CountDownLatch snapshotEstablished;
        private final CountDownLatch allowContinue;
        private final AtomicBoolean firstCheckPaused;

        SnapshotPausingDataSource(
                DataSource target,
                CountDownLatch snapshotEstablished,
                CountDownLatch allowContinue,
                AtomicBoolean firstCheckPaused) {

            super(target);

            this.snapshotEstablished = snapshotEstablished;
            this.allowContinue = allowContinue;
            this.firstCheckPaused = firstCheckPaused;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return wrapConnection(super.getConnection());
        }

        private Connection wrapConnection(Connection delegate) {

            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {

                        Object result = invoke(delegate, method, args);

                        if ("prepareStatement".equals(method.getName())
                                && args != null
                                && args.length > 0
                                && args[0] instanceof String sql
                                && sql.stripLeading()
                                        .startsWith("SELECT COUNT(*)")) {

                            return wrapStatement((PreparedStatement) result);
                        }

                        return result;
                    }
            );
        }

        private PreparedStatement wrapStatement(
                PreparedStatement delegate) {

            return (PreparedStatement) Proxy.newProxyInstance(
                    PreparedStatement.class.getClassLoader(),
                    new Class<?>[]{PreparedStatement.class},
                    (proxy, method, args) -> {

                        Object result = invoke(delegate, method, args);

                        if ("executeQuery".equals(method.getName())
                                && (args == null || args.length == 0)
                                && firstCheckPaused.compareAndSet(
                                        false, true)) {

                            // The SQL query has executed successfully.
                            // Its consistent read has established the snapshot.
                            snapshotEstablished.countDown();

                            try {
                                if (!allowContinue.await(
                                        15, TimeUnit.SECONDS)) {

                                    throw new SQLException(
                                            "Timed out waiting to continue "
                                                    + "snapshot test"
                                    );
                                }
                            } catch (InterruptedException exception) {

                                Thread.currentThread().interrupt();

                                throw new SQLException(
                                        "Snapshot test was interrupted",
                                        exception
                                );
                            }
                        }

                        return result;
                    }
            );
        }

        private static Object invoke(
                Object target,
                java.lang.reflect.Method method,
                Object[] args) throws Throwable {

            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException exception) {
                throw exception.getCause();
            }
        }
    }
}
