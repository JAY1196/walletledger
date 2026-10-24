package com.walletledger.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "wallets")
public class Wallet {

    @Id
    @Column(name = "id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID id;

    @Column(name = "user_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "wallet_type", nullable = false, length = 10)
    private WalletType walletType;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "INR";

    @Column(name = "balance", nullable = false)
    private long balance;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(
            name = "created_at",
            nullable = false,
            insertable = false,
            updatable = false
    )
    private Instant createdAt;

    @Column(
            name = "updated_at",
            nullable = false,
            insertable = false,
            updatable = false
    )
    private Instant updatedAt;

    @Column(
            name = "system_wallet_guard",
            insertable = false,
            updatable = false
    )
    private Byte systemWalletGuard;

    protected Wallet() {
        // Required by JPA.
    }

    public Wallet(UUID id, UUID userId, WalletType walletType) {
        this.id = id;
        this.userId = userId;
        this.walletType = walletType;
        this.currency = "INR";
        this.balance = 0L;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public WalletType getWalletType() {
        return walletType;
    }

    public String getCurrency() {
        return currency;
    }

    public long getBalance() {
        return balance;
    }

    public void debit(long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }

        if (walletType == WalletType.USER && balance < amount) {
            throw new IllegalStateException("Insufficient wallet balance");
        }

        balance = Math.subtractExact(balance, amount);
    }

    public void credit(long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }

        balance = Math.addExact(balance, amount);
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
