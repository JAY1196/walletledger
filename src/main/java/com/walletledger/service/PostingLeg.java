package com.walletledger.service;

import com.walletledger.entity.LedgerEntryDirection;

import java.util.Objects;
import java.util.UUID;

public record PostingLeg(UUID walletId, LedgerEntryDirection direction, long amountPaise) {

    public PostingLeg {
        Objects.requireNonNull(walletId, "Wallet ID is required");
        Objects.requireNonNull(direction, "Posting direction is required");

        if (amountPaise <= 0) {
            throw new IllegalArgumentException("Posting amount must be positive.");
        }
    }
}