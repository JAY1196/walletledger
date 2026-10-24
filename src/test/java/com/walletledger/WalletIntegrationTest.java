package com.walletledger;

import org.junit.jupiter.api.Test;
import com.walletledger.service.LedgerInvariantChecker;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
class WalletIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LedgerInvariantChecker ledgerInvariantChecker;

    private String registerUser() throws Exception {
        String email = "wallet-" + UUID.randomUUID() + "@walletledger.test";

        String response = mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Wallet Test User","email":"%s"}
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return com.fasterxml.jackson.databind.json.JsonMapper.builder()
                .build()
                .readTree(response)
                .get("id")
                .asText();
    }

    @Test
    void createsInrWalletWithZeroBalance() throws Exception {
        String userId = registerUser();

        mockMvc.perform(post("/api/v1/wallets")
                        .header("X-User-Id", userId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.userId").value(userId))
                .andExpect(jsonPath("$.walletType").value("USER"))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.balancePaise").value(0));
    }

    @Test
    void duplicateWalletCreationReturnsConflict() throws Exception {
        String userId = registerUser();

        mockMvc.perform(post("/api/v1/wallets")
                        .header("X-User-Id", userId))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/wallets")
                        .header("X-User-Id", userId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void walletBalanceCanBeReadByOwner() throws Exception {
        String userId = registerUser();

        String response = mockMvc.perform(post("/api/v1/wallets")
                        .header("X-User-Id", userId))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String walletId = com.fasterxml.jackson.databind.json.JsonMapper.builder()
                .build()
                .readTree(response)
                .get("id")
                .asText();

        mockMvc.perform(get("/api/v1/wallets/{id}/balance", walletId)
                        .header("X-User-Id", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(walletId))
                .andExpect(jsonPath("$.balancePaise").value(0));
    }

    @Test
    void anotherUserCannotReadWalletBalance() throws Exception {
        String ownerId = registerUser();
        String anotherUserId = registerUser();

        String response = mockMvc.perform(post("/api/v1/wallets")
                        .header("X-User-Id", ownerId))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String walletId = com.fasterxml.jackson.databind.json.JsonMapper.builder()
                .build()
                .readTree(response)
                .get("id")
                .asText();

        mockMvc.perform(get("/api/v1/wallets/{id}/balance", walletId)
                        .header("X-User-Id", anotherUserId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
    @Test
    void unregisteredUserCannotCreateWallet() throws Exception {
        String unknownUserId = UUID.randomUUID().toString();

        mockMvc.perform(post("/api/v1/wallets")
                        .header("X-User-Id", unknownUserId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
    @Test
    void systemBankUserCannotCreateUserWallet() throws Exception {
        String systemUserId = "00000000-0000-0000-0000-000000000001";

        mockMvc.perform(post("/api/v1/wallets")
                        .header("X-User-Id", systemUserId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void concurrentWalletCreationCreatesExactlyOneWallet() throws Exception {
        String userId = registerUser();
        int requestCount = 20;

        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < requestCount; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();

                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException(
                                "Timed out waiting for concurrent start"
                        );
                    }

                    return mockMvc.perform(
                                    post("/api/v1/wallets")
                                            .header("X-User-Id", userId)
                            )
                            .andReturn()
                            .getResponse()
                            .getStatus();
                }));
            }

            assertTrue(
                    ready.await(10, TimeUnit.SECONDS),
                    "All workers should be ready before requests begin"
            );

            start.countDown();

            int created = 0;
            int conflicts = 0;

            for (Future<Integer> future : futures) {
                int status = future.get(30, TimeUnit.SECONDS);

                if (status == 201) {
                    created++;
                } else if (status == 409) {
                    conflicts++;
                } else {
                    throw new AssertionError(
                            "Unexpected wallet creation HTTP status: " + status
                    );
                }
            }

            assertEquals(1, created, "Exactly one request should create the wallet");
            assertEquals(19, conflicts, "The other requests should conflict");

            ledgerInvariantChecker.assertNoViolations();
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(
                    executor.awaitTermination(10, TimeUnit.SECONDS),
                    "Worker threads should terminate"
            );
        }
    }

}
