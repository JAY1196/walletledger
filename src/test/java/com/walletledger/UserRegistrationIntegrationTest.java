
package com.walletledger;

import com.walletledger.service.LedgerInvariantChecker;
import com.walletledger.entity.User;
import com.walletledger.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;

import org.junit.jupiter.api.Test;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class UserRegistrationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LedgerInvariantChecker ledgerInvariantChecker;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void registersUserWithoutUserIdHeader() throws Exception {
        String email = "registration-" + java.util.UUID.randomUUID()
                + "@walletledger.test";

        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Integration Test User","email":"%s","password":"ValidPassword123!"}
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.name").value("Integration Test User"))
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.createdAt").isNotEmpty());
    }


    @Test
    void registrationStoresOnlyBcryptPasswordHash() throws Exception {
        String email = "password-hash-" + java.util.UUID.randomUUID()
                + "@walletledger.test";
        String password = "ValidPassword123!";

        String response = mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Password Hash Test","email":"%s","password":"%s"}
                                """.formatted(email, password)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andReturn()
                .getResponse()
                .getContentAsString();

        User savedUser = userRepository.findAll().stream()
                .filter(user -> email.equals(user.getEmail()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Registered user was not persisted"));

        String storedHash = savedUser.getPasswordHash();

        assertTrue(storedHash != null && !storedHash.isBlank(),
                "Password hash should be stored");
        assertTrue(!password.equals(storedHash),
                "Raw password must not be stored");
        assertTrue(passwordEncoder.matches(password, storedHash),
                "Stored hash should verify the original password");
        assertTrue(!response.contains(password),
                "Registration response must not contain the raw password");
        assertTrue(!response.contains(storedHash),
                "Registration response must not contain the password hash");
    }
    @Test
    void duplicateEmailReturnsConflict() throws Exception {
        String email = "duplicate-" + java.util.UUID.randomUUID()
                + "@walletledger.test";

        String requestBody = """
                {"name":"Duplicate Test User","email":"%s","password":"ValidPassword123!"}
                """.formatted(email);

        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void invalidEmailReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Invalid Email User","email":"not-an-email","password":"ValidPassword123!"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void malformedBearerTokenReturnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/wallets/00000000-0000-0000-0000-000000000001/balance")
                        .header("Authorization", "Bearer not-a-valid-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }
    @Test
    void protectedWalletEndpointRequiresBearerToken() throws Exception {
        mockMvc.perform(get("/api/v1/wallets/00000000-0000-0000-0000-000000000001/balance"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void blankNameReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"   ","email":"blank-name@walletledger.test","password":"ValidPassword123!"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void missingEmailReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Missing Email User"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void registrationTrimsNameAndNormalizesEmail() throws Exception {
        String email = "normalize-" + java.util.UUID.randomUUID()
                + "@walletledger.test";

        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"  Normalization User  ","email":"%s","password":"ValidPassword123!"}
                                """.formatted(email.toUpperCase(java.util.Locale.ROOT))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Normalization User"))
                .andExpect(jsonPath("$.email").value(email));
    }

    @Test
    void concurrentSameEmailRegistrationCreatesExactlyOneUser() throws Exception {
        String email = "concurrent-" + java.util.UUID.randomUUID()
                + "@walletledger.test";

        String requestBody = """
                {"name":"Concurrent Registration User","email":"%s","password":"ValidPassword123!"}
                """.formatted(email);

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
                                    post("/api/v1/users")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(requestBody)
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
                            "Unexpected registration HTTP status: " + status
                    );
                }
            }

            assertEquals(1, created, "Exactly one registration should succeed");
            assertEquals(19, conflicts, "The other registrations should conflict");
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(
                    executor.awaitTermination(10, TimeUnit.SECONDS),
                    "Worker threads should terminate"
            );
        }
        ledgerInvariantChecker.assertNoViolations();
    }
}