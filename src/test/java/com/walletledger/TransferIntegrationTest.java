package com.walletledger;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.walletledger.entity.LedgerEntry;
import com.walletledger.entity.LedgerEntryDirection;
import com.walletledger.entity.Wallet;
import com.walletledger.repository.LedgerEntryRepository;
import com.walletledger.repository.TransactionRepository;
import com.walletledger.repository.TransactionAuditRepository;
import com.walletledger.repository.OutboxEventRepository;
import com.walletledger.entity.OutboxEvent;
import com.walletledger.repository.WalletRepository;
import com.walletledger.service.LedgerInvariantChecker;
import com.walletledger.service.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class TransferIntegrationTest {

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
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

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
    @MockitoSpyBean
    private OutboxEventRepository outboxEventSpy;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private String registerUser() throws Exception {
        String email = "transfer-" + UUID.randomUUID() + "@walletledger.test";

        String response = mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Transfer Test User","email":"%s","password":"ValidPassword123!"}
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

    private void fundWallet(String walletId, long amountPaise)
            throws Exception {
        Wallet wallet = walletRepository.findById(UUID.fromString(walletId))
                .orElseThrow();


        mockMvc.perform(post("/api/v1/wallets/{id}/topup", walletId)
                        .header("Authorization", bearerToken(wallet.getUserId()))
                        .header("Idempotency-Key", "test-fund-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amountPaise": %d}
                                """.formatted(amountPaise)))
                .andExpect(status().isCreated());
    }

    private long balance(String walletId) {
        return walletRepository.findById(UUID.fromString(walletId))
                .orElseThrow()
                .getBalance();
    }

    private String transferBody(String sourceId, String destinationId, long amount) {
        return """
                {
                  "sourceWalletId": "%s",
                  "destinationWalletId": "%s",
                  "amountPaise": %d
                }
                """.formatted(sourceId, destinationId, amount);
    }

    @Test
    void mysqlBinaryUuidOrderingMatchesWalletLockOrderComparator() {
        List<UUID> ids = List.of(
                UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"),
                UUID.fromString("80000000-0000-0000-0000-000000000000"),
                UUID.fromString("00000000-0000-0000-8000-000000000000"),
                UUID.fromString("00000000-0000-0000-0000-000000000000"),
                UUID.fromString("00000000-0000-0001-0000-000000000000"),
                UUID.fromString("7fffffff-ffff-ffff-ffff-ffffffffffff"),
                UUID.fromString("00000000-0000-0000-7fff-ffffffffffff"),
                UUID.fromString("80000000-0000-0000-0000-000000000001")
        );

        String selects = String.join(
                " UNION ALL ",
                ids.stream()
                        .map(ignored -> "SELECT UUID_TO_BIN(?) AS id")
                        .toList()
        );

        String sql = "SELECT HEX(id) FROM (" + selects
                + ") AS uuid_values ORDER BY id";

        Object[] parameters = ids.stream()
                .map(UUID::toString)
                .toArray();

        List<String> mysqlOrder = jdbcTemplate.queryForList(
                sql, String.class, parameters);

        List<String> javaOrder = ids.stream()
                .sorted(com.walletledger.service.WalletLockOrder.COMPARATOR)
                .map(id -> id.toString()
                        .replace("-", "")
                        .toUpperCase(java.util.Locale.ROOT))
                .toList();

        assertEquals(javaOrder, mysqlOrder,
                "Java lock ordering must match MySQL BINARY(16) ordering.");
    }

    @Test
    void successfulTransferUpdatesBalancesAndCreatesTwoLedgerEntries()
            throws Exception {
        String senderId = registerUser();
        String receiverId = registerUser();
        String sourceId = createWallet(senderId);
        String destinationId = createWallet(receiverId);
        fundWallet(sourceId, 1000);

        String response = mockMvc.perform(post("/api/v1/transfers")
                        .header("Authorization", bearerToken(senderId))
                        .header("Idempotency-Key", "transfer-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(sourceId, destinationId, 300)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.amountPaise").value(300))
                .andReturn()
                .getResponse()
                .getContentAsString();

        UUID transactionId = UUID.fromString(
                jsonMapper.readTree(response).get("transactionId").asText());

        assertEquals(700, balance(sourceId));
        assertEquals(300, balance(destinationId));

        List<LedgerEntry> entries =
                ledgerEntryRepository.findByTransactionIdOrderByIdAsc(transactionId);

        assertEquals(2, entries.size());

        UUID sourceWalletId = UUID.fromString(sourceId);
        UUID destinationWalletId = UUID.fromString(destinationId);

        LedgerEntry debit = entries.stream()
                .filter(entry -> entry.getDirection() == LedgerEntryDirection.DEBIT)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing debit entry"));

        LedgerEntry credit = entries.stream()
                .filter(entry -> entry.getDirection() == LedgerEntryDirection.CREDIT)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing credit entry"));

        assertEquals(sourceWalletId, debit.getWalletId());
        assertEquals(destinationWalletId, credit.getWalletId());
        assertEquals(300, debit.getAmount());
        assertEquals(300, credit.getAmount());

        // A completed transfer must have an audit transition.
        var auditRecords = transactionAuditRepository
                .findByTransactionIdOrderByIdAsc(transactionId);

        assertEquals(1, auditRecords.size());
        assertEquals("PENDING", auditRecords.get(0).getFromStatus());
        assertEquals("COMPLETED", auditRecords.get(0).getToStatus());
        assertEquals(UUID.fromString(senderId), auditRecords.get(0).getChangedBy());

        // A completed transfer must have an unpublished outbox event.
        var events = outboxEventRepository.findByPublishedFalseOrderByIdAsc()
                .stream()
                .filter(event -> event.getAggregateId().equals(transactionId))
                .toList();

        assertEquals(1, events.size());
        assertEquals("TRANSACTION_COMPLETED", events.get(0).getEventType());
        assertEquals("TRANSACTION", events.get(0).getAggregateType());
        assertTrue(!events.get(0).isPublished());

        var payload = jsonMapper.readTree(events.get(0).getPayload());
        assertEquals(transactionId.toString(), payload.get("transaction_id").asText());
        assertEquals(300, payload.get("amount_paise").asLong());
        assertEquals("INR", payload.get("currency").asText());
        assertEquals("COMPLETED", payload.get("status").asText());
    }

    @Test
    void insufficientFundsDoNotChangeBalancesOrCreateLedgerEntries()
            throws Exception {
        String senderId = registerUser();
        String receiverId = registerUser();
        String sourceId = createWallet(senderId);
        String destinationId = createWallet(receiverId);

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Authorization", bearerToken(senderId))
                        .header("Idempotency-Key", "insufficient-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(sourceId, destinationId, 100)))
                .andExpect(status().isUnprocessableEntity());

        assertEquals(0, balance(sourceId));
        assertEquals(0, balance(destinationId));
    }

    @Test
    void userCannotTransferFromAnotherUsersWallet() throws Exception {
        String ownerId = registerUser();
        String anotherUserId = registerUser();
        String sourceId = createWallet(ownerId);
        String destinationId = createWallet(anotherUserId);
        fundWallet(sourceId, 1000);

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Authorization", bearerToken(anotherUserId))
                        .header("Idempotency-Key", "ownership-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(sourceId, destinationId, 100)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        assertEquals(1000, balance(sourceId));
        assertEquals(0, balance(destinationId));
    }

    @Test
    void repeatingSameIdempotencyKeyDoesNotTransferMoneyTwice()
            throws Exception {
        String senderId = registerUser();
        String receiverId = registerUser();
        String sourceId = createWallet(senderId);
        String destinationId = createWallet(receiverId);
        fundWallet(sourceId, 1000);

        String key = "retry-" + UUID.randomUUID();
        String body = transferBody(sourceId, destinationId, 250);

        String firstResponse = mockMvc.perform(post("/api/v1/transfers")
                        .header("Authorization", bearerToken(senderId))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String secondResponse = mockMvc.perform(post("/api/v1/transfers")
                        .header("Authorization", bearerToken(senderId))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertEquals(
                jsonMapper.readTree(firstResponse).get("transactionId").asText(),
                jsonMapper.readTree(secondResponse).get("transactionId").asText());

        assertEquals(firstResponse, secondResponse,
                "An idempotent retry must return the exact original JSON body.");

        var storedTransaction = transactionRepository
                .findByInitiatedByAndIdempotencyKey(
                        UUID.fromString(senderId), key)
                .orElseThrow();

        assertEquals(Short.valueOf((short) 201),
                storedTransaction.getResponseStatus());
        assertEquals(firstResponse, storedTransaction.getResponseBody(),
                "The transaction must persist the exact HTTP response body.");

        assertEquals(750, balance(sourceId));
        assertEquals(250, balance(destinationId));
    }
    @Test
    void reusingIdempotencyKeyForDifferentRequestReturnsConflict()
            throws Exception {
        String senderId = registerUser();
        String receiverId = registerUser();
        String sourceId = createWallet(senderId);
        String destinationId = createWallet(receiverId);
        fundWallet(sourceId, 1000);

        String key = "mismatch-" + UUID.randomUUID();

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Authorization", bearerToken(senderId))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(sourceId, destinationId, 200)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Authorization", bearerToken(senderId))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(sourceId, destinationId, 300)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        assertEquals(800, balance(sourceId));
        assertEquals(200, balance(destinationId));
    }

    @Test
    void simultaneousRequestsWithSameIdempotencyKeyTransferMoneyOnlyOnce()
            throws Exception {
        String senderId = registerUser();
        String receiverId = registerUser();
        String sourceId = createWallet(senderId);
        String destinationId = createWallet(receiverId);
        fundWallet(sourceId, 1000);

        String key = "concurrent-" + UUID.randomUUID();
        String body = transferBody(sourceId, destinationId, 250);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            java.util.concurrent.Callable<MvcResult> request = () -> {
                ready.countDown();

                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException(
                            "Timed out waiting to start concurrent request.");
                }

                return mockMvc.perform(post("/api/v1/transfers")
                                .header("Authorization", bearerToken(senderId))
                                .header("Idempotency-Key", key)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                        .andReturn();
            };

            Future<MvcResult> firstFuture = executor.submit(request);
            Future<MvcResult> secondFuture = executor.submit(request);

            assertTrue(ready.await(10, TimeUnit.SECONDS),
                    "Both requests should be ready to start.");

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
            assertEquals(750, balance(sourceId));
            assertEquals(250, balance(destinationId));

            UUID transactionId = UUID.fromString(firstTransactionId);

            assertEquals(2, ledgerEntryRepository
                    .findByTransactionIdOrderByIdAsc(transactionId).size());

            assertEquals(transactionId, transactionRepository
                    .findByInitiatedByAndIdempotencyKey(
                            UUID.fromString(senderId), key)
                    .orElseThrow()
                    .getId());
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
        ledgerInvariantChecker.assertNoViolations();
    }

    @Test
    void simultaneousTransfersCannotOverspendTheSameWallet()
            throws Exception {
        String senderId = registerUser();
        String receiverOneId = registerUser();
        String receiverTwoId = registerUser();

        String sourceId = createWallet(senderId);
        String destinationOneId = createWallet(receiverOneId);
        String destinationTwoId = createWallet(receiverTwoId);

        fundWallet(sourceId, 1000);

        String keyOne = "compete-one-" + UUID.randomUUID();
        String keyTwo = "compete-two-" + UUID.randomUUID();

        String bodyOne = transferBody(sourceId, destinationOneId, 700);
        String bodyTwo = transferBody(sourceId, destinationTwoId, 700);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            java.util.concurrent.Callable<MvcResult> requestOne = () -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to start.");
                }
                return mockMvc.perform(post("/api/v1/transfers")
                                .header("Authorization", bearerToken(senderId))
                                .header("Idempotency-Key", keyOne)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(bodyOne))
                        .andReturn();
            };

            java.util.concurrent.Callable<MvcResult> requestTwo = () -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to start.");
                }
                return mockMvc.perform(post("/api/v1/transfers")
                                .header("Authorization", bearerToken(senderId))
                                .header("Idempotency-Key", keyTwo)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(bodyTwo))
                        .andReturn();
            };

            Future<MvcResult> futureOne = executor.submit(requestOne);
            Future<MvcResult> futureTwo = executor.submit(requestTwo);

            assertTrue(ready.await(10, TimeUnit.SECONDS),
                    "Both requests should be ready to start.");

            start.countDown();

            MvcResult resultOne = futureOne.get(30, TimeUnit.SECONDS);
            MvcResult resultTwo = futureTwo.get(30, TimeUnit.SECONDS);

            int statusOne = resultOne.getResponse().getStatus();
            int statusTwo = resultTwo.getResponse().getStatus();

            assertEquals(1,
                    java.util.stream.Stream.of(statusOne, statusTwo)
                            .filter(status -> status == 201)
                            .count(),
                    "Exactly one transfer should succeed.");

            assertEquals(1,
                    java.util.stream.Stream.of(statusOne, statusTwo)
                            .filter(status -> status == 422)
                            .count(),
                    "The other transfer should fail for insufficient funds.");

            assertEquals(300, balance(sourceId));

            long destinationOneBalance = balance(destinationOneId);
            long destinationTwoBalance = balance(destinationTwoId);

            assertEquals(700,
                    destinationOneBalance + destinationTwoBalance);

            String successfulTransferBody =
                    statusOne == 201 ? resultOne.getResponse().getContentAsString()
                            : resultTwo.getResponse().getContentAsString();

            UUID successfulTransactionId = UUID.fromString(
                    jsonMapper.readTree(successfulTransferBody)
                            .get("transactionId").asText());

            assertEquals(2,
                    ledgerEntryRepository.findByTransactionIdOrderByIdAsc(
                            successfulTransactionId).size(),
                    "The successful transfer should record exactly one debit and one credit.");} finally {
            start.countDown();
            executor.shutdownNow();
        }
        ledgerInvariantChecker.assertNoViolations();
    }

    @Test
    void failedTransferRollsBackTransactionBalancesAndLedgerEntries()
            throws Exception {
        String senderId = registerUser();
        String receiverId = registerUser();
        String sourceId = createWallet(senderId);
        String destinationId = createWallet(receiverId);

        fundWallet(sourceId, 1);

        // Temporary overflow fixture: always restore it before the test ends.
        Wallet destination = walletRepository
                .findById(UUID.fromString(destinationId))
                .orElseThrow();
        destination.credit(Long.MAX_VALUE);
        walletRepository.saveAndFlush(destination);

        String key = "rollback-" + UUID.randomUUID();

        int sourceLedgerEntriesBefore = ledgerEntryRepository
                .findByWalletIdOrderByIdAsc(UUID.fromString(sourceId)).size();
        int destinationLedgerEntriesBefore = ledgerEntryRepository
                .findByWalletIdOrderByIdAsc(UUID.fromString(destinationId)).size();

        try {
            mockMvc.perform(post("/api/v1/transfers")
                            .header("Authorization", bearerToken(senderId))
                            .header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(transferBody(sourceId, destinationId, 1)))
                    .andExpect(status().isUnprocessableEntity());

            assertEquals(1, balance(sourceId));
            assertEquals(Long.MAX_VALUE, balance(destinationId));

            assertTrue(transactionRepository
                    .findByInitiatedByAndIdempotencyKey(
                            UUID.fromString(senderId), key)
                    .isEmpty(),
                    "The failed transfer transaction must not persist.");

            assertEquals(sourceLedgerEntriesBefore,
                    ledgerEntryRepository
                            .findByWalletIdOrderByIdAsc(UUID.fromString(sourceId)).size(),
                    "The failed transfer must not add source ledger entries.");

            assertEquals(destinationLedgerEntriesBefore,
                    ledgerEntryRepository
                            .findByWalletIdOrderByIdAsc(UUID.fromString(destinationId)).size(),
                    "The failed transfer must not add destination ledger entries.");
        } finally {
            Wallet overflowWallet = walletRepository
                    .findById(UUID.fromString(destinationId))
                    .orElseThrow();

            if (overflowWallet.getBalance() > 0) {
                overflowWallet.debit(overflowWallet.getBalance());
            }

            walletRepository.saveAndFlush(overflowWallet);
        }

        ledgerInvariantChecker.assertNoViolations();
    }

    @Test
    void outboxFailureRollsBackEntireTransfer() throws Exception {
        String senderId = registerUser();
        String receiverId = registerUser();
        String sourceId = createWallet(senderId);
        String destinationId = createWallet(receiverId);

        fundWallet(sourceId, 1000);

        UUID sourceWalletId = UUID.fromString(sourceId);
        UUID destinationWalletId = UUID.fromString(destinationId);
        UUID senderUserId = UUID.fromString(senderId);
        String key = "outbox-failure-" + UUID.randomUUID();

        long sourceBalanceBefore = balance(sourceId);
        long destinationBalanceBefore = balance(destinationId);
        int sourceLedgerCountBefore = ledgerEntryRepository
                .findByWalletIdOrderByIdAsc(sourceWalletId).size();
        int destinationLedgerCountBefore = ledgerEntryRepository
                .findByWalletIdOrderByIdAsc(destinationWalletId).size();
        long auditCountBefore = transactionAuditRepository.count();
        long outboxCountBefore = outboxEventRepository.count();

        doThrow(new RuntimeException("Forced outbox failure"))
                .when(outboxEventSpy)
                .saveAndFlush(any(OutboxEvent.class));

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Authorization", bearerToken(senderId))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(sourceId, destinationId, 100)))
                .andExpect(status().isInternalServerError());

        assertEquals(sourceBalanceBefore, balance(sourceId),
                "Source balance must roll back.");
        assertEquals(destinationBalanceBefore, balance(destinationId),
                "Destination balance must roll back.");

        assertTrue(transactionRepository
                .findByInitiatedByAndIdempotencyKey(senderUserId, key)
                .isEmpty(), "Transaction must roll back.");

        assertEquals(sourceLedgerCountBefore,
                ledgerEntryRepository
                        .findByWalletIdOrderByIdAsc(sourceWalletId).size(),
                "Source ledger entries must roll back.");
        assertEquals(destinationLedgerCountBefore,
                ledgerEntryRepository
                        .findByWalletIdOrderByIdAsc(destinationWalletId).size(),
                "Destination ledger entries must roll back.");

        assertEquals(auditCountBefore, transactionAuditRepository.count(),
                "Audit record must roll back.");
        assertEquals(outboxCountBefore, outboxEventRepository.count(),
                "Outbox event must roll back.");
    }

    @Test
    void thousandConcurrentTransfersAcrossWalletPoolPreserveBalances()
            throws Exception {
        int walletCount = 10;
        int transferCount = 1000;
        int workerCount = 16;
        long startingBalancePaise = 100_000;
        long transferAmountPaise = 10;

        List<String> userIds = new java.util.ArrayList<>();
        List<String> walletIds = new java.util.ArrayList<>();

        for (int i = 0; i < walletCount; i++) {
            String userId = registerUser();
            String walletId = createWallet(userId);
            fundWallet(walletId, startingBalancePaise);
            userIds.add(userId);
            walletIds.add(walletId);
        }

        ExecutorService executor = Executors.newFixedThreadPool(workerCount);
        CountDownLatch ready = new CountDownLatch(workerCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new java.util.ArrayList<>();

        try {
            for (int i = 0; i < transferCount; i++) {
                final int transferIndex = i;

                futures.add(executor.submit(() -> {
                    ready.countDown();

                    if (!start.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException(
                                "Timed out waiting to start wallet-pool transfer.");
                    }

                    int sourceIndex = transferIndex % walletCount;
                    int destinationIndex = (sourceIndex + 1) % walletCount;

                    MvcResult result = mockMvc.perform(post("/api/v1/transfers")
                                    .header("Authorization", bearerToken(userIds.get(sourceIndex)))
                                    .header("Idempotency-Key",
                                            "wallet-pool-" + transferIndex + "-"
                                                    + UUID.randomUUID())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(transferBody(
                                            walletIds.get(sourceIndex),
                                            walletIds.get(destinationIndex),
                                            transferAmountPaise)))
                            .andReturn();

                    return result.getResponse().getStatus();
                }));
            }

            assertTrue(ready.await(30, TimeUnit.SECONDS),
                    "All worker threads should be ready before the load starts.");

            start.countDown();

            for (int i = 0; i < futures.size(); i++) {
                assertEquals(201, futures.get(i).get(120, TimeUnit.SECONDS),
                        "Transfer request " + i + " should return HTTP 201.");
            }
        } finally {
            start.countDown();
            executor.shutdownNow();

            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS),
                    "Transfer worker threads should terminate.");
        }

        for (String walletId : walletIds) {
            assertEquals(startingBalancePaise, balance(walletId),
                    "Each wallet should finish with its original balance: " + walletId);
        }

        ledgerInvariantChecker.assertNoViolations();
    }
    @Test
    void thousandOppositeDirectionTransfersCompleteWithoutDeadlock()
            throws Exception {
        int transferCount = 2000;
        int workerCount = 16;
        long startingBalancePaise = 10_000;
        long transferAmountPaise = 1;

        String userA = registerUser();
        String walletA = createWallet(userA);
        String userB = registerUser();
        String walletB = createWallet(userB);

        fundWallet(walletA, startingBalancePaise);
        fundWallet(walletB, startingBalancePaise);

        ExecutorService executor = Executors.newFixedThreadPool(workerCount);
        CountDownLatch ready = new CountDownLatch(workerCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new java.util.ArrayList<>();

        try {
            for (int i = 0; i < transferCount; i++) {
                final int transferIndex = i;

                futures.add(executor.submit(() -> {
                    ready.countDown();

                    if (!start.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException(
                                "Timed out waiting to start opposite-direction transfers.");
                    }

                    boolean aToB = transferIndex % 2 == 0;
                    String sourceWallet = aToB ? walletA : walletB;
                    String destinationWallet = aToB ? walletB : walletA;
                    String sourceUser = aToB ? userA : userB;

                    MvcResult result = mockMvc.perform(post("/api/v1/transfers")
                            .header("Authorization", bearerToken(sourceUser))
                            .header("Idempotency-Key",
                                    "opposite-stress-" + transferIndex + "-" + UUID.randomUUID())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(transferBody(
                                    sourceWallet, destinationWallet, transferAmountPaise)))
                            .andReturn();

                    return result.getResponse().getStatus();
                }));
            }

            assertTrue(ready.await(30, TimeUnit.SECONDS),
                    "All worker threads should be ready before the load starts.");

            start.countDown();

            for (int i = 0; i < futures.size(); i++) {
                assertEquals(201, futures.get(i).get(180, TimeUnit.SECONDS),
                        "Opposite-direction transfer " + i + " should return HTTP 201.");
            }
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS),
                    "Transfer worker threads should terminate.");
        }

        assertEquals(startingBalancePaise, balance(walletA),
                "Wallet A should finish with its original balance.");
        assertEquals(startingBalancePaise, balance(walletB),
                "Wallet B should finish with its original balance.");

        ledgerInvariantChecker.assertNoViolations();
    }
    @Test
    void simultaneousOppositeDirectionTransfersCompleteWithoutDeadlock()
            throws Exception {
        String userA = registerUser();
        String userB = registerUser();
        String walletA = createWallet(userA);
        String walletB = createWallet(userB);

        fundWallet(walletA, 1000);
        fundWallet(walletB, 1000);

        String keyAToB = "opposite-a-to-b-" + UUID.randomUUID();
        String keyBToA = "opposite-b-to-a-" + UUID.randomUUID();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            java.util.concurrent.Callable<MvcResult> transferAToB = () -> {
                ready.countDown();

                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException(
                            "Timed out waiting to start A-to-B transfer.");
                }

                return mockMvc.perform(post("/api/v1/transfers")
                                .header("Authorization", bearerToken(userA))
                                .header("Idempotency-Key", keyAToB)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(transferBody(walletA, walletB, 200)))
                        .andReturn();
            };

            java.util.concurrent.Callable<MvcResult> transferBToA = () -> {
                ready.countDown();

                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException(
                            "Timed out waiting to start B-to-A transfer.");
                }

                return mockMvc.perform(post("/api/v1/transfers")
                                .header("Authorization", bearerToken(userB))
                                .header("Idempotency-Key", keyBToA)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(transferBody(walletB, walletA, 200)))
                        .andReturn();
            };

            Future<MvcResult> futureAToB = executor.submit(transferAToB);
            Future<MvcResult> futureBToA = executor.submit(transferBToA);

            assertTrue(ready.await(10, TimeUnit.SECONDS),
                    "Both opposite-direction transfers should be ready.");

            start.countDown();

            MvcResult resultAToB = futureAToB.get(30, TimeUnit.SECONDS);
            MvcResult resultBToA = futureBToA.get(30, TimeUnit.SECONDS);

            assertEquals(201, resultAToB.getResponse().getStatus(),
                    "A-to-B transfer should complete.");
            assertEquals(201, resultBToA.getResponse().getStatus(),
                    "B-to-A transfer should complete.");

            UUID transactionAToB = UUID.fromString(
                    jsonMapper.readTree(resultAToB.getResponse()
                            .getContentAsString())
                            .get("transactionId").asText());

            UUID transactionBToA = UUID.fromString(
                    jsonMapper.readTree(resultBToA.getResponse()
                            .getContentAsString())
                            .get("transactionId").asText());

            assertNotEquals(transactionAToB, transactionBToA);

            assertEquals(1000, balance(walletA),
                    "Wallet A should finish with its original balance.");
            assertEquals(1000, balance(walletB),
                    "Wallet B should finish with its original balance.");

            assertEquals(2, ledgerEntryRepository
                    .findByTransactionIdOrderByIdAsc(transactionAToB).size());

            assertEquals(2, ledgerEntryRepository
                    .findByTransactionIdOrderByIdAsc(transactionBToA).size());} finally {
            start.countDown();
            executor.shutdownNow();
        }
        ledgerInvariantChecker.assertNoViolations();
    }
}
