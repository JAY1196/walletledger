package com.walletledger.repository;

import com.walletledger.entity.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface LedgerEntryRepository
        extends JpaRepository<LedgerEntry, Long> {

    List<LedgerEntry> findByTransactionIdOrderByIdAsc(
            UUID transactionId
    );

    List<LedgerEntry> findByWalletIdOrderByIdAsc(
            UUID walletId
    );

    List<LedgerEntry> findAllByOrderByWalletIdAscIdAsc();

    /*
     * Read-only projection for the wallet statement API.
     * Native SQL converts BINARY(16) UUIDs to standard UUID strings.
     */
    interface StatementRow {
        Long getEntryId();
        String getTransactionId();
        String getType();
        String getStatus();
        String getDirection();
        Long getAmountPaise();
        String getCounterpartyWalletId();
        Instant getCreatedAt();
    }

    /*
     * One joined query:
     * - wallet-scoped
     * - newest first
     * - keyset pagination
     * - no per-entry transaction or wallet lookups
     */
    @Query(value = """
            SELECT
                le.id AS entryId,
                BIN_TO_UUID(t.id) AS transactionId,
                t.type AS type,
                t.status AS status,
                le.direction AS direction,
                le.amount AS amountPaise,
                CASE
                    WHEN le.direction = 'DEBIT' THEN
                        CASE
                            WHEN destination_wallet.wallet_type = 'SYSTEM'
                                THEN NULL
                            ELSE BIN_TO_UUID(t.destination_wallet_id)
                        END
                    WHEN le.direction = 'CREDIT' THEN
                        CASE
                            WHEN source_wallet.wallet_type = 'SYSTEM'
                                THEN NULL
                            ELSE BIN_TO_UUID(t.source_wallet_id)
                        END
                    ELSE NULL
                END AS counterpartyWalletId,
                le.created_at AS createdAt
            FROM ledger_entries le
            JOIN transactions t
                ON t.id = le.transaction_id
            JOIN wallets source_wallet
                ON source_wallet.id = t.source_wallet_id
            JOIN wallets destination_wallet
                ON destination_wallet.id = t.destination_wallet_id
            WHERE le.wallet_id = :walletId
              AND (:beforeId IS NULL OR le.id < :beforeId)
            ORDER BY le.id DESC
            LIMIT :fetchLimit
            """, nativeQuery = true)
    List<StatementRow> findStatementPage(
            @Param("walletId") UUID walletId,
            @Param("beforeId") Long beforeId,
            @Param("fetchLimit") int fetchLimit
    );

    @Query(value = """
            SELECT CONCAT(
                BIN_TO_UUID(w.id),
                ': cached balance does not match ledger-derived balance'
            )
            FROM wallets w
            LEFT JOIN ledger_entries le ON le.wallet_id = w.id
            GROUP BY w.id, w.balance
            HAVING CAST(w.balance AS DECIMAL(65,0)) <>
                COALESCE(
                    SUM(
                        CASE
                            WHEN le.direction = 'CREDIT'
                                THEN CAST(le.amount AS DECIMAL(65,0))
                            WHEN le.direction = 'DEBIT'
                                THEN -CAST(le.amount AS DECIMAL(65,0))
                            ELSE 0
                        END
                    ),
                    0
                )
            """, nativeQuery = true)
    List<String> findWalletBalanceMismatchDescriptions();
}