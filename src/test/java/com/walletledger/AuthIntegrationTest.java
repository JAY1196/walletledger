package com.walletledger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AuthIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private String registerAndLogin(String email, String password)
            throws Exception {

        String registrationJson =
                "{\"name\":\"JWT Test User\",\"email\":\"" + email
                + "\",\"password\":\"" + password + "\"}";

        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registrationJson))
                .andExpect(status().isCreated());

        String loginJson =
                "{\"email\":\"" + email + "\",\"password\":\""
                + password + "\"}";

        String response = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").isNumber())
                .andExpect(jsonPath("$.userId").isNotEmpty())
                .andReturn()
                .getResponse()
                .getContentAsString();

        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\"accessToken\"\\s*:\\s*\"([^\"]+)\"")
                .matcher(response);

        if (!matcher.find()) {
            throw new AssertionError("Login response did not contain an access token.");
        }

        return matcher.group(1);
    }

    @Test
    void loginReturnsBearerTokenAndTokenAuthenticatesRequest()
            throws Exception {

        String email = "jwt-" + java.util.UUID.randomUUID()
                + "@walletledger.test";

        String token = registerAndLogin(email, "ValidPassword123!");

        assertNotNull(token);
        assertFalse(token.isBlank());

        mockMvc.perform(post("/api/v1/wallets")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated());
    }

    @Test
    void wrongPasswordReturnsUnauthorized() throws Exception {
        String email = "wrong-password-" + java.util.UUID.randomUUID()
                + "@walletledger.test";

        String registrationJson =
                "{\"name\":\"JWT Test User\",\"email\":\"" + email
                + "\",\"password\":\"ValidPassword123!\"}";

        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registrationJson))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email
                                + "\",\"password\":\"WrongPassword123!\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail")
                        .value("Invalid email or password."));
    }

    @Test
    void unknownEmailReturnsSameGenericUnauthorizedResponse()
            throws Exception {

        String email = "unknown-" + java.util.UUID.randomUUID()
                + "@walletledger.test";

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email
                                + "\",\"password\":\"WrongPassword123!\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail")
                        .value("Invalid email or password."));
    }

    @Test
    void passwordlessSystemAccountCannotLogin() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"system-bank@walletledger.internal\","
                                + "\"password\":\"ValidPassword123!\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail")
                        .value("Invalid email or password."));
    }
    @Test
    void missingBearerTokenReturnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/wallets/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void malformedBearerTokenReturnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/wallets/me")
                        .header("Authorization", "Bearer not-a-valid-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void xUserIdCannotAuthenticateARequest() throws Exception {
        mockMvc.perform(get("/api/v1/wallets/me")
                        .header("X-User-Id",
                                java.util.UUID.randomUUID().toString()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }
}