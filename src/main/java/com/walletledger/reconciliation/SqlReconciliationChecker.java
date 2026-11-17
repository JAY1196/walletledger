package com.walletledger.reconciliation;

import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

@Component
public class SqlReconciliationChecker {

    private static final String TRANSACTION_LEDGER_SQL = """
            SELECT COUNT(*)
            FROM (
                SELECT
                    t.id,
                    t.amount,
                    COUNT(le.id) AS entry_count,
                    COALESCE(SUM(
                        CASE WHEN le.direction = 'DEBIT'
                             THEN le.amount ELSE 0 END
                    ), 0) AS debit_total,
                    COALESCE(SUM(
                        CASE WHEN le.direction = 'CREDIT'
                             THEN le.amount ELSE 0 END
                    ), 0) AS credit_total
                FROM transactions t
                LEFT JOIN ledger_entries le
                    ON le.transaction_id = t.id
                WHERE t.status IN ('COMPLETED', 'REVERSED')
                GROUP BY t.id, t.amount
                HAVING
                    entry_count < 2
                    OR debit_total <> credit_total
                    OR debit_total <> t.amount
            ) violations
            """;

    private static final String REVERSAL_SQL = """
            WITH bad_reversals AS (

                SELECT o.id
                FROM transactions o
                WHERE
                    (
                        o.status = 'COMPLETED'
                        AND EXISTS (
                            SELECT 1
                            FROM transactions r
                            WHERE r.original_txn_id = o.id
                              AND r.type = 'REVERSAL'
                              AND r.status = 'COMPLETED'
                        )
                    )
                    OR
                    (
                        o.status = 'REVERSED'
                        AND (
                            SELECT COUNT(*)
                            FROM transactions r
                            WHERE r.original_txn_id = o.id
                              AND r.type = 'REVERSAL'
                              AND r.status = 'COMPLETED'
                        ) <> 1
                    )

                UNION

                SELECT r.id
                FROM transactions r
                LEFT JOIN transactions o
                    ON o.id = r.original_txn_id
                WHERE r.type = 'REVERSAL'
                  AND r.status = 'COMPLETED'
                  AND (
                      o.id IS NULL
                      OR o.status <> 'REVERSED'
                      OR r.amount <> o.amount
                  )

                UNION

                SELECT r.id
                FROM transactions r
                WHERE r.type = 'REVERSAL'
                  AND r.status = 'COMPLETED'
                  AND EXISTS (
                      SELECT 1
                      FROM transactions child
                      WHERE child.original_txn_id = r.id
                        AND child.type = 'REVERSAL'
                        AND child.status = 'COMPLETED'
                  )

                UNION

                SELECT r.id
                FROM transactions r
                JOIN transactions o
                    ON o.id = r.original_txn_id
                WHERE r.type = 'REVERSAL'
                  AND r.status = 'COMPLETED'
                  AND (
                      EXISTS (
                          SELECT 1
                          FROM ledger_entries re
                          WHERE re.transaction_id = r.id
                            AND NOT EXISTS (
                                SELECT 1
                                FROM ledger_entries oe
                                WHERE oe.transaction_id = o.id
                                  AND oe.wallet_id = re.wallet_id
                                  AND oe.amount = re.amount
                                  AND oe.direction <> re.direction
                            )
                      )
                      OR
                      EXISTS (
                          SELECT 1
                          FROM ledger_entries oe
                          WHERE oe.transaction_id = o.id
                            AND NOT EXISTS (
                                SELECT 1
                                FROM ledger_entries re
                                WHERE re.transaction_id = r.id
                                  AND re.wallet_id = oe.wallet_id
                                  AND re.amount = oe.amount
                                  AND re.direction <> oe.direction
                            )
                      )
                  )
            )
            SELECT COUNT(*)
            FROM bad_reversals
            """;

    private static final String WALLET_BALANCE_SQL = """
            SELECT COUNT(*)
            FROM (
                SELECT
                    w.id,
                    w.balance,
                    COALESCE(SUM(
                        CASE
                            WHEN le.direction = 'CREDIT'
                                THEN le.amount
                            WHEN le.direction = 'DEBIT'
                                THEN -le.amount
                            ELSE 0
                        END
                    ), 0) AS ledger_balance
                FROM wallets w
                LEFT JOIN ledger_entries le
                    ON le.wallet_id = w.id
                GROUP BY w.id, w.balance
                HAVING w.balance <> ledger_balance
            ) violations
            """;

    private static final String NEGATIVE_USER_WALLET_SQL = """
            SELECT COUNT(*)
            FROM wallets
            WHERE wallet_type = 'USER'
              AND balance < 0
            """;

    private static final String GLOBAL_BALANCE_SQL = """
            SELECT CASE
                WHEN COALESCE(SUM(balance), 0) = 0 THEN 0
                ELSE 1
            END
            FROM wallets
            """;

    /**
     * Runs all reconciliation checks using the supplied JDBC connection.
     * The caller manages the connection and transaction lifecycle.
     */
    public ReconciliationViolationCounts check(Connection connection)
            throws SQLException {

        if (connection == null || connection.isClosed()) {
            throw new IllegalArgumentException(
                    "A valid JDBC connection is required");
        }

        long transactionLedgerViolations =
                queryCount(connection, TRANSACTION_LEDGER_SQL);

        long reversalViolations =
                queryCount(connection, REVERSAL_SQL);

        long walletBalanceViolations =
                queryCount(connection, WALLET_BALANCE_SQL);

        long negativeUserWalletViolations =
                queryCount(connection, NEGATIVE_USER_WALLET_SQL);

        long globalBalanceViolations =
                queryCount(connection, GLOBAL_BALANCE_SQL);

        return new ReconciliationViolationCounts(
                transactionLedgerViolations,
                reversalViolations,
                walletBalanceViolations,
                negativeUserWalletViolations,
                globalBalanceViolations
        );
    }

    private long queryCount(Connection connection, String sql)
            throws SQLException {

        try (PreparedStatement statement =
                     connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {

            if (!resultSet.next()) {
                throw new SQLException(
                        "Reconciliation query returned no result");
            }

            return resultSet.getLong(1);
        }
    }
}