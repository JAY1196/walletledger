package com.walletledger;

import com.walletledger.entity.User;
import com.walletledger.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class SystemUserAuthTest {

    private static final UUID SYSTEM_USER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static final String SYSTEM_EMAIL =
            "system-bank@walletledger.internal";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Test
    void seededSystemBankHasFixedIdentityAndNoPasswordHash() {
        User systemUser = userRepository.findByEmail(SYSTEM_EMAIL)
                .orElseThrow(() -> new AssertionError(
                        "The seeded system-bank user was not found."));

        assertEquals(SYSTEM_USER_ID, systemUser.getId());
        assertEquals("WalletLedger System Bank", systemUser.getName());
        assertNull(systemUser.getPasswordHash(),
                "The system-bank account must not have a login password hash.");
    }

    @Test
    void passwordlessSystemAccountCannotLogin() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "%s",
                                  "password": "ValidPassword123!"
                                }
                                """.formatted(SYSTEM_EMAIL)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.detail")
                        .value("Invalid email or password."));
    }

    @Test
    void systemBankEmailCannotBeClaimedThroughRegistration()
            throws Exception {
        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Attempted System Account Claim",
                                  "email": "%s",
                                  "password": "ValidPassword123!"
                                }
                                """.formatted(SYSTEM_EMAIL)))
                .andExpect(status().isConflict());

        User systemUser = userRepository.findByEmail(SYSTEM_EMAIL)
                .orElseThrow(() -> new AssertionError(
                        "The seeded system-bank user disappeared."));

        assertEquals(SYSTEM_USER_ID, systemUser.getId());
        assertNull(systemUser.getPasswordHash(),
                "Registration must not assign a password to the system account.");
    }
}