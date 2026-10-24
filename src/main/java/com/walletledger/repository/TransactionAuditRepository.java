package com.walletledger.repository;

import com.walletledger.entity.TransactionAudit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TransactionAuditRepository
        extends JpaRepository<TransactionAudit, Long> {

    List<TransactionAudit> findByTransactionIdOrderByIdAsc(UUID transactionId);
}
