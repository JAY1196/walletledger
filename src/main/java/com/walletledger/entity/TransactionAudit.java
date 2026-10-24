package com.walletledger.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "transaction_audit")
public class TransactionAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transaction_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID transactionId;

    @Column(name = "from_status", length = 16)
    private String fromStatus;

    @Column(name = "to_status", nullable = false, length = 16)
    private String toStatus;

    @Column(name = "changed_by", columnDefinition = "BINARY(16)")
    private UUID changedBy;

    @Column(name = "reason", length = 255)
    private String reason;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected TransactionAudit() {
    }

    public TransactionAudit(
            UUID transactionId,
            String fromStatus,
            String toStatus,
            UUID changedBy,
            String reason) {
        this.transactionId = transactionId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.changedBy = changedBy;
        this.reason = reason;
    }

    public Long getId() {
        return id;
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public String getFromStatus() {
        return fromStatus;
    }

    public String getToStatus() {
        return toStatus;
    }

    public UUID getChangedBy() {
        return changedBy;
    }

    public String getReason() {
        return reason;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
