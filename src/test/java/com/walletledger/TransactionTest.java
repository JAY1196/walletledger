package com.walletledger;

import com.walletledger.entity.Transaction;
import com.walletledger.entity.TransactionStatus;
import com.walletledger.entity.TransactionType;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TransactionTest {

    private Transaction newTransaction(
            TransactionType type,
            long amount,
            UUID originalTxnId
    ) {
        return new Transaction(
                UUID.randomUUID(),
                type,
                "test-key-001",
                new byte[32],
                amount,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                originalTxnId
        );
    }

    @Test
    void newTransactionStartsPending() {
        Transaction transaction =
                newTransaction(TransactionType.TRANSFER, 1000, null);

        assertEquals(TransactionStatus.PENDING, transaction.getStatus());
    }

    @Test
    void rejectsZeroAmount() {
        assertThrows(
                IllegalArgumentException.class,
                () -> newTransaction(TransactionType.TRANSFER, 0, null)
        );
    }

    @Test
    void rejectsNegativeAmount() {
        assertThrows(
                IllegalArgumentException.class,
                () -> newTransaction(TransactionType.TRANSFER, -100, null)
        );
    }

    @Test
    void rejectsInvalidRequestHashLength() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new Transaction(
                        UUID.randomUUID(),
                        TransactionType.TRANSFER,
                        "test-key",
                        new byte[31],
                        100,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        null
                )
        );
    }

    @Test
    void rejectsReversalWithoutOriginalTransaction() {
        assertThrows(
                IllegalArgumentException.class,
                () -> newTransaction(TransactionType.REVERSAL, 100, null)
        );
    }

    @Test
    void rejectsOriginalTransactionOnNonReversal() {
        assertThrows(
                IllegalArgumentException.class,
                () -> newTransaction(
                        TransactionType.TRANSFER, 100, UUID.randomUUID())
        );
    }

    @Test
    void rejectsSameSourceAndDestinationWallet() {
        UUID walletId = UUID.randomUUID();

        assertThrows(
                IllegalArgumentException.class,
                () -> new Transaction(
                        UUID.randomUUID(),
                        TransactionType.TRANSFER,
                        "test-key",
                        new byte[32],
                        100,
                        UUID.randomUUID(),
                        walletId,
                        walletId,
                        null
                )
        );
    }

    @Test
    void completedTransactionCannotBeCompletedAgain() {
        Transaction transaction =
                newTransaction(TransactionType.TRANSFER, 100, null);

        transaction.complete((short) 200, "{}");

        assertThrows(
                IllegalStateException.class,
                () -> transaction.complete((short) 200, "{}")
        );
    }

    @Test
    void completedTransactionCannotBeFailed() {
        Transaction transaction =
                newTransaction(TransactionType.TRANSFER, 100, null);

        transaction.complete((short) 200, "{}");

        assertThrows(
                IllegalStateException.class,
                () -> transaction.fail((short) 500, "{}")
        );
    }

    @Test
    void pendingTransactionCannotBeMarkedReversed() {
        Transaction transaction =
                newTransaction(TransactionType.TRANSFER, 100, null);

        assertThrows(
                IllegalStateException.class,
                () -> transaction.markReversed((short) 200, "{}")
        );
    }

    @Test
    void completedTransactionCanBeMarkedReversed() {
        Transaction transaction =
                newTransaction(TransactionType.TRANSFER, 100, null);

        transaction.complete((short) 200, "{}");
        transaction.markReversed((short) 200, "{}");

        assertEquals(TransactionStatus.REVERSED, transaction.getStatus());
    }
    @Test
    void reversedTransactionCannotBeCompletedAgain() {
        Transaction transaction =
                newTransaction(TransactionType.TRANSFER, 100, null);
        transaction.complete((short) 200, "{}");
        transaction.markReversed((short) 200, "{}");

        assertThrows(
                IllegalStateException.class,
                () -> transaction.complete((short) 200, "{}")
        );
    }

    @Test
    void reversedTransactionCannotBeFailed() {
        Transaction transaction =
                newTransaction(TransactionType.TRANSFER, 100, null);
        transaction.complete((short) 200, "{}");
        transaction.markReversed((short) 200, "{}");

        assertThrows(
                IllegalStateException.class,
                () -> transaction.fail((short) 500, "{}")
        );
    }

    @Test
    void reversedTransactionCannotBeReversedAgain() {
        Transaction transaction =
                newTransaction(TransactionType.TRANSFER, 100, null);
        transaction.complete((short) 200, "{}");
        transaction.markReversed((short) 200, "{}");

        assertThrows(
                IllegalStateException.class,
                () -> transaction.markReversed((short) 200, "{}")
        );
    }

    @Test
    void requestHashIsDefensivelyCopied() {
        byte[] hash = new byte[32];

        Transaction transaction = new Transaction(
                UUID.randomUUID(),
                TransactionType.TRANSFER,
                "test-key",
                hash,
                100,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                null
        );

        hash[0] = 42;

        assertEquals(0, transaction.getRequestHash()[0]);

        byte[] returnedHash = transaction.getRequestHash();
        returnedHash[0] = 99;

        assertEquals(0, transaction.getRequestHash()[0]);
    }
}