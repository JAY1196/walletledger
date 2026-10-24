package com.walletledger.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.function.Supplier;

@Component
public class MySqlDeadlockRetryExecutor {

    private static final Logger log =
            LoggerFactory.getLogger(MySqlDeadlockRetryExecutor.class);

    private static final int MAX_ATTEMPTS = 3;
    private static final int MYSQL_DEADLOCK_ERROR_CODE = 1213;

    public <T> T execute(Supplier<T> operation) {
        if (operation == null) {
            throw new IllegalArgumentException("Operation must not be null.");
        }

        for (int attempt = 1; ; attempt++) {
            try {
                return operation.get();
            } catch (RuntimeException ex) {
                if (attempt >= MAX_ATTEMPTS || !isMySqlDeadlock(ex)) {
                    throw ex;
                }

                log.warn(
                        "MySQL deadlock detected; retrying operation "
                                + "(attempt {} of {}).",
                        attempt + 1,
                        MAX_ATTEMPTS);
            }
        }
    }

    private boolean isMySqlDeadlock(Throwable failure) {
        Deque<Throwable> pending = new ArrayDeque<>();
        Set<Throwable> visited =
                Collections.newSetFromMap(new IdentityHashMap<>());

        pending.add(failure);

        while (!pending.isEmpty()) {
            Throwable current = pending.removeFirst();

            if (!visited.add(current)) {
                continue;
            }

            if (current instanceof SQLException sqlException) {
                if (sqlException.getErrorCode() == MYSQL_DEADLOCK_ERROR_CODE) {
                    return true;
                }

                SQLException next = sqlException.getNextException();
                if (next != null) {
                    pending.addLast(next);
                }
            }

            Throwable cause = current.getCause();
            if (cause != null) {
                pending.addLast(cause);
            }
        }

        return false;
    }
}