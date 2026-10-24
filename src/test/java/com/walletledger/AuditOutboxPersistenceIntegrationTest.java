package com.walletledger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.walletledger.entity.OutboxEvent;
import com.walletledger.entity.Transaction;
import com.walletledger.entity.TransactionAudit;
import com.walletledger.entity.TransactionType;
import com.walletledger.entity.User;
import com.walletledger.entity.Wallet;
import com.walletledger.entity.WalletType;
import com.walletledger.repository.OutboxEventRepository;
import com.walletledger.repository.TransactionAuditRepository;
import com.walletledger.repository.TransactionRepository;
import com.walletledger.repository.UserRepository;
import com.walletledger.repository.WalletRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class AuditOutboxPersistenceIntegrationTest {

    @Autowired
    private TransactionAuditRepository auditRepository;

    @Autowired
    private OutboxEventRepository outboxRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WalletRepository walletRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    @Transactional
    void savesAndReloadsTransactionAudit() {
        UUID userId = UUID.randomUUID();
        UUID walletId = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();
        String suffix = UUID.randomUUID().toString();

        userRepository.saveAndFlush(new User(
                userId, "Audit Test User", "audit-" + suffix + "@walletledger.test"
        ));

        userRepository.saveAndFlush(new User(
                UUID.randomUUID(), "Audit Recipient",
                "audit-recipient-" + suffix + "@walletledger.test"
        ));

        UUID recipientId = userRepository
                .findAll()
                .stream()
                .filter(user -> !user.getId().equals(userId))
                .filter(user -> user.getEmail().equals(
                        "audit-recipient-" + suffix + "@walletledger.test"))
                .map(User::getId)
                .findFirst()
                .orElseThrow();

        UUID destinationWalletId = UUID.randomUUID();

        walletRepository.saveAndFlush(new Wallet(
                walletId, userId, WalletType.USER
        ));

        walletRepository.saveAndFlush(new Wallet(
                destinationWalletId, recipientId, WalletType.USER
        ));

        Transaction transaction = new Transaction(
                transactionId,
                TransactionType.TRANSFER,
                "audit-test-" + suffix,
                new byte[32],
                100L,
                userId,
                walletId,
                destinationWalletId,
                null
        );

        transactionRepository.saveAndFlush(transaction);

        auditRepository.saveAndFlush(new TransactionAudit(
                transactionId, "PENDING", "COMPLETED", userId, "Test completion"
        ));

        entityManager.clear();

        TransactionAudit reloaded = auditRepository
                .findByTransactionIdOrderByIdAsc(transactionId)
                .stream()
                .findFirst()
                .orElseThrow();

        assertAll(
                () -> assertNotNull(reloaded.getId()),
                () -> assertEquals(transactionId, reloaded.getTransactionId()),
                () -> assertEquals("PENDING", reloaded.getFromStatus()),
                () -> assertEquals("COMPLETED", reloaded.getToStatus()),
                () -> assertEquals(userId, reloaded.getChangedBy()),
                () -> assertEquals("Test completion", reloaded.getReason()),
                () -> assertNotNull(reloaded.getCreatedAt())
        );
    }

    @Test
    @Transactional
    void savesAndReloadsUnpublishedOutboxEventWithJsonPayload() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID recipientId = UUID.randomUUID();
        UUID sourceWalletId = UUID.randomUUID();
        UUID destinationWalletId = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();
        String suffix = UUID.randomUUID().toString();

        userRepository.saveAndFlush(new User(
                userId, "Outbox Test User", "outbox-" + suffix + "@walletledger.test"
        ));

        userRepository.saveAndFlush(new User(
                recipientId, "Outbox Recipient",
                "outbox-recipient-" + suffix + "@walletledger.test"
        ));

        walletRepository.saveAndFlush(new Wallet(
                sourceWalletId, userId, WalletType.USER
        ));

        walletRepository.saveAndFlush(new Wallet(
                destinationWalletId, recipientId, WalletType.USER
        ));

        transactionRepository.saveAndFlush(new Transaction(
                transactionId,
                TransactionType.TRANSFER,
                "outbox-test-" + suffix,
                new byte[32],
                250L,
                userId,
                sourceWalletId,
                destinationWalletId,
                null
        ));

        String payload =
                "{\"transactionId\":\"" + transactionId
                + "\",\"type\":\"TRANSFER\",\"amountPaise\":250}";

        OutboxEvent event = outboxRepository.saveAndFlush(new OutboxEvent(
                "TRANSACTION", transactionId, "TRANSACTION_COMPLETED", payload
        ));

        Long eventId = event.getId();

        entityManager.clear();

        OutboxEvent reloaded = outboxRepository.findById(eventId)
                .orElseThrow();

        JsonNode payloadJson = JsonMapper.builder().build()
                .readTree(reloaded.getPayload());

        assertAll(
                () -> assertEquals("TRANSACTION", reloaded.getAggregateType()),
                () -> assertEquals(transactionId, reloaded.getAggregateId()),
                () -> assertEquals("TRANSACTION_COMPLETED", reloaded.getEventType()),
                () -> assertNotNull(reloaded.getPayload()),
                () -> assertEquals(250, payloadJson.path("amountPaise").asInt()),
                () -> assertFalse(reloaded.isPublished()),
                () -> assertNotNull(reloaded.getCreatedAt()),
                () -> assertNull(reloaded.getPublishedAt())
        );

        assertEquals(1, outboxRepository
                .findByPublishedFalseOrderByIdAsc()
                .stream()
                .filter(item -> item.getId().equals(eventId))
                .count());
    }
}