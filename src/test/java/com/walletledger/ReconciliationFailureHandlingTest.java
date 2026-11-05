
package com.walletledger;
import com.walletledger.reconciliation.ReconciliationViolationCounts;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class ReconciliationFailureHandlingTest {

    private static final String DATABASE = "walletledger_failure_test";
    private static final String USERNAME = "test_owner";
    private static final String PASSWORD = "test_password";

    private static final String LOCK_NAME =
            "reconciliation-failure-test-" + UUID.randomUUID();

    private static final String FAILURE_MESSAGE =
            "X".repeat(1500);

    @Container
    private static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>("mysql:8.4")
                    .withDatabaseName(DATABASE)
                    .withUsername(USERNAME)
                    .withPassword(PASSWORD)
                    .withCommand("--log-bin-trust-function-creators=ON");

    private static DriverManagerDataSource underlyingDataSource;
    private static RecordingDataSource recordingDataSource;
    private static ReconciliationRunner runner;

    @BeforeAll
    static void migrateAndConfigureRunner() {

        underlyingDataSource = new DriverManagerDataSource();
        underlyingDataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        underlyingDataSource.setUrl(MYSQL.getJdbcUrl());
        underlyingDataSource.setUsername(MYSQL.getUsername());
        underlyingDataSource.setPassword(MYSQL.getPassword());

        Flyway.configure()
                .dataSource(underlyingDataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        recordingDataSource =
                new RecordingDataSource(underlyingDataSource);

        SqlReconciliationChecker failingChecker =
                new SqlReconciliationChecker() {
                    @Override
                    public ReconciliationViolationCounts check(
                            Connection connection) throws SQLException {

                        throw new SQLException(FAILURE_MESSAGE);
                    }
                };

        runner = new ReconciliationRunner(
                recordingDataSource,
                failingChecker,
                new ReconciliationProperties(
                        "0 0 * * * *",
                        LOCK_NAME,
                        0
                )
        );
    }

    @Test
    void sqlFailureRecordsFailedRunAndRestoresConnectionAndLock()
            throws Exception {

        recordingDataSource.reset();

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> runner.runOnce()
        );

        assertEquals(
                "Reconciliation execution failed",
                exception.getMessage()
        );

        assertNotNull(exception.getCause());
        assertInstanceOf(SQLException.class, exception.getCause());

        // 1. Verify FAILED history and truncated error message.
        assertFailedHistory();

        // 2. Verify that the original connection settings were restored.
        ConnectionState initial =
                recordingDataSource.initialState.get();

        ConnectionState restored =
                recordingDataSource.closedState.get();

        assertNotNull(initial, "Initial connection state was not captured");
        assertNotNull(restored, "Final connection state was not captured");

        assertEquals(
                initial.isolation(),
                restored.isolation(),
                "Transaction isolation was not restored"
        );

        assertEquals(
                initial.readOnly(),
                restored.readOnly(),
                "Read-only setting was not restored"
        );

        assertEquals(
                initial.autoCommit(),
                restored.autoCommit(),
                "Auto-commit setting was not restored"
        );

        // 3. Verify that another connection can acquire the named lock.
        assertNamedLockIsAvailable();
    }

    private static void assertFailedHistory() throws Exception {

        String sql = """
                SELECT
                    status,
                    error_message,
                    CHAR_LENGTH(error_message) AS error_length
                FROM reconciliation_runs
                ORDER BY id DESC
                LIMIT 1
                """;

        try (Connection connection =
                     underlyingDataSource.getConnection();
             PreparedStatement statement =
                     connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {

            assertTrue(
                    resultSet.next(),
                    "Expected a reconciliation history row"
            );

            assertEquals(
                    "FAILED",
                    resultSet.getString("status")
            );

            assertEquals(
                    1000,
                    resultSet.getInt("error_length"),
                    "Error message must be truncated to 1000 characters"
            );

            assertEquals(
                    FAILURE_MESSAGE.substring(0, 1000),
                    resultSet.getString("error_message")
            );
        }
    }

    private static void assertNamedLockIsAvailable()
            throws Exception {

        try (Connection connection =
                     underlyingDataSource.getConnection()) {

            try (PreparedStatement statement =
                         connection.prepareStatement(
                                 "SELECT GET_LOCK(?, 0)")) {

                statement.setString(1, LOCK_NAME);

                try (ResultSet resultSet = statement.executeQuery()) {

                    assertTrue(resultSet.next());

                    assertEquals(
                            1,
                            resultSet.getInt(1),
                            "Named lock was not released after failure"
                    );
                }
            }

            try (PreparedStatement statement =
                         connection.prepareStatement(
                                 "SELECT RELEASE_LOCK(?)")) {

                statement.setString(1, LOCK_NAME);

                try (ResultSet resultSet = statement.executeQuery()) {

                    assertTrue(resultSet.next());

                    assertEquals(
                            1,
                            resultSet.getInt(1)
                    );
                }
            }
        }
    }

    private record ConnectionState(
            int isolation,
            boolean readOnly,
            boolean autoCommit) {
    }

    /**
     * Records the original connection settings and the settings
     * present immediately before the runner closes the connection.
     */
    private static class RecordingDataSource
            extends DelegatingDataSource {

        private final AtomicReference<ConnectionState> initialState =
                new AtomicReference<>();

        private final AtomicReference<ConnectionState> closedState =
                new AtomicReference<>();

        RecordingDataSource(DataSource targetDataSource) {
            super(targetDataSource);
        }

        void reset() {
            initialState.set(null);
            closedState.set(null);
        }

        @Override
        public Connection getConnection() throws SQLException {
            return recordConnection(super.getConnection());
        }

        @Override
        public Connection getConnection(
                String username,
                String password) throws SQLException {

            return recordConnection(
                    super.getConnection(username, password)
            );
        }

        private Connection recordConnection(Connection delegate)
                throws SQLException {

            initialState.set(readState(delegate));

            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {

                        if (method.getName().equals("close")
                                && method.getParameterCount() == 0) {

                            closedState.set(readState(delegate));
                        }

                        try {
                            return method.invoke(delegate, args);
                        } catch (InvocationTargetException exception) {
                            throw exception.getCause();
                        }
                    }
            );
        }

        private static ConnectionState readState(Connection connection)
                throws SQLException {

            return new ConnectionState(
                    connection.getTransactionIsolation(),
                    connection.isReadOnly(),
                    connection.getAutoCommit()
            );
        }
    }
}
