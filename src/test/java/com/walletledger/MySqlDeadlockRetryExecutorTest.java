package com.walletledger;

import com.walletledger.service.MySqlDeadlockRetryExecutor;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MySqlDeadlockRetryExecutorTest {

    private final MySqlDeadlockRetryExecutor executor =
            new MySqlDeadlockRetryExecutor();

    @Test
    void retriesDeadlockAndReturnsSuccessfulResult() {
        AtomicInteger attempts = new AtomicInteger();

        String result = executor.execute(() -> {
            if (attempts.incrementAndGet() == 1) {
                throw deadlockException();
            }
            return "success";
        });

        assertEquals("success", result);
        assertEquals(2, attempts.get());
    }

    @Test
    void stopsAfterThreeAttemptsWhenDeadlockPersists() {
        AtomicInteger attempts = new AtomicInteger();
        RuntimeException failure = deadlockException();

        RuntimeException thrown = assertThrows(
                RuntimeException.class,
                () -> executor.execute(() -> {
                    attempts.incrementAndGet();
                    throw failure;
                }));

        assertSame(failure, thrown);
        assertEquals(3, attempts.get());
    }

    @Test
    void doesNotRetryLockWaitTimeout1205() {
        AtomicInteger attempts = new AtomicInteger();
        RuntimeException failure = new RuntimeException(
                new SQLException("Lock wait timeout", "HY000", 1205));

        RuntimeException thrown = assertThrows(
                RuntimeException.class,
                () -> executor.execute(() -> {
                    attempts.incrementAndGet();
                    throw failure;
                }));

        assertSame(failure, thrown);
        assertEquals(1, attempts.get());
    }

    @Test
    void doesNotRetryUnrelatedExceptions() {
        AtomicInteger attempts = new AtomicInteger();
        IllegalStateException failure =
                new IllegalStateException("Unexpected failure");

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> executor.execute(() -> {
                    attempts.incrementAndGet();
                    throw failure;
                }));

        assertSame(failure, thrown);
        assertEquals(1, attempts.get());
    }

    private RuntimeException deadlockException() {
        return new RuntimeException(
                new SQLException("Deadlock found", "40001", 1213));
    }
}