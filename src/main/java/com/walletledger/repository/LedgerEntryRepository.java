package com.walletledger.repository;

import com.walletledger.entity.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;

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