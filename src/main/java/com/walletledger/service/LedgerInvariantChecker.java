package com.walletledger.service;

import com.walletledger.entity.LedgerEntry;
import com.walletledger.entity.LedgerEntryDirection;
import com.walletledger.entity.Transaction;
import com.walletledger.entity.TransactionStatus;
import com.walletledger.repository.LedgerEntryRepository;
import com.walletledger.repository.TransactionRepository;
import com.walletledger.repository.WalletRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigInteger;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Service
public class LedgerInvariantChecker {

    private final TransactionRepository transactionRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final WalletRepository walletRepository;

    public LedgerInvariantChecker(
            TransactionRepository transactionRepository,
            LedgerEntryRepository ledgerEntryRepository,
            WalletRepository walletRepository) {
        this.transactionRepository = transactionRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.walletRepository = walletRepository;
    }

    @Transactional(readOnly = true)
    public List<String> findViolations() {
        List<String> violations = new ArrayList<>();

        for (Transaction transaction : transactionRepository.findAll()) {
            TransactionStatus status = transaction.getStatus();

            if (status != TransactionStatus.COMPLETED
                    && status != TransactionStatus.REVERSED) {
                continue;
            }

            List<LedgerEntry> entries =
                    ledgerEntryRepository.findByTransactionIdOrderByIdAsc(
                            transaction.getId());

            if (entries.size() < 2) {
                violations.add(
                        transaction.getId()
                                + ": completed/reversed transaction has fewer than two ledger entries");
                continue;
            }

            BigInteger debits = BigInteger.ZERO;
            BigInteger credits = BigInteger.ZERO;

            for (LedgerEntry entry : entries) {
                BigInteger amount = BigInteger.valueOf(entry.getAmount());

                if (entry.getDirection() == LedgerEntryDirection.DEBIT) {
                    debits = debits.add(amount);
                } else if (entry.getDirection() == LedgerEntryDirection.CREDIT) {
                    credits = credits.add(amount);
                }
            }

            BigInteger expectedAmount =
                    BigInteger.valueOf(transaction.getAmount());

            if (!debits.equals(credits)) {
                violations.add(
                        transaction.getId()
                                + ": debit total does not equal credit total");
            }

            if (!debits.equals(expectedAmount)
                    || !credits.equals(expectedAmount)) {
                violations.add(
                        transaction.getId()
                                + ": ledger totals do not match transaction amount");
            }
        }

        return List.copyOf(violations);
    }

    /**
     * Reconciles cached wallet balances against their complete ledger history.
     * Credits increase balances; debits decrease balances.
     */
            @Transactional(readOnly = true)
    public List<String> findWalletBalanceViolations() {
        return List.copyOf(
                ledgerEntryRepository.findWalletBalanceMismatchDescriptions()
        );
    }

    @Transactional(readOnly = true)
    public List<String> findGlobalBalanceViolations() {
        BigDecimal total = walletRepository.sumAllWalletBalances();

        if (total == null || total.compareTo(BigDecimal.ZERO) != 0) {
            return List.of(
                    "Global wallet balance sum is not zero: "
                            + (total == null ? "null" : total.toPlainString())
            );
        }

        return List.of();
    }

    @Transactional(readOnly = true)
    public void assertNoViolations() {
        List<String> violations = new ArrayList<>();

        violations.addAll(findViolations());
        violations.addAll(findWalletBalanceViolations());
        violations.addAll(findGlobalBalanceViolations());

        if (!violations.isEmpty()) {
            throw new IllegalStateException(
                    "Ledger invariant violations: "
                            + String.join("; ", violations)
            );
        }
    }
}
