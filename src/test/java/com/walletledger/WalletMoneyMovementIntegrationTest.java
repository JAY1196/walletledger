package com.walletledger;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.walletledger.entity.LedgerEntry;
import com.walletledger.entity.LedgerEntryDirection;
import com.walletledger.entity.Wallet;
import com.walletledger.service.LedgerInvariantChecker;
import com.walletledger.repository.LedgerEntryRepository;
import com.walletledger.repository.OutboxEventRepository;
import com.walletledger.repository.TransactionAuditRepository;
import com.walletledger.repository.TransactionRepository;
import com.walletledger.repository.WalletRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class WalletMoneyMovementIntegrationTest {

    private static final UUID SYSTEM_WALLET_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LedgerInvariantChecker ledgerInvariantChecker;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private TransactionAuditRepository transactionAuditRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private String registerUser() throws Exception {
        String email = "money-movement-" + UUID.randomUUID()
                + "@walletledger.test";

        String response = mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Money Movement Test User","email":"%s"}
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return jsonMapper.readTree(response).get("id").asText();
    }

    private String createWallet(String userId) throws Exception {
        String response = mockMvc.perform(post("/api/v1/wallets")
                        .header("X-User-Id", userId))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return jsonMapper.readTree(response).get("id").asText();
    }

    private void fundWallet(String walletId, long amountPaise)
            throws Exception {
        Wallet wallet = walletRepository.findById(UUID.fromString(walletId))
                .orElseThrow();

        mockMvc.perform(post("/api/v1/wallets/{id}/topup", walletId)
                        .header("X-User-Id", wallet.getUserId())
                        .header("Idempotency-Key", "test-fund-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(movementBody(amountPaise)))
                .andExpect(status().isCreated());
    }

    private long balance(String walletId) {
        return walletRepository.findById(UUID.fromString(walletId))
                .orElseThrow()
                .getBalance();
    }

    private String movementBody(long amountPaise) {
        return """
                {"amountPaise": %d}
                """.formatted(amountPaise);
    }

    @Test
    void successfulTopUpCreditsUserAndRecordsBalancedLedgerEntries()
            throws Exception {
        String userId = registerUser();
        String walletId = createWallet(userId);
        String key = "topup-" + UUID.randomUUID();

        String response = mockMvc.perform(post(
                        "/api/v1/wallets/{id}/topup", walletId)
                        .header("X-User-Id", userId)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(movementBody(300)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("TOPUP"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.amountPaise").value(300))
                .andReturn()
                .getResponse()
                .getContentAsString();

        UUID transactionId = UUID.fromString(
                jsonMapper.readTree(response).get("transactionId").asText());

        assertEquals(300, balance(walletId));

        List<LedgerEntry> entries =
                ledgerEntryRepository.findByTransactionIdOrderByIdAsc(transactionId);

        assertEquals(2, entries.size());

        LedgerEntry debit = entries.stream()
                .filter(entry -> entry.getDirection() == LedgerEntryDirection.DEBIT)
                .findFirst()
                .orElseThrow();

        LedgerEntry credit = entries.stream()
                .filter(entry -> entry.getDirection() == LedgerEntryDirection.CREDIT)
                .findFirst()
                .orElseThrow();

        assertEquals(SYSTEM_WALLET_ID, debit.getWalletId());
        assertEquals(UUID.fromString(walletId), credit.getWalletId());
        assertEquals(300, debit.getAmount());
        assertEquals(300, credit.getAmount());

        var audits = transactionAuditRepository
                .findByTransactionIdOrderByIdAsc(transactionId);
        assertEquals(1, audits.size());
        assertEquals("PENDING", audits.get(0).getFromStatus());
        assertEquals("COMPLETED", audits.get(0).getToStatus());

        var events = outboxEventRepository.findByPublishedFalseOrderByIdAsc()
                .stream()
                .filter(event -> event.getAggregateId().equals(transactionId))
                .toList();

        assertEquals(1, events.size());
        assertEquals("TRANSACTION_COMPLETED", events.get(0).getEventType());
        assertEquals(transactionId, transactionRepository
                .findByInitiatedByAndIdempotencyKey(UUID.fromString(userId), key)
                .orElseThrow()
                .getId());
    }

    @Test
    void mixedTopUpAndWithdrawalPassFullInvariantCheck()
            throws Exception {
        String userId = registerUser();
        String walletId = createWallet(userId);

        // Top up the wallet with 1,000 paise.
        mockMvc.perform(post(
                        "/api/v1/wallets/{id}/topup", walletId)
                        .header("X-User-Id", userId)
                        .header("Idempotency-Key", "mixed-topup-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(movementBody(1000)))
                .andExpect(status().isCreated());

        assertEquals(1000, balance(walletId));

        // Withdraw 300 paise from the same wallet.
        mockMvc.perform(post(
                        "/api/v1/wallets/{id}/withdraw", walletId)
                        .header("X-User-Id", userId)
                        .header("Idempotency-Key", "mixed-withdraw-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(movementBody(300)))
                .andExpect(status().isCreated());

        assertEquals(700, balance(walletId));

        // Check transaction entries, cached wallet balances and global balance.
        ledgerInvariantChecker.assertNoViolations();
    }

    @Test
    void successfulWithdrawalDebitsUserAndRecordsBalancedLedgerEntries()
            throws Exception {
        String userId = registerUser();
        String walletId = createWallet(userId);
        fundWallet(walletId, 1000);
        String key = "withdraw-" + UUID.randomUUID();

        String response = mockMvc.perform(post(
                        "/api/v1/wallets/{id}/withdraw", walletId)
                        .header("X-User-Id", userId)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(movementBody(300)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("WITHDRAWAL"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.amountPaise").value(300))
                .andReturn()
                .getResponse()
                .getContentAsString();

        UUID transactionId = UUID.fromString(
                jsonMapper.readTree(response).get("transactionId").asText());

        assertEquals(700, balance(walletId));

        List<LedgerEntry> entries =
                ledgerEntryRepository.findByTransactionIdOrderByIdAsc(transactionId);

        assertEquals(2, entries.size());

        LedgerEntry debit = entries.stream()
                .filter(entry -> entry.getDirection() == LedgerEntryDirection.DEBIT)
                .findFirst()
                .orElseThrow();

        LedgerEntry credit = entries.stream()
                .filter(entry -> entry.getDirection() == LedgerEntryDirection.CREDIT)
                .findFirst()
                .orElseThrow();

        assertEquals(UUID.fromString(walletId), debit.getWalletId());
        assertEquals(SYSTEM_WALLET_ID, credit.getWalletId());
        assertEquals(300, debit.getAmount());
        assertEquals(300, credit.getAmount());

        var audits = transactionAuditRepository
                .findByTransactionIdOrderByIdAsc(transactionId);
        assertEquals(1, audits.size());
        assertEquals("PENDING", audits.get(0).getFromStatus());
        assertEquals("COMPLETED", audits.get(0).getToStatus());

        var events = outboxEventRepository.findByPublishedFalseOrderByIdAsc()
                .stream()
                .filter(event -> event.getAggregateId().equals(transactionId))
                .toList();

        assertEquals(1, events.size());
        assertEquals("TRANSACTION_COMPLETED", events.get(0).getEventType());
        assertTrue(!events.get(0).isPublished());
        assertEquals(transactionId, transactionRepository
                .findByInitiatedByAndIdempotencyKey(UUID.fromString(userId), key)
                .orElseThrow()
                .getId());
    }
    @Test
    void retryingSameTopUpWithSameIdempotencyKeyDoesNotCreditTwice()
            throws Exception {
        String userId = registerUser();
        String walletId = createWallet(userId);
        String key = "topup-retry-" + UUID.randomUUID();

        String firstResponse = mockMvc.perform(post(
                        "/api/v1/wallets/{id}/topup", walletId)
                        .header("X-User-Id", userId)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(movementBody(500)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String retryResponse = mockMvc.perform(post(
                        "/api/v1/wallets/{id}/topup", walletId)
                        .header("X-User-Id", userId)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(movementBody(500)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        UUID firstTransactionId = UUID.fromString(
                jsonMapper.readTree(firstResponse).get("transactionId").asText());
        UUID retryTransactionId = UUID.fromString(
                jsonMapper.readTree(retryResponse).get("transactionId").asText());

        assertEquals(firstTransactionId, retryTransactionId);
        assertEquals(500, balance(walletId));
        assertEquals(2, ledgerEntryRepository
                .findByTransactionIdOrderByIdAsc(firstTransactionId).size());
    }

    @Test
    void withdrawalWithInsufficientFundsDoesNotChangeBalance()
            throws Exception {
        String userId = registerUser();
        String walletId = createWallet(userId);
        fundWallet(walletId, 200);

        mockMvc.perform(post(
                        "/api/v1/wallets/{id}/withdraw", walletId)
                        .header("X-User-Id", userId)
                        .header("Idempotency-Key", "withdraw-insufficient-"
                                + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(movementBody(300)))
                .andExpect(status().isUnprocessableEntity());

        assertEquals(200, balance(walletId));
    }

    @Test
    void reusingTopUpKeyWithDifferentAmountReturnsConflict()
            throws Exception {
        String userId = registerUser();
        String walletId = createWallet(userId);
        String key = "topup-conflict-" + UUID.randomUUID();

        mockMvc.perform(post("/api/v1/wallets/{id}/topup", walletId)
                        .header("X-User-Id", userId)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(movementBody(500)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/wallets/{id}/topup", walletId)
                        .header("X-User-Id", userId)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(movementBody(700)))
                .andExpect(status().isConflict());

        assertEquals(500, balance(walletId));
    }

    @Test
    void reusingTopUpKeyForWithdrawalReturnsConflict()
            throws Exception {
        String userId = registerUser();
        String walletId = createWallet(userId);
        String key = "cross-operation-" + UUID.randomUUID();

        mockMvc.perform(post("/api/v1/wallets/{id}/topup", walletId)
                        .header("X-User-Id", userId)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(movementBody(500)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/wallets/{id}/withdraw", walletId)
                        .header("X-User-Id", userId)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(movementBody(500)))
                .andExpect(status().isConflict());

        assertEquals(500, balance(walletId));
    }

    @Test
    void simultaneousTopUpsWithSameIdempotencyKeyCreditOnlyOnce()
            throws Exception {
        String userId = registerUser();
        String walletId = createWallet(userId);
        String key = "concurrent-topup-" + UUID.randomUUID();
        String body = movementBody(500);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            java.util.concurrent.Callable<MvcResult> request = () -> {
                ready.countDown();

                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException(
                            "Timed out waiting to start concurrent top-up.");
                }

                return mockMvc.perform(post("/api/v1/wallets/{id}/topup", walletId)
                                .header("X-User-Id", userId)
                                .header("Idempotency-Key", key)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                        .andReturn();
            };

            Future<MvcResult> firstFuture = executor.submit(request);
            Future<MvcResult> secondFuture = executor.submit(request);

            assertTrue(ready.await(10, TimeUnit.SECONDS),
                    "Both top-up requests should be ready.");

            start.countDown();

            MvcResult first = firstFuture.get(30, TimeUnit.SECONDS);
            MvcResult second = secondFuture.get(30, TimeUnit.SECONDS);

            assertEquals(201, first.getResponse().getStatus());
            assertEquals(201, second.getResponse().getStatus());

            String firstTransactionId = jsonMapper.readTree(
                    first.getResponse().getContentAsString())
                    .get("transactionId").asText();

            String secondTransactionId = jsonMapper.readTree(
                    second.getResponse().getContentAsString())
                    .get("transactionId").asText();

            assertEquals(firstTransactionId, secondTransactionId);
            assertEquals(500, balance(walletId));

            UUID transactionId = UUID.fromString(firstTransactionId);
            assertEquals(2, ledgerEntryRepository
                    .findByTransactionIdOrderByIdAsc(transactionId).size());

            assertEquals(transactionId, transactionRepository
                    .findByInitiatedByAndIdempotencyKey(
                            UUID.fromString(userId), key)
                    .orElseThrow()
                    .getId());
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
        ledgerInvariantChecker.assertNoViolations();
    }

    @Test
    void simultaneousWithdrawalsCannotOverspendWallet()
            throws Exception {
        String userId = registerUser();
        String walletId = createWallet(userId);
        fundWallet(walletId, 1000);

        String keyOne = "concurrent-withdraw-one-" + UUID.randomUUID();
        String keyTwo = "concurrent-withdraw-two-" + UUID.randomUUID();
        String body = movementBody(700);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            java.util.concurrent.Callable<MvcResult> requestOne = () -> {
                ready.countDown();

                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException(
                            "Timed out waiting to start first withdrawal.");
                }

                return mockMvc.perform(post("/api/v1/wallets/{id}/withdraw", walletId)
                                .header("X-User-Id", userId)
                                .header("Idempotency-Key", keyOne)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                        .andReturn();
            };

            java.util.concurrent.Callable<MvcResult> requestTwo = () -> {
                ready.countDown();

                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException(
                            "Timed out waiting to start second withdrawal.");
                }

                return mockMvc.perform(post("/api/v1/wallets/{id}/withdraw", walletId)
                                .header("X-User-Id", userId)
                                .header("Idempotency-Key", keyTwo)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                        .andReturn();
            };

            Future<MvcResult> futureOne = executor.submit(requestOne);
            Future<MvcResult> futureTwo = executor.submit(requestTwo);

            assertTrue(ready.await(10, TimeUnit.SECONDS),
                    "Both withdrawal requests should be ready.");

            start.countDown();

            MvcResult resultOne = futureOne.get(30, TimeUnit.SECONDS);
            MvcResult resultTwo = futureTwo.get(30, TimeUnit.SECONDS);

            int statusOne = resultOne.getResponse().getStatus();
            int statusTwo = resultTwo.getResponse().getStatus();

            assertEquals(1,
                    java.util.stream.Stream.of(statusOne, statusTwo)
                            .filter(status -> status == 201)
                            .count(),
                    "Exactly one withdrawal should succeed.");

            assertEquals(1,
                    java.util.stream.Stream.of(statusOne, statusTwo)
                            .filter(status -> status == 422)
                            .count(),
                    "The other withdrawal should fail for insufficient funds.");

            assertEquals(300, balance(walletId));

            String successfulWithdrawalBody =
                    statusOne == 201 ? resultOne.getResponse().getContentAsString()
                            : resultTwo.getResponse().getContentAsString();

            UUID successfulWithdrawalId = UUID.fromString(
                    jsonMapper.readTree(successfulWithdrawalBody)
                            .get("transactionId").asText());

            assertEquals(2, ledgerEntryRepository
                    .findByTransactionIdOrderByIdAsc(successfulWithdrawalId).size(),
                    "The successful withdrawal should have balanced ledger entries.");
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
        ledgerInvariantChecker.assertNoViolations();
    }

    @Test
    void topUpOverflowRollsBackAllFinancialRecords() throws Exception {
        String userId = registerUser();
        String walletId = createWallet(userId);
        UUID userUuid = UUID.fromString(userId);
        UUID walletUuid = UUID.fromString(walletId);
        String key = "overflow-topup-" + UUID.randomUUID();

        // Establish a temporary near-maximum balance for this overflow test.
        // It is deliberately not treated as a valid ledger-funded balance.
        long overflowBalance = Long.MAX_VALUE - 50L;
        Wallet overflowFixture = walletRepository.findById(walletUuid).orElseThrow();
        overflowFixture.credit(overflowBalance);
        walletRepository.saveAndFlush(overflowFixture);

        try {
            long userBalanceBefore = balance(walletId);
            long systemBalanceBefore = balance(SYSTEM_WALLET_ID.toString());

            int userLedgerEntriesBefore = ledgerEntryRepository
                    .findByWalletIdOrderByIdAsc(walletUuid).size();
            int systemLedgerEntriesBefore = ledgerEntryRepository
                    .findByWalletIdOrderByIdAsc(SYSTEM_WALLET_ID).size();
            int transactionsBefore = transactionRepository.findAll().size();
            int auditsBefore = transactionAuditRepository.findAll().size();
            int outboxEventsBefore = outboxEventRepository.findAll().size();

            mockMvc.perform(post("/api/v1/wallets/{id}/topup", walletId)
                            .header("X-User-Id", userId)
                            .header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(movementBody(100)))
                    .andExpect(status().isUnprocessableEntity());

            assertEquals(userBalanceBefore, balance(walletId));
            assertEquals(systemBalanceBefore, balance(SYSTEM_WALLET_ID.toString()));

            assertEquals(userLedgerEntriesBefore, ledgerEntryRepository
                    .findByWalletIdOrderByIdAsc(walletUuid).size());
            assertEquals(systemLedgerEntriesBefore, ledgerEntryRepository
                    .findByWalletIdOrderByIdAsc(SYSTEM_WALLET_ID).size());

            assertEquals(transactionsBefore, transactionRepository.findAll().size());
            assertEquals(auditsBefore, transactionAuditRepository.findAll().size());
            assertEquals(outboxEventsBefore, outboxEventRepository.findAll().size());

            assertTrue(transactionRepository
                    .findByInitiatedByAndIdempotencyKey(userUuid, key)
                    .isEmpty());
        } finally {
            Wallet overflowWallet = walletRepository.findById(walletUuid).orElseThrow();

            if (overflowWallet.getBalance() > 0) {
                overflowWallet.debit(overflowWallet.getBalance());
            }

            walletRepository.saveAndFlush(overflowWallet);
        }

        ledgerInvariantChecker.assertNoViolations();
    }

    @Test
    void anotherUserCannotTopUpOrWithdrawFromWallet() throws Exception {
        String ownerId = registerUser();
        String otherUserId = registerUser();
        String walletId = createWallet(ownerId);
        UUID walletUuid = UUID.fromString(walletId);

        fundWallet(walletId, 1000);

        long balanceBefore = balance(walletId);
        int ledgerEntriesBefore = ledgerEntryRepository
                .findByWalletIdOrderByIdAsc(walletUuid).size();
        int transactionsBefore = transactionRepository.findAll().size();

        mockMvc.perform(post("/api/v1/wallets/{id}/topup", walletId)
                        .header("X-User-Id", otherUserId)
                        .header("Idempotency-Key", "unauthorized-topup-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(movementBody(100)))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/v1/wallets/{id}/withdraw", walletId)
                        .header("X-User-Id", otherUserId)
                        .header("Idempotency-Key", "unauthorized-withdraw-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(movementBody(100)))
                .andExpect(status().isNotFound());

        assertEquals(balanceBefore, balance(walletId));
        assertEquals(ledgerEntriesBefore, ledgerEntryRepository
                .findByWalletIdOrderByIdAsc(walletUuid).size());
        assertEquals(transactionsBefore, transactionRepository.findAll().size());
    }
}
