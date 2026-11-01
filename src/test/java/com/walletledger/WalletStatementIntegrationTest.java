package com.walletledger;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.walletledger.repository.WalletRepository;
import com.walletledger.service.JwtService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class WalletStatementIntegrationTest {

    private static final UUID SYSTEM_WALLET_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private WalletRepository walletRepository;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private String bearerToken(String userId) {
        return "Bearer " + jwtService.generateToken(UUID.fromString(userId));
    }

    private String registerUser() throws Exception {
        String email = "statement-" + UUID.randomUUID()
                + "@walletledger.test";

        String response = mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                   "name": "Statement Test User",
                                   "email": "%s",
                                   "password": "ValidPassword123!"
                                }
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

    private void topUp(String userId, String walletId, long amount)
            throws Exception {

        mockMvc.perform(post("/api/v1/wallets/{id}/topup", walletId)
                        .header("Authorization", bearerToken(userId))
                        .header("Idempotency-Key",
                                "statement-topup-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amountPaise": %d}
                                """.formatted(amount)))
                .andExpect(status().isCreated());
    }


    private String topUpAndGetTransactionId(
            String userId, String walletId, long amount) throws Exception {

        String response = mockMvc.perform(post(
                        "/api/v1/wallets/{id}/topup", walletId)
                        .header("Authorization", bearerToken(userId))
                        .header("Idempotency-Key",
                                "statement-cursor-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amountPaise": %d}
                                """.formatted(amount)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return jsonMapper.readTree(response)
                .get("transactionId").asText();
    }
    @Test
    void missingBearerTokenCannotReadWalletStatement() throws Exception {
        UUID walletId = UUID.randomUUID();

        mockMvc.perform(get(
                        "/api/v1/wallets/{id}/transactions", walletId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void userCannotReadAnotherUsersWalletStatement() throws Exception {
        String ownerId = registerUser();
        String otherUserId = registerUser();

        String ownerWalletId = createWallet(ownerId);
        topUp(ownerId, ownerWalletId, 500);

        mockMvc.perform(get(
                        "/api/v1/wallets/{id}/transactions",
                        ownerWalletId)
                        .header("Authorization", bearerToken(otherUserId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void systemWalletStatementCannotBeRead() throws Exception {
        String userId = registerUser();

        mockMvc.perform(get(
                        "/api/v1/wallets/{id}/transactions",
                        SYSTEM_WALLET_ID)
                        .header("Authorization", bearerToken(userId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void ownerCanReadOwnWalletStatement() throws Exception {
        String userId = registerUser();
        String walletId = createWallet(userId);

        topUp(userId, walletId, 750);

        mockMvc.perform(get(
                        "/api/v1/wallets/{id}/transactions",
                        walletId)
                        .header("Authorization", bearerToken(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items.length()").value(1));
    }

    @Test
    void userToUserTransferShowsCorrectCounterpartyWallets()
            throws Exception {

        String senderId = registerUser();
        String receiverId = registerUser();

        String senderWalletId = createWallet(senderId);
        String receiverWalletId = createWallet(receiverId);

        topUp(senderId, senderWalletId, 1000);

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Authorization", bearerToken(senderId))
                        .header("Idempotency-Key",
                                "statement-transfer-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceWalletId": "%s",
                                  "destinationWalletId": "%s",
                                  "amountPaise": 300
                                }
                                """.formatted(senderWalletId, receiverWalletId)))
                .andExpect(status().isCreated());

        String senderResponse = mockMvc.perform(get(
                        "/api/v1/wallets/{id}/transactions", senderWalletId)
                        .header("Authorization", bearerToken(senderId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].direction").value("DEBIT"))
                .andExpect(jsonPath("$.items[0].amount_paise").value(300))
                .andExpect(jsonPath("$.items[0].counterparty_wallet_id")
                        .value(receiverWalletId))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String receiverResponse = mockMvc.perform(get(
                        "/api/v1/wallets/{id}/transactions", receiverWalletId)
                        .header("Authorization", bearerToken(receiverId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].direction").value("CREDIT"))
                .andExpect(jsonPath("$.items[0].amount_paise").value(300))
                .andExpect(jsonPath("$.items[0].counterparty_wallet_id")
                        .value(senderWalletId))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String senderTransactionId = jsonMapper.readTree(senderResponse)
                .get("items").get(0).get("transaction_id").asText();

        String receiverTransactionId = jsonMapper.readTree(receiverResponse)
                .get("items").get(0).get("transaction_id").asText();

        org.junit.jupiter.api.Assertions.assertEquals(
                senderTransactionId, receiverTransactionId);
    }
    @Test
    void emptyWalletReturnsEmptyStatement() throws Exception {
        String userId = registerUser();
        String walletId = createWallet(userId);

        String response = mockMvc.perform(get(
                        "/api/v1/wallets/{id}/transactions", walletId)
                        .header("Authorization", bearerToken(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.next_cursor").doesNotExist())
                .andReturn()
                .getResponse()
                .getContentAsString();

        var json = jsonMapper.readTree(response);
        org.junit.jupiter.api.Assertions.assertTrue(
                json.get("next_cursor").isNull());
    }

    @Test
    void paginationWalkReturnsEveryEntryOnceInDescendingOrder()
            throws Exception {

        String userId = registerUser();
        String walletId = createWallet(userId);

        // Each top-up creates one ledger entry for this user's wallet.
        for (int i = 1; i <= 5; i++) {
            topUp(userId, walletId, i * 100L);
        }

        java.util.List<Long> collectedEntryIds =
                new java.util.ArrayList<>();

        String cursor = null;
        int pageCount = 0;

        do {
            var request = get(
                    "/api/v1/wallets/{id}/transactions", walletId)
                    .header("Authorization", bearerToken(userId))
                    .param("limit", "2");

            if (cursor != null) {
                request.param("cursor", cursor);
            }

            String response = mockMvc.perform(request)
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();

            var json = jsonMapper.readTree(response);
            var items = json.get("items");

            org.junit.jupiter.api.Assertions.assertTrue(
                    items.size() <= 2,
                    "A page must not contain more than the requested limit.");

            for (var item : items) {
                org.junit.jupiter.api.Assertions.assertTrue(
                        item.has("entry_id"));
                org.junit.jupiter.api.Assertions.assertTrue(
                        item.has("transaction_id"));
                org.junit.jupiter.api.Assertions.assertTrue(
                        item.has("amount_paise"));
                org.junit.jupiter.api.Assertions.assertTrue(
                        item.has("counterparty_wallet_id"));
                org.junit.jupiter.api.Assertions.assertTrue(
                        item.has("created_at"));

                collectedEntryIds.add(item.get("entry_id").asLong());
            }

            cursor = json.get("next_cursor").isNull()
                    ? null
                    : json.get("next_cursor").asText();

            pageCount++;

            org.junit.jupiter.api.Assertions.assertTrue(
                    pageCount <= 3,
                    "Pagination must finish within three pages.");
        } while (cursor != null);

        org.junit.jupiter.api.Assertions.assertEquals(
                5, collectedEntryIds.size(),
                "All five wallet entries must be returned.");

        org.junit.jupiter.api.Assertions.assertEquals(
                3, pageCount,
                "Five entries with limit two should require three pages.");

        org.junit.jupiter.api.Assertions.assertEquals(
                collectedEntryIds.size(),
                new java.util.HashSet<>(collectedEntryIds).size(),
                "Pagination must not return duplicate entry IDs.");

        for (int i = 1; i < collectedEntryIds.size(); i++) {
            org.junit.jupiter.api.Assertions.assertTrue(
                    collectedEntryIds.get(i - 1)
                            > collectedEntryIds.get(i),
                    "Entries must be returned in descending ID order.");
        }
    }

    @Test
    void malformedCursorReturnsBadRequest() throws Exception {
        String userId = registerUser();
        String walletId = createWallet(userId);

        mockMvc.perform(get(
                        "/api/v1/wallets/{id}/transactions", walletId)
                        .header("Authorization", bearerToken(userId))
                        .param("cursor", "not-a-valid-cursor"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void invalidPageLimitsReturnBadRequest() throws Exception {
        String userId = registerUser();
        String walletId = createWallet(userId);

        for (String invalidLimit : java.util.List.of("0", "101", "-1", "abc")) {
            mockMvc.perform(get(
                            "/api/v1/wallets/{id}/transactions", walletId)
                            .header("Authorization", bearerToken(userId))
                            .param("limit", invalidLimit))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        }
    }

    @Test
    void cursorFromAnotherWalletCannotExposeItsEntries()
            throws Exception {

        String userB = registerUser();
        String walletB = createWallet(userB);

        // Create wallet B's entries first, so their IDs are lower.
        String walletBTransaction1 =
                topUpAndGetTransactionId(userB, walletB, 300);
        String walletBTransaction2 =
                topUpAndGetTransactionId(userB, walletB, 400);

        String userA = registerUser();
        String walletA = createWallet(userA);

        topUp(userA, walletA, 500);
        topUp(userA, walletA, 600);

        // Obtain a cursor from wallet A.
        String firstPage = mockMvc.perform(get(
                        "/api/v1/wallets/{id}/transactions", walletA)
                        .header("Authorization", bearerToken(userA))
                        .param("limit", "1"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String cursorFromWalletA = jsonMapper.readTree(firstPage)
                .get("next_cursor").asText();

        // Apply that cursor to wallet B. The query must still be scoped to B.
        String walletBPage = mockMvc.perform(get(
                        "/api/v1/wallets/{id}/transactions", walletB)
                        .header("Authorization", bearerToken(userB))
                        .param("limit", "10")
                        .param("cursor", cursorFromWalletA))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        var items = jsonMapper.readTree(walletBPage).get("items");

        org.junit.jupiter.api.Assertions.assertEquals(2, items.size());

        java.util.Set<String> returnedTransactionIds =
                new java.util.HashSet<>();

        for (var item : items) {
            returnedTransactionIds.add(
                    item.get("transaction_id").asText());
        }

        org.junit.jupiter.api.Assertions.assertEquals(
                java.util.Set.of(
                        walletBTransaction1,
                        walletBTransaction2),
                returnedTransactionIds,
                "A cursor from wallet A must not expose wallet A entries.");
    }

    @Test
    void reversedTopUpShowsOriginalAndReversalAsSeparateEntries()
            throws Exception {

        String userId = registerUser();
        String walletId = createWallet(userId);

        String originalTransactionId =
                topUpAndGetTransactionId(userId, walletId, 300);

        String reversalResponse = mockMvc.perform(post(
                        "/api/v1/transactions/{id}/reverse",
                        originalTransactionId)
                        .header("Authorization", bearerToken(userId))
                        .header("Idempotency-Key",
                                "statement-reversal-" + UUID.randomUUID()))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String reversalTransactionId = jsonMapper.readTree(reversalResponse)
                .get("reversalTransactionId").asText();

        String statementResponse = mockMvc.perform(get(
                        "/api/v1/wallets/{id}/transactions", walletId)
                        .header("Authorization", bearerToken(userId))
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        var items = jsonMapper.readTree(statementResponse).get("items");

        org.junit.jupiter.api.Assertions.assertEquals(2, items.size());

        var reversalEntry = items.get(0);
        var originalEntry = items.get(1);

        // Newest reversal entry must appear before the original entry.
        org.junit.jupiter.api.Assertions.assertEquals(
                reversalTransactionId,
                reversalEntry.get("transaction_id").asText());

        org.junit.jupiter.api.Assertions.assertEquals(
                "REVERSAL", reversalEntry.get("type").asText());

        org.junit.jupiter.api.Assertions.assertEquals(
                "COMPLETED", reversalEntry.get("status").asText());

        org.junit.jupiter.api.Assertions.assertEquals(
                "DEBIT", reversalEntry.get("direction").asText());

        org.junit.jupiter.api.Assertions.assertEquals(
                300L, reversalEntry.get("amount_paise").asLong());

        org.junit.jupiter.api.Assertions.assertTrue(
                reversalEntry.get("counterparty_wallet_id").isNull(),
                "SYSTEM wallet ID must be hidden for reversal entry.");

        org.junit.jupiter.api.Assertions.assertEquals(
                originalTransactionId,
                originalEntry.get("transaction_id").asText());

        org.junit.jupiter.api.Assertions.assertEquals(
                "TOPUP", originalEntry.get("type").asText());

        org.junit.jupiter.api.Assertions.assertEquals(
                "REVERSED", originalEntry.get("status").asText());

        org.junit.jupiter.api.Assertions.assertEquals(
                "CREDIT", originalEntry.get("direction").asText());

        org.junit.jupiter.api.Assertions.assertEquals(
                300L, originalEntry.get("amount_paise").asLong());

        org.junit.jupiter.api.Assertions.assertTrue(
                originalEntry.get("counterparty_wallet_id").isNull(),
                "SYSTEM wallet ID must be hidden for original top-up entry.");

        long signedBalance = 0L;

        for (var item : items) {
            long amount = item.get("amount_paise").asLong();

            signedBalance += "CREDIT".equals(
                    item.get("direction").asText()) ? amount : -amount;
        }

        org.junit.jupiter.api.Assertions.assertEquals(
                0L, signedBalance,
                "Signed statement entries must match the wallet's zero balance.");

        org.junit.jupiter.api.Assertions.assertEquals(
                0L,
                walletRepository.findById(UUID.fromString(walletId))
                        .orElseThrow().getBalance());
    }

    @Test
    void defaultAndMaximumPageSizesAreRespected() throws Exception {
        String userId = registerUser();
        String walletId = createWallet(userId);

        for (int i = 0; i < 21; i++) {
            topUpAndGetTransactionId(userId, walletId, 100);
        }

        // Default limit must be 20.
        String defaultResponse = mockMvc.perform(get(
                        "/api/v1/wallets/{id}/transactions", walletId)
                        .header("Authorization", bearerToken(userId)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        var defaultJson = jsonMapper.readTree(defaultResponse);

        org.junit.jupiter.api.Assertions.assertEquals(
                20, defaultJson.get("items").size());

        org.junit.jupiter.api.Assertions.assertFalse(
                defaultJson.get("next_cursor").isNull());

        // Maximum allowed limit must return all 21 entries.
        String maximumResponse = mockMvc.perform(get(
                        "/api/v1/wallets/{id}/transactions", walletId)
                        .header("Authorization", bearerToken(userId))
                        .param("limit", "100"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        var maximumJson = jsonMapper.readTree(maximumResponse);

        org.junit.jupiter.api.Assertions.assertEquals(
                21, maximumJson.get("items").size());

        org.junit.jupiter.api.Assertions.assertTrue(
                maximumJson.get("next_cursor").isNull());
    }

    @Test
    void newEntriesDoNotDisturbAnExistingPaginationWalk()
            throws Exception {

        String userId = registerUser();
        String walletId = createWallet(userId);

        String firstId = topUpAndGetTransactionId(userId, walletId, 100);
        String secondId = topUpAndGetTransactionId(userId, walletId, 200);
        String thirdId = topUpAndGetTransactionId(userId, walletId, 300);

        // Begin walking the original three entries.
        String firstPageResponse = mockMvc.perform(get(
                        "/api/v1/wallets/{id}/transactions", walletId)
                        .header("Authorization", bearerToken(userId))
                        .param("limit", "1"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        var firstPage = jsonMapper.readTree(firstPageResponse);
        String cursor = firstPage.get("next_cursor").asText();

        String firstPageTransactionId = firstPage.get("items")
                .get(0).get("transaction_id").asText();

        // Insert a newer entry after page one has already been read.
        String newlyInsertedId =
                topUpAndGetTransactionId(userId, walletId, 400);

        java.util.Set<String> walkedTransactionIds =
                new java.util.HashSet<>();

        walkedTransactionIds.add(firstPageTransactionId);

        // Continue using the original cursor.
        while (cursor != null) {
            String pageResponse = mockMvc.perform(get(
                            "/api/v1/wallets/{id}/transactions", walletId)
                            .header("Authorization", bearerToken(userId))
                            .param("limit", "1")
                            .param("cursor", cursor))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();

            var page = jsonMapper.readTree(pageResponse);
            var items = page.get("items");

            for (var item : items) {
                walkedTransactionIds.add(
                        item.get("transaction_id").asText());
            }

            cursor = page.get("next_cursor").isNull()
                    ? null
                    : page.get("next_cursor").asText();
        }

        org.junit.jupiter.api.Assertions.assertEquals(
                3, walkedTransactionIds.size());

        org.junit.jupiter.api.Assertions.assertTrue(
                walkedTransactionIds.contains(firstId));

        org.junit.jupiter.api.Assertions.assertTrue(
                walkedTransactionIds.contains(secondId));

        org.junit.jupiter.api.Assertions.assertTrue(
                walkedTransactionIds.contains(thirdId));

        org.junit.jupiter.api.Assertions.assertFalse(
                walkedTransactionIds.contains(newlyInsertedId),
                "A new entry must not appear in an already-started pagination walk.");
    }
}
