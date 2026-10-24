package com.walletledger.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.walletledger.entity.OutboxEvent;
import com.walletledger.entity.Transaction;
import com.walletledger.entity.TransactionAudit;
import com.walletledger.entity.TransactionStatus;
import com.walletledger.repository.OutboxEventRepository;
import com.walletledger.repository.TransactionAuditRepository;
import com.walletledger.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class TransactionLifecycleService {

    private final TransactionRepository transactionRepository;
    private final TransactionAuditRepository auditRepository;
    private final OutboxEventRepository outboxRepository;
    private final JsonMapper objectMapper;

    public TransactionLifecycleService(
            TransactionRepository transactionRepository,
            TransactionAuditRepository auditRepository,
            OutboxEventRepository outboxRepository) {
        this.transactionRepository = transactionRepository;
        this.auditRepository = auditRepository;
        this.outboxRepository = outboxRepository;
        this.objectMapper = JsonMapper.builder().build();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void complete(
            Transaction transaction,
            Short responseStatus,
            String responseBody,
            String reason) {

        Objects.requireNonNull(transaction, "Transaction is required");

        if (transaction.getStatus() != TransactionStatus.PENDING) {
            throw new IllegalStateException(
                    "Only a pending transaction can be completed");
        }

        String payload = buildCompletionPayload(transaction);
        TransactionStatus previousStatus = transaction.getStatus();

        transaction.complete(responseStatus, responseBody);

        transactionRepository.saveAndFlush(transaction);

        auditRepository.saveAndFlush(new TransactionAudit(
                transaction.getId(),
                previousStatus.name(),
                TransactionStatus.COMPLETED.name(),
                transaction.getInitiatedBy(),
                reason
        ));

        outboxRepository.saveAndFlush(new OutboxEvent(
                "TRANSACTION",
                transaction.getId(),
                "TRANSACTION_COMPLETED",
                payload
        ));
    }

    private String buildCompletionPayload(Transaction transaction) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("transaction_id", transaction.getId());
        payload.put("type", transaction.getType().name());
        payload.put("source_wallet_id", transaction.getSourceWalletId());
        payload.put("destination_wallet_id", transaction.getDestinationWalletId());
        payload.put("amount_paise", transaction.getAmount());
        payload.put("currency", "INR");
        payload.put("status", TransactionStatus.COMPLETED.name());
        payload.put("initiated_by", transaction.getInitiatedBy());
        payload.put("timestamp", Instant.now().toString());
        payload.put("event_version", 1);

        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(
                    "Could not serialize the transaction outbox event.", ex);
        }
    }
}