package com.walletledger.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "ledger_entries")
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(
            name = "transaction_id",
            nullable = false,
            columnDefinition = "BINARY(16)"
    )
    private UUID transactionId;

    @Column(
            name = "wallet_id",
            nullable = false,
            columnDefinition = "BINARY(16)"
    )
    private UUID walletId;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 6)
    private LedgerEntryDirection direction;

    @Column(name = "amount", nullable = false)
    private long amount;

    @Column(
            name = "created_at",
            nullable = false,
            insertable = false,
            updatable = false
    )
    private Instant createdAt;

    protected LedgerEntry() {
        // Required by JPA.
    }

    public LedgerEntry(
            UUID transactionId,
            UUID walletId,
            LedgerEntryDirection direction,
            long amount
    ) {
        this.transactionId = Objects.requireNonNull(
                transactionId, "Transaction ID is required");
        this.walletId = Objects.requireNonNull(
                walletId, "Wallet ID is required");
        this.direction = Objects.requireNonNull(
                direction, "Ledger direction is required");

        if (amount <= 0) {
            throw new IllegalArgumentException(
                    "Ledger amount must be positive");
        }

        this.amount = amount;
    }

    public Long getId() {
        return id;
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public UUID getWalletId() {
        return walletId;
    }

    public LedgerEntryDirection getDirection() {
        return direction;
    }

    public long getAmount() {
        return amount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}