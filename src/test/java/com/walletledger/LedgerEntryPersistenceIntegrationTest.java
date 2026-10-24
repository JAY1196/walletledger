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

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class LedgerEntryPersistenceIntegrationTest {

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    @Transactional
    void savesAndReloadsDebitAndCreditEntriesWithGeneratedIds() {
        UUID userA = UUID.randomUUID();
        UUID userB = UUID.randomUUID();
        UUID walletA = UUID.randomUUID();
        UUID walletB = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();
        String suffix = UUID.randomUUID().toString();

        userRepository.saveAndFlush(new User(
                userA, "Ledger Test A", "ledger-a-" + suffix + "@walletledger.test"
        ));
        userRepository.saveAndFlush(new User(
                userB, "Ledger Test B", "ledger-b-" + suffix + "@walletledger.test"
        ));

        walletRepository.saveAndFlush(new Wallet(walletA, userA, WalletType.USER));
        walletRepository.saveAndFlush(new Wallet(walletB, userB, WalletType.USER));

        byte[] requestHash = new byte[32];

        transactionRepository.saveAndFlush(new Transaction(
                transactionId,
                TransactionType.TRANSFER,
                "ledger-test-" + suffix,
                requestHash,
                2500L,
                userA,
                walletA,
                walletB,
                null
        ));

        LedgerEntry debit = ledgerEntryRepository.saveAndFlush(
                new LedgerEntry(
                        transactionId, walletA, LedgerEntryDirection.DEBIT, 2500L
                )
        );

        LedgerEntry credit = ledgerEntryRepository.saveAndFlush(
                new LedgerEntry(
                        transactionId, walletB, LedgerEntryDirection.CREDIT, 2500L
                )
        );

        assertNotNull(debit.getId(), "MySQL should generate the debit entry ID");
        assertNotNull(credit.getId(), "MySQL should generate the credit entry ID");
        assertNotEquals(debit.getId(), credit.getId());

        entityManager.clear();

        List<LedgerEntry> transactionEntries =
                ledgerEntryRepository.findByTransactionIdOrderByIdAsc(transactionId);

        assertEquals(2, transactionEntries.size());

        LedgerEntry reloadedDebit = transactionEntries.stream()
                .filter(e -> e.getDirection() == LedgerEntryDirection.DEBIT)
                .findFirst()
                .orElseThrow();

        LedgerEntry reloadedCredit = transactionEntries.stream()
                .filter(e -> e.getDirection() == LedgerEntryDirection.CREDIT)
                .findFirst()
                .orElseThrow();

        assertAll(
                () -> assertEquals(walletA, reloadedDebit.getWalletId()),
                () -> assertEquals(walletB, reloadedCredit.getWalletId()),
                () -> assertEquals(2500L, reloadedDebit.getAmount()),
                () -> assertEquals(2500L, reloadedCredit.getAmount()),
                () -> assertNotNull(reloadedDebit.getCreatedAt()),
                () -> assertNotNull(reloadedCredit.getCreatedAt())
        );

        assertEquals(
                1,
                ledgerEntryRepository.findByWalletIdOrderByIdAsc(walletA).size()
        );
        assertEquals(
                1,
                ledgerEntryRepository.findByWalletIdOrderByIdAsc(walletB).size()
        );
    }

    @Test
    @Transactional
    void databaseRejectsLedgerEntryUpdatesAndDeletes() {
        UUID userA = UUID.randomUUID();
        UUID userB = UUID.randomUUID();
        UUID walletA = UUID.randomUUID();
        UUID walletB = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();
        String suffix = UUID.randomUUID().toString();

        userRepository.saveAndFlush(new User(
                userA, "Ledger Guard A", "ledger-guard-a-" + suffix + "@walletledger.test"
        ));
        userRepository.saveAndFlush(new User(
                userB, "Ledger Guard B", "ledger-guard-b-" + suffix + "@walletledger.test"
        ));

        walletRepository.saveAndFlush(new Wallet(walletA, userA, WalletType.USER));
        walletRepository.saveAndFlush(new Wallet(walletB, userB, WalletType.USER));

        transactionRepository.saveAndFlush(new Transaction(
                transactionId,
                TransactionType.TRANSFER,
                "ledger-guard-" + suffix,
                new byte[32],
                100L,
                userA,
                walletA,
                walletB,
                null
        ));

        LedgerEntry entry = ledgerEntryRepository.saveAndFlush(
                new LedgerEntry(
                        transactionId, walletA, LedgerEntryDirection.DEBIT, 100L
                )
        );

        Long entryId = entry.getId();
        assertNotNull(entryId);

        entityManager.flush();

        assertThrows(
                org.springframework.dao.DataAccessException.class,
                () -> jdbcTemplate.update(
                        "UPDATE ledger_entries SET amount = ? WHERE id = ?",
                        200L, entryId
                )
        );

        assertThrows(
                org.springframework.dao.DataAccessException.class,
                () -> jdbcTemplate.update(
                        "DELETE FROM ledger_entries WHERE id = ?",
                        entryId
                )
        );

        Integer remaining = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ledger_entries WHERE id = ?",
                Integer.class,
                entryId
        );

        assertEquals(1, remaining);
    }
}