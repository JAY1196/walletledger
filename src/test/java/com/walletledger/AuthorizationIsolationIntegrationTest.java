
package com.walletledger;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.walletledger.entity.Wallet;
import com.walletledger.repository.LedgerEntryRepository;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AuthorizationIsolationIntegrationTest {

    private static final long INITIAL_BALANCE_PAise = 10_000L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private String bearerToken(String userId) {
        return "Bearer " + jwtService.generateToken(UUID.fromString(userId));
    }

    private String registerUser() throws Exception {
        String email = "authorization-" + UUID.randomUUID()
                + "@walletledger.test";

        String response = mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Authorization Test User",
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
                        .header("Idempotency-Key", "initial-fund-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amountPaise": %d}
                                """.formatted(amount)))
                .andExpect(status().isCreated());
    }

    private long balance(String walletId) {
        return walletRepository.findById(UUID.fromString(walletId))
                .orElseThrow()
                .getBalance();
    }

    private long ledgerCount(String walletId) {
        return ledgerEntryRepository
                .findByWalletIdOrderByIdAsc(UUID.fromString(walletId))
                .size();
    }

    private void assertBUnchanged(
            String walletB,
            long originalBalance,
            long originalLedgerCount) {

        assertEquals(originalBalance, balance(walletB),
                "User B's cached wallet balance must remain unchanged");

        assertEquals(originalLedgerCount, ledgerCount(walletB),
                "User B's ledger entry count must remain unchanged");
    }

    @Test
    void userACannotReadUserBWalletBalance() throws Exception {
        String userA = registerUser();
        String userB = registerUser();
        String walletB = createWallet(userB);
        topUp(userB, walletB, INITIAL_BALANCE_PAise);

        mockMvc.perform(get("/api/v1/wallets/{id}/balance", walletB)
                        .header("Authorization", bearerToken(userA)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void userACannotTransferFromUserBWallet() throws Exception {
        String userA = registerUser();
        String userB = registerUser();

        String walletA = createWallet(userA);
        String walletB = createWallet(userB);
        topUp(userB, walletB, INITIAL_BALANCE_PAise);

        long balanceBefore = balance(walletB);
        long ledgerBefore = ledgerCount(walletB);

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Authorization", bearerToken(userA))
                        .header("Idempotency-Key", "cross-user-transfer-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceWalletId": "%s",
                                  "destinationWalletId": "%s",
                                  "amountPaise": 1000
                                }
                                """.formatted(walletB, walletA)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        assertBUnchanged(walletB, balanceBefore, ledgerBefore);
    }

    @Test
    void userACannotTopUpUserBWallet() throws Exception {
        String userA = registerUser();
        String userB = registerUser();
        String walletB = createWallet(userB);
        topUp(userB, walletB, INITIAL_BALANCE_PAise);

        long balanceBefore = balance(walletB);
        long ledgerBefore = ledgerCount(walletB);

        mockMvc.perform(post("/api/v1/wallets/{id}/topup", walletB)
                        .header("Authorization", bearerToken(userA))
                        .header("Idempotency-Key", "cross-user-topup-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amountPaise": 1000}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        assertBUnchanged(walletB, balanceBefore, ledgerBefore);
    }

    @Test
    void userACannotWithdrawFromUserBWallet() throws Exception {
        String userA = registerUser();
        String userB = registerUser();
        String walletB = createWallet(userB);
        topUp(userB, walletB, INITIAL_BALANCE_PAise);

        long balanceBefore = balance(walletB);
        long ledgerBefore = ledgerCount(walletB);

        mockMvc.perform(post("/api/v1/wallets/{id}/withdraw", walletB)
                        .header("Authorization", bearerToken(userA))
                        .header("Idempotency-Key", "cross-user-withdraw-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amountPaise": 1000}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        assertBUnchanged(walletB, balanceBefore, ledgerBefore);
    }

    @Test
    void userACannotReverseUserBTransaction() throws Exception {
        String userA = registerUser();
        String userB = registerUser();
        String walletB = createWallet(userB);
        topUp(userB, walletB, INITIAL_BALANCE_PAise);

        // Create a real completed transaction owned by User B.
        String response = mockMvc.perform(post("/api/v1/wallets/{id}/topup", walletB)
                        .header("Authorization", bearerToken(userB))
                        .header("Idempotency-Key", "reversible-topup-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amountPaise": 500}
                                """))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String transactionId = jsonMapper.readTree(response)
                .get("transactionId").asText();

        long balanceBefore = balance(walletB);
        long ledgerBefore = ledgerCount(walletB);

        mockMvc.perform(post("/api/v1/transactions/{id}/reverse", transactionId)
                        .header("Authorization", bearerToken(userA))
                        .header("Idempotency-Key", "cross-user-reversal-" + UUID.randomUUID()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertBUnchanged(walletB, balanceBefore, ledgerBefore);
    }
}
