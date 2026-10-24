package com.walletledger.service;

import java.util.Comparator;
import java.util.UUID;

/**
 * Provides canonical ordering for wallet row locks.
 * UUID bytes are compared lexicographically as unsigned bytes,
 * using the standard 16-byte representation.
 */
public final class WalletLockOrder {

    public static final Comparator<UUID> COMPARATOR = (left, right) -> {
        int mostSignificant = Long.compareUnsigned(
                left.getMostSignificantBits(),
                right.getMostSignificantBits());

        if (mostSignificant != 0) {
            return mostSignificant;
        }

        return Long.compareUnsigned(
                left.getLeastSignificantBits(),
                right.getLeastSignificantBits());
    };

    private WalletLockOrder() {
        throw new AssertionError("Utility class");
    }
}
