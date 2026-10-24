package com.walletledger.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "transactions")
public class Transaction {

    @Id
    @Column(name = "id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 16)
    private TransactionType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private TransactionStatus status;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, columnDefinition = "BINARY(32)")
    private byte[] requestHash;

    @Column(name = "amount", nullable = false)
    private long amount;

    @Column(name = "initiated_by", nullable = false, columnDefinition = "BINARY(16)")
    private UUID initiatedBy;

    @Column(name = "source_wallet_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID sourceWalletId;

    @Column(name = "destination_wallet_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID destinationWalletId;

    @Column(name = "original_txn_id", columnDefinition = "BINARY(16)")
    private UUID originalTxnId;

    @Column(name = "response_status")
    private Short responseStatus;

    @Column(name = "response_body", columnDefinition = "LONGTEXT")
    private String responseBody;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    protected Transaction() {
        // Required by JPA.
    }

    public Transaction(
            UUID id,
            TransactionType type,
            String idempotencyKey,
            byte[] requestHash,
            long amount,
            UUID initiatedBy,
            UUID sourceWalletId,
            UUID destinationWalletId,
            UUID originalTxnId
    ) {
        this.id = Objects.requireNonNull(id, "Transaction ID is required");
        this.type = Objects.requireNonNull(type, "Transaction type is required");
        this.idempotencyKey = Objects.requireNonNull(
                idempotencyKey, "Idempotency key is required");
        this.requestHash = Objects.requireNonNull(
                requestHash, "Request hash is required").clone();
        this.initiatedBy = Objects.requireNonNull(
                initiatedBy, "Initiating user ID is required");
        this.sourceWalletId = Objects.requireNonNull(
                sourceWalletId, "Source wallet ID is required");
        this.destinationWalletId = Objects.requireNonNull(
                destinationWalletId, "Destination wallet ID is required");

        if (idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw new IllegalArgumentException(
                    "Idempotency key must contain 1 to 128 non-blank characters");
        }

        if (requestHash.length != 32) {
            throw new IllegalArgumentException(
                    "Request hash must contain exactly 32 bytes");
        }

        if (amount <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }

        if (sourceWalletId.equals(destinationWalletId)) {
            throw new IllegalArgumentException(
                    "Source and destination wallets must differ");
        }

        if (type == TransactionType.REVERSAL && originalTxnId == null) {
            throw new IllegalArgumentException(
                    "A reversal must reference its original transaction");
        }

        if (type != TransactionType.REVERSAL && originalTxnId != null) {
            throw new IllegalArgumentException(
                    "Only reversal transactions may reference an original transaction");
        }

        this.amount = amount;
        this.originalTxnId = originalTxnId;
        this.status = TransactionStatus.PENDING;
    }

    public void complete(Short responseStatus, String responseBody) {
        requirePending();
        validateResponseStatus(responseStatus);
        this.status = TransactionStatus.COMPLETED;
        this.responseStatus = responseStatus;
        this.responseBody = responseBody;
    }

    public void fail(Short responseStatus, String responseBody) {
        requirePending();
        validateResponseStatus(responseStatus);
        this.status = TransactionStatus.FAILED;
        this.responseStatus = responseStatus;
        this.responseBody = responseBody;
    }

    public void markReversed(Short responseStatus, String responseBody) {
        if (status != TransactionStatus.COMPLETED) {
            throw new IllegalStateException(
                    "Only a completed transaction can be marked reversed");
        }

        validateResponseStatus(responseStatus);
        this.status = TransactionStatus.REVERSED;
        this.responseStatus = responseStatus;
        this.responseBody = responseBody;
    }

    private void requirePending() {
        if (status != TransactionStatus.PENDING) {
            throw new IllegalStateException(
                    "Only a pending transaction can be completed or failed");
        }
    }

    private static void validateResponseStatus(Short responseStatus) {
        if (responseStatus != null
                && (responseStatus < 100 || responseStatus > 599)) {
            throw new IllegalArgumentException(
                    "Response status must be between 100 and 599");
        }
    }

    public UUID getId() { return id; }
    public TransactionType getType() { return type; }
    public TransactionStatus getStatus() { return status; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public byte[] getRequestHash() {
        return requestHash == null ? null : requestHash.clone();
    }
    public long getAmount() { return amount; }
    public UUID getInitiatedBy() { return initiatedBy; }
    public UUID getSourceWalletId() { return sourceWalletId; }
    public UUID getDestinationWalletId() { return destinationWalletId; }
    public UUID getOriginalTxnId() { return originalTxnId; }
    public Short getResponseStatus() { return responseStatus; }
    public String getResponseBody() { return responseBody; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}