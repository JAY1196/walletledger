package com.walletledger.service;

import com.walletledger.entity.LedgerEntry;
import com.walletledger.entity.LedgerEntryDirection;
import com.walletledger.entity.Transaction;
import com.walletledger.entity.TransactionStatus;
import com.walletledger.entity.Wallet;
import com.walletledger.entity.WalletType;
import com.walletledger.repository.LedgerEntryRepository;
import com.walletledger.repository.WalletRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class LedgerPostingService {

    private final WalletRepository walletRepository;
    private final LedgerEntryRepository ledgerEntryRepository;

    public LedgerPostingService(
            WalletRepository walletRepository,
            LedgerEntryRepository ledgerEntryRepository) {
        this.walletRepository = walletRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void post(Transaction transaction, List<PostingLeg> legs) {
        Objects.requireNonNull(transaction, "Transaction is required");
        Objects.requireNonNull(legs, "Posting legs are required");

        if (transaction.getStatus() != TransactionStatus.PENDING) {
            throw new IllegalStateException(
                    "Only pending transactions can be posted.");
        }

        if (legs.size() < 2) {
            throw new IllegalArgumentException(
                    "A balanced posting requires at least two legs.");
        }

        long totalDebits = 0L;
        long totalCredits = 0L;
        Set<String> uniqueEntries = new HashSet<>();
        Set<UUID> walletIds = new HashSet<>();

        for (PostingLeg leg : legs) {
            Objects.requireNonNull(leg, "Posting leg cannot be null");

            String entryKey = leg.walletId() + ":" + leg.direction();
            if (!uniqueEntries.add(entryKey)) {
                throw new IllegalArgumentException(
                        "Duplicate wallet and direction in posting legs.");
            }

            walletIds.add(leg.walletId());

            try {
                if (leg.direction() == LedgerEntryDirection.DEBIT) {
                    totalDebits = Math.addExact(totalDebits, leg.amountPaise());
                } else {
                    totalCredits = Math.addExact(totalCredits, leg.amountPaise());
                }
            } catch (ArithmeticException ex) {
                throw new IllegalArgumentException(
                        "Posting totals exceed the supported amount range.", ex);
            }
        }

        if (totalDebits != totalCredits) {
            throw new IllegalArgumentException(
                    "Total debits must equal total credits.");
        }

        if (totalDebits != transaction.getAmount()) {
            throw new IllegalArgumentException(
                    "Posting total must equal the transaction amount.");
        }

        List<UUID> orderedWalletIds = walletIds.stream()
                .sorted(WalletLockOrder.COMPARATOR)
                .toList();

        Map<UUID, Wallet> wallets = new LinkedHashMap<>();

        for (UUID walletId : orderedWalletIds) {
            Wallet wallet = walletRepository.findByIdForUpdate(walletId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Wallet not found: " + walletId));
            wallets.put(walletId, wallet);
        }

        Map<UUID, Long> projectedBalances = new HashMap<>();

        for (Map.Entry<UUID, Wallet> entry : wallets.entrySet()) {
            projectedBalances.put(entry.getKey(), entry.getValue().getBalance());
        }

        // Validate every balance change before mutating managed entities.
        for (PostingLeg leg : legs) {
            Wallet wallet = wallets.get(leg.walletId());

            if (wallet == null) {
                throw new IllegalArgumentException(
                        "Wallet not found: " + leg.walletId());
            }

            long currentBalance = projectedBalances.get(leg.walletId());
            long nextBalance;

            try {
                if (leg.direction() == LedgerEntryDirection.DEBIT) {
                    if (wallet.getWalletType() == WalletType.USER
                            && currentBalance < leg.amountPaise()) {
                        throw new IllegalStateException(
                                "Insufficient wallet balance.");
                    }

                    nextBalance = Math.subtractExact(
                            currentBalance, leg.amountPaise());
                } else {
                    nextBalance = Math.addExact(
                            currentBalance, leg.amountPaise());
                }
            } catch (ArithmeticException ex) {
                throw new IllegalArgumentException(
                        "Wallet balance would overflow.", ex);
            }

            projectedBalances.put(leg.walletId(), nextBalance);
        }

        // Apply changes only after the complete posting has passed validation.
        for (PostingLeg leg : legs) {
            Wallet wallet = wallets.get(leg.walletId());

            if (leg.direction() == LedgerEntryDirection.DEBIT) {
                wallet.debit(leg.amountPaise());
            } else {
                wallet.credit(leg.amountPaise());
            }
        }

        walletRepository.saveAllAndFlush(wallets.values());

        List<LedgerEntry> entries = new ArrayList<>(legs.size());

        for (PostingLeg leg : legs) {
            entries.add(new LedgerEntry(
                    transaction.getId(),
                    leg.walletId(),
                    leg.direction(),
                    leg.amountPaise()));
        }

        ledgerEntryRepository.saveAllAndFlush(entries);
    }
}
