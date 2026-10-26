package com.walletledger.repository;

import com.walletledger.entity.Transaction;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface TransactionRepository
        extends JpaRepository<Transaction, UUID> {

    Optional<Transaction> findByInitiatedByAndIdempotencyKey(
            UUID initiatedBy,
            String idempotencyKey
    );

    java.util.List<Transaction> findByOriginalTxnId(UUID originalTxnId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Transaction t WHERE t.id = :id")
    Optional<Transaction> findByIdForUpdate(@Param("id") UUID id);
}
