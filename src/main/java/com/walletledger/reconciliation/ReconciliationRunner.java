
package com.walletledger.reconciliation;

import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Service
public class ReconciliationRunner {

    private final DataSource dataSource;
    private final SqlReconciliationChecker checker;
    private final ReconciliationProperties properties;

    public ReconciliationRunner(
            DataSource dataSource,
            SqlReconciliationChecker checker,
            ReconciliationProperties properties) {

        this.dataSource = dataSource;
        this.checker = checker;
        this.properties = properties;
    }

    /**
     * Executes one reconciliation run.
     *
     * @return true if this instance acquired the lock and completed a run;
     *         false if another instance already holds the lock.
     */
    public boolean runOnce() {

        try (Connection connection = dataSource.getConnection()) {

            if (!acquireLock(connection)) {
                return false;
            }

            Throwable executionFailure = null;

            try {
                executeLockedRun(connection);
                return true;

            } catch (SQLException | RuntimeException | Error exception) {

                executionFailure = exception;
                throw exception;

            } finally {

                try {
                    releaseLock(connection);

                } catch (SQLException releaseException) {

                    if (executionFailure != null) {
                        executionFailure.addSuppressed(releaseException);
                    } else {
                        throw releaseException;
                    }
                }
            }

        } catch (SQLException exception) {

            throw new IllegalStateException(
                    "Reconciliation execution failed",
                    exception);
        }
    }

    private boolean acquireLock(Connection connection)
            throws SQLException {

        String sql = "SELECT GET_LOCK(?, ?)";

        try (PreparedStatement statement =
                     connection.prepareStatement(sql)) {

            statement.setString(1, properties.lockName());
            statement.setInt(2, properties.lockTimeoutSeconds());

            try (ResultSet resultSet = statement.executeQuery()) {

                return resultSet.next()
                        && resultSet.getInt(1) == 1
                        && !resultSet.wasNull();
            }
        }
    }

    private void releaseLock(Connection connection)
            throws SQLException {

        String sql = "SELECT RELEASE_LOCK(?)";

        try (PreparedStatement statement =
                     connection.prepareStatement(sql)) {

            statement.setString(1, properties.lockName());

            try (ResultSet resultSet = statement.executeQuery()) {

                if (!resultSet.next() || resultSet.getInt(1) != 1) {
                    throw new SQLException(
                            "Could not confirm reconciliation lock release");
                }
            }
        }
    }

    private void executeLockedRun(Connection connection)
            throws SQLException {

        LocalDateTime startedAt =
                LocalDateTime.now(ZoneOffset.UTC);

        long startedNanos = System.nanoTime();

        long runId = insertRunningHistory(connection, startedAt);

        try {

            ReconciliationViolationCounts counts =
                    executeConsistentSnapshot(connection);

            long durationMs =
                    (System.nanoTime() - startedNanos) / 1_000_000;

            String status = counts.hasViolations()
                    ? "VIOLATIONS_FOUND"
                    : "SUCCESS";

            updateHistory(
                    connection,
                    runId,
                    startedAt,
                    status,
                    counts,
                    durationMs,
                    null
            );

        } catch (Exception exception) {

            rollbackQuietly(connection);

            long durationMs =
                    (System.nanoTime() - startedNanos) / 1_000_000;

            String errorMessage = exception.getMessage();

            if (errorMessage != null && errorMessage.length() > 1000) {
                errorMessage = errorMessage.substring(0, 1000);
            }

            try {
                connection.setReadOnly(false);
                connection.setAutoCommit(true);

                updateHistory(
                        connection,
                        runId,
                        startedAt,
                        "FAILED",
                        null,
                        durationMs,
                        errorMessage
                );

            } catch (SQLException historyException) {
                exception.addSuppressed(historyException);
            }

            if (exception instanceof SQLException sqlException) {
                throw sqlException;
            }

            throw new SQLException(
                    "Reconciliation checks failed",
                    exception
            );
        }
    }

    /**
     * All five checks execute on the same connection and inside
     * one read-only REPEATABLE READ transaction.
     *
     * The original connection settings are restored before
     * the connection is returned to the connection pool.
     */
    private ReconciliationViolationCounts executeConsistentSnapshot(
            Connection connection) throws SQLException {

        int originalIsolation =
                connection.getTransactionIsolation();

        boolean originalReadOnly =
                connection.isReadOnly();

        boolean originalAutoCommit =
                connection.getAutoCommit();

        Throwable primaryFailure = null;

        try {

            connection.setTransactionIsolation(
                    Connection.TRANSACTION_REPEATABLE_READ);

            connection.setReadOnly(true);
            connection.setAutoCommit(false);

            try {

                ReconciliationViolationCounts counts =
                        checker.check(connection);

                connection.commit();

                return counts;

            } catch (SQLException | RuntimeException exception) {

                rollbackQuietly(connection);
                throw exception;
            }

        } catch (SQLException | RuntimeException exception) {

            primaryFailure = exception;
            throw exception;

        } finally {

            SQLException restorationFailure =
                    restoreConnectionState(
                            connection,
                            originalIsolation,
                            originalReadOnly,
                            originalAutoCommit
                    );

            if (restorationFailure != null) {

                if (primaryFailure != null) {
                    primaryFailure.addSuppressed(restorationFailure);
                } else {
                    throw restorationFailure;
                }
            }
        }
    }

    /**
     * Attempts to restore every original connection setting,
     * even if restoring one of them fails.
     */
    private SQLException restoreConnectionState(
            Connection connection,
            int originalIsolation,
            boolean originalReadOnly,
            boolean originalAutoCommit) {

        SQLException restorationFailure = null;

        try {
            connection.setReadOnly(originalReadOnly);
        } catch (SQLException exception) {
            restorationFailure = exception;
        }

        try {
            connection.setTransactionIsolation(originalIsolation);
        } catch (SQLException exception) {

            if (restorationFailure == null) {
                restorationFailure = exception;
            } else {
                restorationFailure.addSuppressed(exception);
            }
        }

        try {
            connection.setAutoCommit(originalAutoCommit);
        } catch (SQLException exception) {

            if (restorationFailure == null) {
                restorationFailure = exception;
            } else {
                restorationFailure.addSuppressed(exception);
            }
        }

        return restorationFailure;
    }

    private long insertRunningHistory(
            Connection connection,
            LocalDateTime startedAt) throws SQLException {

        String sql = """
                INSERT INTO reconciliation_runs (
                    started_at,
                    status
                )
                VALUES (?, 'RUNNING')
                """;

        try (PreparedStatement statement =
                     connection.prepareStatement(
                             sql,
                             Statement.RETURN_GENERATED_KEYS)) {

            statement.setTimestamp(
                    1,
                    Timestamp.valueOf(startedAt));

            statement.executeUpdate();

            try (ResultSet keys = statement.getGeneratedKeys()) {

                if (!keys.next()) {
                    throw new SQLException(
                            "Could not retrieve reconciliation run ID");
                }

                return keys.getLong(1);
            }
        }
    }

    private void updateHistory(
            Connection connection,
            long runId,
            LocalDateTime startedAt,
            String status,
            ReconciliationViolationCounts counts,
            long durationMs,
            String errorMessage) throws SQLException {

        String sql = """
                UPDATE reconciliation_runs
                SET
                    started_at = ?,
                    finished_at = CURRENT_TIMESTAMP(6),
                    status = ?,
                    transaction_ledger_violation_count = ?,
                    reversal_violation_count = ?,
                    wallet_balance_violation_count = ?,
                    negative_user_wallet_violation_count = ?,
                    global_balance_violation_count = ?,
                    total_violation_count = ?,
                    duration_ms = ?,
                    error_message = ?
                WHERE id = ?
                """;

        try (PreparedStatement statement =
                     connection.prepareStatement(sql)) {

            statement.setTimestamp(
                    1,
                    Timestamp.valueOf(startedAt));

            statement.setString(2, status);

            statement.setLong(
                    3,
                    counts == null ? 0
                            : counts.transactionLedgerViolations());

            statement.setLong(
                    4,
                    counts == null ? 0
                            : counts.reversalViolations());

            statement.setLong(
                    5,
                    counts == null ? 0
                            : counts.walletBalanceViolations());

            statement.setLong(
                    6,
                    counts == null ? 0
                            : counts.negativeUserWalletViolations());

            statement.setLong(
                    7,
                    counts == null ? 0
                            : counts.globalBalanceViolations());

            statement.setLong(
                    8,
                    counts == null ? 0
                            : counts.totalViolations());

            statement.setLong(9, durationMs);

            statement.setString(10, errorMessage);

            statement.setLong(11, runId);

            int updated = statement.executeUpdate();

            if (updated != 1) {
                throw new SQLException(
                        "Could not update reconciliation history");
            }
        }
    }

    private void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // Preserve the original exception.
        }
    }
}
