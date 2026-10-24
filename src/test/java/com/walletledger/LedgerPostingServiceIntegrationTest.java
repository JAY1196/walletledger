package com.walletledger;

import com.walletledger.entity.LedgerEntry;
import com.walletledger.entity.LedgerEntryDirection;
import com.walletledger.entity.Transaction;
import com.walletledger.entity.TransactionType;
import com.walletledger.entity.User;
import com.walletledger.entity.Wallet;
import com.walletledger.entity.WalletType;
import com.walletledger.repository.LedgerEntryRepository;
import com.walletledger.repository.TransactionRepository;
import com.walletledger.repository.UserRepository;
import com.walletledger.repository.WalletRepository;
import com.walletledger.service.LedgerPostingService;
import com.walletledger.service.PostingLeg;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class LedgerPostingServiceIntegrationTest {

    @Autowired
    private LedgerPostingService ledgerPostingService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Test
    void balancedPostingUpdatesBalancesAndCreatesTwoLedgerEntries() {
        Fixture fixture = createFixture(500L, 200L);

        ledgerPostingService.post(fixture.transaction(), List.of(
                new PostingLeg(fixture.sourceWalletId(),
                        LedgerEntryDirection.DEBIT, 200L),
                new PostingLeg(fixture.destinationWalletId(),
                        LedgerEntryDirection.CREDIT, 200L)
        ));

        assertEquals(300L, balance(fixture.sourceWalletId()));
        assertEquals(200L, balance(fixture.destinationWalletId()));

        List<LedgerEntry> entries =
                ledgerEntryRepository.findByTransactionIdOrderByIdAsc(
                        fixture.transaction().getId());

        assertEquals(2, entries.size());
        assertBalanceChangesMatchLedger(fixture, 500L, 0L);
        assertEquals(200L, entries.get(0).getAmount());
        assertEquals(200L, entries.get(1).getAmount());
        assertEquals(
                entries.stream()
                        .filter(e -> e.getDirection() == LedgerEntryDirection.DEBIT)
                        .mapToLong(LedgerEntry::getAmount)
                        .sum(),
                entries.stream()
                        .filter(e -> e.getDirection() == LedgerEntryDirection.CREDIT)
                        .mapToLong(LedgerEntry::getAmount)
                        .sum()
        );
    }

    @Test
    void completedTransactionCannotBePostedAgain() {
        Fixture fixture = createFixture(500L, 200L);
        fixture.transaction().complete((short) 201, "{}");

        assertThrows(IllegalStateException.class, () ->
                ledgerPostingService.post(fixture.transaction(), List.of(
                        new PostingLeg(fixture.sourceWalletId(),
                                LedgerEntryDirection.DEBIT, 200L),
                        new PostingLeg(fixture.destinationWalletId(),
                                LedgerEntryDirection.CREDIT, 200L)
                ))
        );

        assertEquals(500L, balance(fixture.sourceWalletId()));
        assertEquals(0L, balance(fixture.destinationWalletId()));
        assertEquals(0, ledgerEntryRepository
                .findByTransactionIdOrderByIdAsc(fixture.transaction().getId())
                .size());
    }
    @Test
    void fewerThanTwoPostingLegsAreRejectedWithoutChangingBalancesOrLedger() {
        Fixture fixture = createFixture(500L, 200L);

        assertThrows(IllegalArgumentException.class, () ->
                ledgerPostingService.post(fixture.transaction(), List.of(
                        new PostingLeg(fixture.sourceWalletId(),
                                LedgerEntryDirection.DEBIT, 200L)
                ))
        );

        assertEquals(500L, balance(fixture.sourceWalletId()));
        assertEquals(0L, balance(fixture.destinationWalletId()));
        assertEquals(0, ledgerEntryRepository
                .findByTransactionIdOrderByIdAsc(fixture.transaction().getId())
                .size());
    }
    @Test
    void unbalancedPostingIsRejectedWithoutChangingBalancesOrLedger() {
        Fixture fixture = createFixture(500L, 100L);

        assertThrows(IllegalArgumentException.class, () ->
                ledgerPostingService.post(fixture.transaction(), List.of(
                        new PostingLeg(fixture.sourceWalletId(),
                                LedgerEntryDirection.DEBIT, 100L),
                        new PostingLeg(fixture.destinationWalletId(),
                                LedgerEntryDirection.CREDIT, 90L)
                ))
        );

        assertEquals(500L, balance(fixture.sourceWalletId()));
        assertEquals(0L, balance(fixture.destinationWalletId()));

        assertEquals(0, ledgerEntryRepository
                .findByTransactionIdOrderByIdAsc(fixture.transaction().getId())
                .size());
    }

    @Test
    void insufficientFundsAreRejectedWithoutChangingBalancesOrLedger() {
        Fixture fixture = createFixture(50L, 100L);

        assertThrows(IllegalStateException.class, () ->
                ledgerPostingService.post(fixture.transaction(), List.of(
                        new PostingLeg(fixture.sourceWalletId(),
                                LedgerEntryDirection.DEBIT, 100L),
                        new PostingLeg(fixture.destinationWalletId(),
                                LedgerEntryDirection.CREDIT, 100L)
                ))
        );

        assertEquals(50L, balance(fixture.sourceWalletId()));
        assertEquals(0L, balance(fixture.destinationWalletId()));

        assertEquals(0, ledgerEntryRepository
                .findByTransactionIdOrderByIdAsc(fixture.transaction().getId())
                .size());
    }
    @Test
    void creditOverflowIsRejectedWithoutChangingBalancesOrLedger() {
        Fixture fixture = createFixture(500L, 100L);

        Wallet destination = walletRepository.findById(
                fixture.destinationWalletId()).orElseThrow();
        destination.credit(Long.MAX_VALUE - 50L);
        walletRepository.saveAndFlush(destination);

        assertThrows(IllegalArgumentException.class, () ->
                ledgerPostingService.post(fixture.transaction(), List.of(
                        new PostingLeg(fixture.sourceWalletId(),
                                LedgerEntryDirection.DEBIT, 100L),
                        new PostingLeg(fixture.destinationWalletId(),
                                LedgerEntryDirection.CREDIT, 100L)
                ))
        );

        assertEquals(500L, balance(fixture.sourceWalletId()));
        assertEquals(Long.MAX_VALUE - 50L,
                balance(fixture.destinationWalletId()));

        assertEquals(0, ledgerEntryRepository
                .findByTransactionIdOrderByIdAsc(fixture.transaction().getId())
                .size());
    }
    @Test
    void duplicateWalletAndDirectionIsRejectedWithoutChangingBalancesOrLedger() {
        Fixture fixture = createFixture(500L, 200L);

        assertThrows(IllegalArgumentException.class, () ->
                ledgerPostingService.post(fixture.transaction(), List.of(
                        new PostingLeg(fixture.sourceWalletId(),
                                LedgerEntryDirection.DEBIT, 100L),
                        new PostingLeg(fixture.sourceWalletId(),
                                LedgerEntryDirection.DEBIT, 100L),
                        new PostingLeg(fixture.destinationWalletId(),
                                LedgerEntryDirection.CREDIT, 200L)
                ))
        );

        assertEquals(500L, balance(fixture.sourceWalletId()));
        assertEquals(0L, balance(fixture.destinationWalletId()));

        assertEquals(0, ledgerEntryRepository
                .findByTransactionIdOrderByIdAsc(fixture.transaction().getId())
                .size());
    }
    private Fixture createFixture(long sourceBalance, long transactionAmount) {
        String suffix = UUID.randomUUID().toString();

        UUID userId = UUID.randomUUID();
        UUID recipientId = UUID.randomUUID();
        UUID sourceWalletId = UUID.randomUUID();
        UUID destinationWalletId = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();

        userRepository.saveAndFlush(new User(
                userId, "Posting Test User", "posting-" + suffix + "@walletledger.test"));

        userRepository.saveAndFlush(new User(
                recipientId, "Posting Recipient",
                "posting-recipient-" + suffix + "@walletledger.test"));

        Wallet source = new Wallet(sourceWalletId, userId, WalletType.USER);
        source.credit(sourceBalance);

        walletRepository.saveAndFlush(source);
        walletRepository.saveAndFlush(new Wallet(
                destinationWalletId, recipientId, WalletType.USER));

        Transaction transaction = new Transaction(
                transactionId,
                TransactionType.TRANSFER,
                "posting-test-" + suffix,
                new byte[32],
                transactionAmount,
                userId,
                sourceWalletId,
                destinationWalletId,
                null
        );

        transactionRepository.saveAndFlush(transaction);

        return new Fixture(transaction, sourceWalletId, destinationWalletId);
    }

    private void assertBalanceChangesMatchLedger(
            Fixture fixture,
            long sourceBalanceBefore,
            long destinationBalanceBefore) {

        List<LedgerEntry> entries =
                ledgerEntryRepository.findByTransactionIdOrderByIdAsc(
                        fixture.transaction().getId());

        long sourceLedgerDelta = entries.stream()
                .filter(e -> e.getWalletId().equals(fixture.sourceWalletId()))
                .mapToLong(e -> e.getDirection() == LedgerEntryDirection.CREDIT
                        ? e.getAmount() : -e.getAmount())
                .sum();

        long destinationLedgerDelta = entries.stream()
                .filter(e -> e.getWalletId().equals(fixture.destinationWalletId()))
                .mapToLong(e -> e.getDirection() == LedgerEntryDirection.CREDIT
                        ? e.getAmount() : -e.getAmount())
                .sum();

        assertEquals(sourceBalanceBefore + sourceLedgerDelta,
                balance(fixture.sourceWalletId()),
                "Source wallet balance must match its ledger movement.");

        assertEquals(destinationBalanceBefore + destinationLedgerDelta,
                balance(fixture.destinationWalletId()),
                "Destination wallet balance must match its ledger movement.");
    }

    private long balance(UUID walletId) {
        return walletRepository.findById(walletId)
                .orElseThrow()
                .getBalance();
    }

    private record Fixture(
            Transaction transaction,
            UUID sourceWalletId,
            UUID destinationWalletId) {
    }
}