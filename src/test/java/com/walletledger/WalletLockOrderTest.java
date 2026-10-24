package com.walletledger;

import com.walletledger.service.WalletLockOrder;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WalletLockOrderTest {

    @Test
    void sortsUuidBytesAsUnsignedLexicographicValues() {
        UUID zero = new UUID(0L, 0L);
        UUID highBitInMostSignificantHalf = new UUID(Long.MIN_VALUE, 0L);
        UUID highBitInLeastSignificantHalf = new UUID(0L, Long.MIN_VALUE);
        UUID maximum = new UUID(-1L, -1L);

        List<UUID> actual = List.of(
                maximum,
                highBitInLeastSignificantHalf,
                highBitInMostSignificantHalf,
                zero
        ).stream()
                .sorted(WalletLockOrder.COMPARATOR)
                .toList();

        assertEquals(
                List.of(
                        zero,
                        highBitInLeastSignificantHalf,
                        highBitInMostSignificantHalf,
                        maximum
                ),
                actual
        );
    }

    @Test
    void comparesMostSignificantBytesBeforeLeastSignificantBytes() {
        UUID lowerMostSignificant = new UUID(1L, -1L);
        UUID higherMostSignificant = new UUID(2L, 0L);

        assertEquals(
                true,
                WalletLockOrder.COMPARATOR.compare(
                        lowerMostSignificant, higherMostSignificant) < 0
        );
    }

    @Test
    void comparesLeastSignificantHalfAsUnsigned() {
        UUID lower = new UUID(0L, Long.MAX_VALUE);
        UUID higher = new UUID(0L, Long.MIN_VALUE);

        assertEquals(
                true,
                WalletLockOrder.COMPARATOR.compare(lower, higher) < 0
        );
    }
}
