package com.walletledger;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.walletledger.entity.Transaction;
import com.walletledger.entity.TransactionStatus;
import com.walletledger.entity.TransactionType;
import com.walletledger.repository.TransactionRepository;
import com.walletledger.repository.LedgerEntryRepository;
import com.walletledger.repository.TransactionAuditRepository;
import com.walletledger.repository.OutboxEventRepository;
import com.walletledger.entity.OutboxEvent;



import com.walletledger.repository.WalletRepository;
import com.walletledger.service.JwtService;
import com.walletledger.service.LedgerInvariantChecker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;







import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Future;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class TransactionReversalIntegrationTest {

    private static final UUID SYSTEM_WALLET_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    private String bearerToken(Object userId) {
        UUID id = userId instanceof UUID uuid
                ? uuid
                : UUID.fromString(userId.toString());
        return "Bearer " + jwtService.generateToken(id);
    }

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private TransactionAuditRepository transactionAuditRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @MockitoSpyBean
    private OutboxEventRepository outboxEventSpy;




    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private LedgerInvariantChecker ledgerInvariantChecker;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private String registerUser() throws Exception {
        String email = "reversal-" + UUID.randomUUID() + "@walletledger.test";

        String response = mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Reversal Test User","email":"%s","password":"ValidPassword123!"}
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return jsonMapper.readTree(response).get("id").asText();
    }

    private String createWallet(String userId) throws Exception {
        String response = mockMvc.perform(post("/api/v1/wallets")
                        .header("Authorization", bearerToken(userId)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return jsonMapper.readTree(response).get("id").asText();
    }

    private String createTopUp(String userId, String walletId, long amount)
            throws Exception {
        String response = mockMvc.perform(post(
                        "/api/v1/wallets/{id}/topup", walletId)
                        .header("Authorization", bearerToken(userId))
                        .header("Idempotency-Key", "original-topup-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amountPaise": %d}
                                """.formatted(amount)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return jsonMapper.readTree(response).get("transactionId").asText();
    }

    private String reverse(String userId, String transactionId, String key)
            throws Exception {
        return mockMvc.perform(post(
                        "/api/v1/transactions/{id}/reverse", transactionId)
                        .header("Authorization", bearerToken(userId))
                        .header("Idempotency-Key", key))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private String createTransfer(
            String senderId,
            String sourceWalletId,
            String destinationWalletId,
            long amountPaise) throws Exception {

        String response = mockMvc.perform(post("/api/v1/transfers")
                        .header("Authorization", bearerToken(senderId))
                        .header("Idempotency-Key",
                                "transfer-test-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceWalletId": "%s",
                                  "destinationWalletId": "%s",
                                  "amountPaise": %d
                                }
                                """.formatted(
                                sourceWalletId,
                                destinationWalletId,
                                amountPaise)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return jsonMapper.readTree(response).get("transactionId").asText();
    }
    @Test
    void topUpCanBeReversedAndLedgerInvariantsRemainValid() throws Exception {
        String userId = registerUser();
        String walletId = createWallet(userId);
        String originalId = createTopUp(userId, walletId, 300);

        assertEquals(300L, walletRepository.findById(UUID.fromString(walletId))
                .orElseThrow().getBalance());

        String response = reverse(
                userId, originalId, "reverse-" + UUID.randomUUID());

        var json = jsonMapper.readTree(response);
        UUID reversalId = UUID.fromString(
                json.get("reversalTransactionId").asText());

        assertEquals(originalId, json.get("originalTransactionId").asText());
        assertEquals("COMPLETED", json.get("status").asText());
        assertEquals(300L, json.get("amountPaise").asLong());
        assertNotEquals(UUID.fromString(originalId), reversalId);

        Transaction original = transactionRepository.findById(
                UUID.fromString(originalId)).orElseThrow();
        Transaction reversal = transactionRepository.findById(reversalId)
                .orElseThrow();

        assertEquals(TransactionStatus.REVERSED, original.getStatus());
        assertEquals(TransactionType.REVERSAL, reversal.getType());
        assertEquals(TransactionStatus.COMPLETED, reversal.getStatus());
        assertEquals(UUID.fromString(originalId), reversal.getOriginalTxnId());
        assertEquals(300L, reversal.getAmount());

        assertEquals(0L, walletRepository.findById(UUID.fromString(walletId))
                .orElseThrow().getBalance());

        ledgerInvariantChecker.assertNoViolations();
    }

    @Test
    void retryWithSameIdempotencyKeyReturnsExactStoredResponse()
            throws Exception {
        String userId = registerUser();
        String walletId = createWallet(userId);
        String originalId = createTopUp(userId, walletId, 450);
        String key = "reverse-retry-" + UUID.randomUUID();

        String firstResponse = reverse(userId, originalId, key);
        String retryResponse = reverse(userId, originalId, key);

        assertEquals(firstResponse, retryResponse);

        long reversalCount = transactionRepository.findAll().stream()
                .filter(t -> t.getType() == TransactionType.REVERSAL)
                .filter(t -> originalId.equals(
                        t.getOriginalTxnId().toString()))
                .count();

        assertEquals(1L, reversalCount);
        assertEquals(0L, walletRepository.findById(UUID.fromString(walletId))
                .orElseThrow().getBalance());

        ledgerInvariantChecker.assertNoViolations();
    }

    @Test
    void differentUserCannotReverseAnotherUsersTransaction()
            throws Exception {
        String ownerId = registerUser();
        String walletId = createWallet(ownerId);
        String originalId = createTopUp(ownerId, walletId, 500);

        String otherUserId = registerUser();
        String key = "wrong-caller-" + UUID.randomUUID();

        mockMvc.perform(post(
                        "/api/v1/transactions/{id}/reverse", originalId)
                        .header("Authorization", bearerToken(otherUserId))
                        .header("Idempotency-Key", key))
                .andExpect(status().isForbidden());

        Transaction original = transactionRepository.findById(
                UUID.fromString(originalId)).orElseThrow();

        assertEquals(TransactionStatus.COMPLETED, original.getStatus());
        assertEquals(500L, walletRepository.findById(UUID.fromString(walletId))
                .orElseThrow().getBalance());

        ledgerInvariantChecker.assertNoViolations();
    }

    @Test
    void differentKeyCannotReverseTransactionTwice() throws Exception {
        String userId = registerUser();
        String walletId = createWallet(userId);
        String originalId = createTopUp(userId, walletId, 600);

        reverse(userId, originalId, "reverse-first-" + UUID.randomUUID());

        mockMvc.perform(post(
                        "/api/v1/transactions/{id}/reverse", originalId)
                        .header("Authorization", bearerToken(userId))
                        .header("Idempotency-Key",
                                "reverse-second-" + UUID.randomUUID()))
                .andExpect(status().isConflict());

        assertEquals(TransactionStatus.REVERSED,
                transactionRepository.findById(UUID.fromString(originalId))
                        .orElseThrow().getStatus());

        assertEquals(0L, walletRepository.findById(UUID.fromString(walletId))
                .orElseThrow().getBalance());

        ledgerInvariantChecker.assertNoViolations();
    }

    @Test
    void sameIdempotencyKeyCannotBeReusedForAnotherOriginalTransaction()
            throws Exception {
        String userId = registerUser();
        String walletId = createWallet(userId);

        String firstOriginalId = createTopUp(userId, walletId, 250);
        String secondOriginalId = createTopUp(userId, walletId, 350);
        String sharedKey = "shared-reversal-key-" + UUID.randomUUID();

        reverse(userId, firstOriginalId, sharedKey);

        mockMvc.perform(post(
                        "/api/v1/transactions/{id}/reverse", secondOriginalId)
                        .header("Authorization", bearerToken(userId))
                        .header("Idempotency-Key", sharedKey))
                .andExpect(status().isConflict());

        assertEquals(TransactionStatus.REVERSED,
                transactionRepository.findById(UUID.fromString(firstOriginalId))
                        .orElseThrow().getStatus());

        assertEquals(TransactionStatus.COMPLETED,
                transactionRepository.findById(UUID.fromString(secondOriginalId))
                        .orElseThrow().getStatus());

        assertEquals(350L, walletRepository.findById(UUID.fromString(walletId))
                .orElseThrow().getBalance());

        ledgerInvariantChecker.assertNoViolations();
    }

    @Test
    void reversalTransactionCannotItselfBeReversed() throws Exception {
        String userId = registerUser();
        String walletId = createWallet(userId);
        String originalId = createTopUp(userId, walletId, 700);

        String reversalResponse = reverse(
                userId, originalId, "reverse-original-" + UUID.randomUUID());

        String reversalId = jsonMapper.readTree(reversalResponse)
                .get("reversalTransactionId").asText();

        mockMvc.perform(post(
                        "/api/v1/transactions/{id}/reverse", reversalId)
                        .header("Authorization", bearerToken(userId))
                        .header("Idempotency-Key",
                                "reverse-the-reversal-" + UUID.randomUUID()))
                .andExpect(status().isConflict());

        assertEquals(TransactionStatus.REVERSED,
                transactionRepository.findById(UUID.fromString(originalId))
                        .orElseThrow().getStatus());

        assertEquals(TransactionStatus.COMPLETED,
                transactionRepository.findById(UUID.fromString(reversalId))
                        .orElseThrow().getStatus());

        assertEquals(0L, walletRepository.findById(UUID.fromString(walletId))
                .orElseThrow().getBalance());

        ledgerInvariantChecker.assertNoViolations();
    }

    @Test
    void failedReversalDueToInsufficientRecipientBalanceRollsBack()
            throws Exception {
        String senderId = registerUser();
        String recipientId = registerUser();
        String thirdUserId = registerUser();

        String senderWalletId = createWallet(senderId);
        String recipientWalletId = createWallet(recipientId);
        String thirdWalletId = createWallet(thirdUserId);

        // Sender starts with 500 paise and transfers all of it.
        createTopUp(senderId, senderWalletId, 500);

        String originalTransferId = createTransfer(
                senderId, senderWalletId, recipientWalletId, 500);

        // Recipient spends 300 paise, leaving only 200 paise.
        createTransfer(
                recipientId, recipientWalletId, thirdWalletId, 300);

        UUID originalId = UUID.fromString(originalTransferId);
        UUID recipientWalletUuid = UUID.fromString(recipientWalletId);
        UUID senderWalletUuid = UUID.fromString(senderWalletId);
        UUID thirdWalletUuid = UUID.fromString(thirdWalletId);

        assertEquals(0L, walletRepository.findById(senderWalletUuid)
                .orElseThrow().getBalance());
        assertEquals(200L, walletRepository.findById(recipientWalletUuid)
                .orElseThrow().getBalance());
        assertEquals(300L, walletRepository.findById(thirdWalletUuid)
                .orElseThrow().getBalance());

        long ledgerCountBeforeFailedReversal = ledgerEntryRepository.count();
        long auditCountBeforeFailedReversal = transactionAuditRepository.count();
        long outboxCountBeforeFailedReversal = outboxEventRepository.count();

        mockMvc.perform(post(
                        "/api/v1/transactions/{id}/reverse", originalTransferId)
                        .header("Authorization", bearerToken(senderId))
                        .header("Idempotency-Key",
                                "insufficient-reversal-" + UUID.randomUUID()))
                .andExpect(status().isUnprocessableEntity());

        // The original must remain completed after the failed attempt.
        assertEquals(TransactionStatus.COMPLETED,
                transactionRepository.findById(originalId)
                        .orElseThrow().getStatus());

        // No reversal transaction should have survived the rollback.
        long linkedReversals = transactionRepository.findAll().stream()
                .filter(t -> t.getType() == TransactionType.REVERSAL)
                .filter(t -> originalId.equals(t.getOriginalTxnId()))
                .count();

        assertEquals(0L, linkedReversals);

        // A failed reversal must not leave partial ledger, audit, or outbox writes.
        assertEquals(ledgerCountBeforeFailedReversal, ledgerEntryRepository.count(),
                "Failed reversal must not create ledger entries.");
        assertEquals(auditCountBeforeFailedReversal, transactionAuditRepository.count(),
                "Failed reversal must not create audit records.");
        assertEquals(outboxCountBeforeFailedReversal, outboxEventRepository.count(),
                "Failed reversal must not create outbox events.");

        // None of the three balances should have changed.
        assertEquals(0L, walletRepository.findById(senderWalletUuid)
                .orElseThrow().getBalance());
        assertEquals(200L, walletRepository.findById(recipientWalletUuid)
                .orElseThrow().getBalance());
        assertEquals(300L, walletRepository.findById(thirdWalletUuid)
                .orElseThrow().getBalance());

        ledgerInvariantChecker.assertNoViolations();
    }

    @Test
    void successfulWithdrawalReversalRestoresUserBalance() throws Exception {
        String userId = registerUser();
        String walletId = createWallet(userId);

        createTopUp(userId, walletId, 1000);

        String withdrawalKey = "withdraw-for-reversal-" + UUID.randomUUID();

        String withdrawalResponse = mockMvc.perform(post(
                        "/api/v1/wallets/{id}/withdraw", walletId)
                        .header("Authorization", bearerToken(userId))
                        .header("Idempotency-Key", withdrawalKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amountPaise": 300}
                                """))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String withdrawalId = jsonMapper.readTree(withdrawalResponse)
                .get("transactionId").asText();

        assertEquals(700L, walletRepository.findById(UUID.fromString(walletId))
                .orElseThrow().getBalance());

        String reversalResponse = reverse(
                userId,
                withdrawalId,
                "reverse-withdrawal-" + UUID.randomUUID());

        var responseJson = jsonMapper.readTree(reversalResponse);
        UUID reversalId = UUID.fromString(
                responseJson.get("reversalTransactionId").asText());

        assertEquals(withdrawalId,
                responseJson.get("originalTransactionId").asText());

        assertEquals(1000L, walletRepository.findById(UUID.fromString(walletId))
                .orElseThrow().getBalance());

        Transaction original = transactionRepository.findById(
                UUID.fromString(withdrawalId)).orElseThrow();

        Transaction reversal = transactionRepository.findById(reversalId)
                .orElseThrow();

        assertEquals(TransactionType.WITHDRAWAL, original.getType());
        assertEquals(TransactionStatus.REVERSED, original.getStatus());
        assertEquals(TransactionType.REVERSAL, reversal.getType());
        assertEquals(TransactionStatus.COMPLETED, reversal.getStatus());
        assertEquals(UUID.fromString(withdrawalId), reversal.getOriginalTxnId());

        ledgerInvariantChecker.assertNoViolations();
    }

    @Test
    void successfulTransferReversalRestoresBothWalletBalances()
            throws Exception {
        String senderId = registerUser();
        String recipientId = registerUser();

        String senderWalletId = createWallet(senderId);
        String recipientWalletId = createWallet(recipientId);

        createTopUp(senderId, senderWalletId, 1000);

        String originalTransferId = createTransfer(
                senderId, senderWalletId, recipientWalletId, 300);

        assertEquals(700L, walletRepository.findById(
                UUID.fromString(senderWalletId)).orElseThrow().getBalance());
        assertEquals(300L, walletRepository.findById(
                UUID.fromString(recipientWalletId)).orElseThrow().getBalance());

        String reversalResponse = reverse(
                senderId,
                originalTransferId,
                "reverse-transfer-" + UUID.randomUUID());

        var responseJson = jsonMapper.readTree(reversalResponse);
        UUID reversalId = UUID.fromString(
                responseJson.get("reversalTransactionId").asText());

        assertEquals(originalTransferId,
                responseJson.get("originalTransactionId").asText());

        assertEquals(1000L, walletRepository.findById(
                UUID.fromString(senderWalletId)).orElseThrow().getBalance());
        assertEquals(0L, walletRepository.findById(
                UUID.fromString(recipientWalletId)).orElseThrow().getBalance());

        Transaction original = transactionRepository.findById(
                UUID.fromString(originalTransferId)).orElseThrow();
        Transaction reversal = transactionRepository.findById(reversalId)
                .orElseThrow();

        assertEquals(TransactionType.TRANSFER, original.getType());
        assertEquals(TransactionStatus.REVERSED, original.getStatus());
        assertEquals(TransactionType.REVERSAL, reversal.getType());
        assertEquals(TransactionStatus.COMPLETED, reversal.getStatus());
        assertEquals(UUID.fromString(originalTransferId),
                reversal.getOriginalTxnId());

        ledgerInvariantChecker.assertNoViolations();
    }

    @Test
    void onlyOneOfTwentyConcurrentReversalsSucceeds() throws Exception {
        String userId = registerUser();
        String walletId = createWallet(userId);
        String originalId = createTopUp(userId, walletId, 1000);

        int requestCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(requestCount);

        List<Future<Integer>> results = new ArrayList<>();

        try {
            for (int i = 0; i < requestCount; i++) {
                final int requestNumber = i;

                results.add(executor.submit(() -> {
                    ready.countDown();

                    try {
                        if (!start.await(20, TimeUnit.SECONDS)) {
                            throw new IllegalStateException(
                                    "Timed out waiting for concurrency start");
                        }

                        return mockMvc.perform(post(
                                        "/api/v1/transactions/{id}/reverse",
                                        originalId)
                                        .header("Authorization", bearerToken(userId))
                                        .header("Idempotency-Key",
                                                "concurrent-reverse-"
                                                        + requestNumber + "-"
                                                        + UUID.randomUUID()))
                                .andReturn()
                                .getResponse()
                                .getStatus();
                    } finally {
                        finished.countDown();
                    }
                }));
            }

            assertTrue(ready.await(20, TimeUnit.SECONDS),
                    "All reversal requests should be ready before starting");

            start.countDown();

            assertTrue(finished.await(90, TimeUnit.SECONDS),
                    "All reversal requests should finish");

            int successCount = 0;
            int conflictCount = 0;

            for (Future<Integer> result : results) {
                int responseStatus = result.get(5, TimeUnit.SECONDS);

                if (responseStatus == 201) {
                    successCount++;
                } else if (responseStatus == 409) {
                    conflictCount++;
                } else {
                    throw new AssertionError(
                            "Unexpected reversal response status: "
                                    + responseStatus);
                }
            }

            assertEquals(1, successCount,
                    "Exactly one reversal request should succeed");
            assertEquals(requestCount - 1, conflictCount,
                    "All remaining requests should conflict");

            UUID originalUuid = UUID.fromString(originalId);

            assertEquals(TransactionStatus.REVERSED,
                    transactionRepository.findById(originalUuid)
                            .orElseThrow().getStatus());

            long successfulReversals = transactionRepository.findAll().stream()
                    .filter(t -> t.getType() == TransactionType.REVERSAL)
                    .filter(t -> originalUuid.equals(t.getOriginalTxnId()))
                    .filter(t -> t.getStatus() == TransactionStatus.COMPLETED)
                    .count();

            assertEquals(1L, successfulReversals);

            assertEquals(0L, walletRepository.findById(UUID.fromString(walletId))
                    .orElseThrow().getBalance());

            ledgerInvariantChecker.assertNoViolations();
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS),
                    "Concurrency executor should shut down");
        }
    }

    @Test
    void outboxFailureDuringReversalRollsBackEntireOperation()
            throws Exception {
        String userId = registerUser();
        String walletId = createWallet(userId);
        String originalId = createTopUp(userId, walletId, 800);

        UUID originalUuid = UUID.fromString(originalId);
        UUID walletUuid = UUID.fromString(walletId);

        long balanceBefore = walletRepository.findById(walletUuid)
                .orElseThrow().getBalance();
        long ledgerCountBefore = ledgerEntryRepository.count();
        long auditCountBefore = transactionAuditRepository.count();
        long outboxCountBefore = outboxEventRepository.count();
        long transactionCountBefore = transactionRepository.count();

        doThrow(new RuntimeException("Forced reversal outbox failure"))
                .when(outboxEventSpy)
                .saveAndFlush(any(OutboxEvent.class));

        try {
            mockMvc.perform(post(
                            "/api/v1/transactions/{id}/reverse", originalId)
                            .header("Authorization", bearerToken(userId))
                            .header("Idempotency-Key",
                                    "forced-reversal-failure-" + UUID.randomUUID()))
                    .andExpect(status().isInternalServerError());
        } finally {
            // Do not let the injected failure affect other tests.
            reset(outboxEventSpy);
        }

        assertEquals(TransactionStatus.COMPLETED,
                transactionRepository.findById(originalUuid)
                        .orElseThrow().getStatus(),
                "The original transaction must remain completed.");

        assertEquals(balanceBefore,
                walletRepository.findById(walletUuid).orElseThrow().getBalance(),
                "The wallet balance must roll back.");

        assertEquals(ledgerCountBefore, ledgerEntryRepository.count(),
                "Reversal ledger entries must roll back.");

        assertEquals(auditCountBefore, transactionAuditRepository.count(),
                "Reversal audit records must roll back.");

        assertEquals(outboxCountBefore, outboxEventRepository.count(),
                "The reversal outbox event must roll back.");

        assertEquals(transactionCountBefore, transactionRepository.count(),
                "The pending reversal transaction must roll back.");

        long linkedReversals = transactionRepository.findAll().stream()
                .filter(t -> t.getType() == TransactionType.REVERSAL)
                .filter(t -> originalUuid.equals(t.getOriginalTxnId()))
                .count();

        assertEquals(0L, linkedReversals,
                "No reversal transaction may survive the failure.");

        ledgerInvariantChecker.assertNoViolations();
    }
}
