package com.walletledger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.walletledger.entity.Transaction;
import com.walletledger.entity.TransactionStatus;
import com.walletledger.entity.TransactionType;
import com.walletledger.entity.User;
import com.walletledger.entity.Wallet;
import com.walletledger.entity.WalletType;
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

import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class TransactionPersistenceIntegrationTest {

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WalletRepository walletRepository;

    @PersistenceContext
    private EntityManager entityManager;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    @Transactional
    void savesReloadsAndFindsTransactionByIdempotencyKey() throws Exception {
        UUID initiatorId = UUID.randomUUID();
        UUID destinationUserId = UUID.randomUUID();
        UUID sourceWalletId = UUID.randomUUID();
        UUID destinationWalletId = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();

        String uniqueSuffix = UUID.randomUUID().toString();

        userRepository.saveAndFlush(new User(
                initiatorId,
                "Transaction Test Initiator",
                "txn-initiator-" + uniqueSuffix + "@walletledger.test"
        ));

        userRepository.saveAndFlush(new User(
                destinationUserId,
                "Transaction Test Recipient",
                "txn-recipient-" + uniqueSuffix + "@walletledger.test"
        ));

        walletRepository.saveAndFlush(new Wallet(
                sourceWalletId, initiatorId, WalletType.USER
        ));

        walletRepository.saveAndFlush(new Wallet(
                destinationWalletId, destinationUserId, WalletType.USER
        ));

        byte[] requestHash = new byte[32];
        for (int i = 0; i < requestHash.length; i++) {
            requestHash[i] = (byte) (i + 1);
        }

        String idempotencyKey = "txn-test-" + uniqueSuffix;
        String responseBody =
                "{\"result\":\"ok\",\"amountPaise\":1250}";

        Transaction transaction = new Transaction(
                transactionId,
                TransactionType.TRANSFER,
                idempotencyKey,
                requestHash,
                1250L,
                initiatorId,
                sourceWalletId,
                destinationWalletId,
                null
        );

        transaction.complete((short) 201, responseBody);

        transactionRepository.saveAndFlush(transaction);

        // Reload database-generated timestamps and force a database read.
        entityManager.clear();

        Transaction reloaded = transactionRepository.findById(transactionId)
                .orElseThrow();

        assertAll(
                () -> assertEquals(transactionId, reloaded.getId()),
                () -> assertEquals(TransactionType.TRANSFER, reloaded.getType()),
                () -> assertEquals(TransactionStatus.COMPLETED, reloaded.getStatus()),
                () -> assertEquals(idempotencyKey, reloaded.getIdempotencyKey()),
                () -> assertEquals(1250L, reloaded.getAmount()),
                () -> assertEquals(initiatorId, reloaded.getInitiatedBy()),
                () -> assertEquals(sourceWalletId, reloaded.getSourceWalletId()),
                () -> assertEquals(destinationWalletId,
                        reloaded.getDestinationWalletId()),
                () -> assertNull(reloaded.getOriginalTxnId()),
                () -> assertEquals((short) 201, reloaded.getResponseStatus()),
                () -> assertNotNull(reloaded.getCreatedAt()),
                () -> assertNotNull(reloaded.getUpdatedAt()),
                () -> assertTrue(Arrays.equals(
                        requestHash, reloaded.getRequestHash()))
        );

        JsonNode actualJson = jsonMapper.readTree(reloaded.getResponseBody());
        JsonNode expectedJson = jsonMapper.readTree(responseBody);

        assertEquals(expectedJson, actualJson);

        Transaction idempotentMatch =
                transactionRepository
                        .findByInitiatedByAndIdempotencyKey(
                                initiatorId, idempotencyKey)
                        .orElseThrow();

        assertEquals(transactionId, idempotentMatch.getId());
        assertEquals(TransactionStatus.COMPLETED, idempotentMatch.getStatus());
    }
}