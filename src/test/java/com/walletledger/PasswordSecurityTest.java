package com.walletledger;

import com.walletledger.entity.User;
import com.walletledger.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class PasswordSecurityTest {

    private static final String VALID_PASSWORD = "ValidPassword123!";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void registrationStoresBcryptHashAndDoesNotExposeCredentials()
            throws Exception {
        String email = uniqueEmail("password-hash");
        String responseBody = register(email, VALID_PASSWORD)
                .getResponse()
                .getContentAsString();

        User savedUser = userRepository.findByEmail(email)
                .orElseThrow(() -> new AssertionError(
                        "Registered user was not persisted."));

        String storedHash = savedUser.getPasswordHash();

        assertNotNull(storedHash, "Password hash must be stored.");
        assertNotEquals(VALID_PASSWORD, storedHash,
                "The raw password must never be stored.");
        assertTrue(storedHash.startsWith("$2"),
                "The stored password must use BCrypt format.");
        assertEquals(60, storedHash.length(),
                "A BCrypt password hash should be 60 characters.");
        assertTrue(passwordEncoder.matches(VALID_PASSWORD, storedHash),
                "The stored hash must verify the original password.");

        assertFalse(responseBody.contains(VALID_PASSWORD),
                "The registration response must not expose the raw password.");
        assertFalse(responseBody.contains(storedHash),
                "The registration response must not expose the password hash.");
        assertFalse(responseBody.contains("passwordHash"),
                "The response must not contain a passwordHash field.");
    }

    @Test
    void samePasswordProducesDifferentBcryptHashesForDifferentUsers()
            throws Exception {
        String firstEmail = uniqueEmail("password-salt-a");
        String secondEmail = uniqueEmail("password-salt-b");

        register(firstEmail, VALID_PASSWORD);
        register(secondEmail, VALID_PASSWORD);

        User firstUser = userRepository.findByEmail(firstEmail)
                .orElseThrow(() -> new AssertionError(
                        "First registered user was not persisted."));

        User secondUser = userRepository.findByEmail(secondEmail)
                .orElseThrow(() -> new AssertionError(
                        "Second registered user was not persisted."));

        assertNotNull(firstUser.getPasswordHash());
        assertNotNull(secondUser.getPasswordHash());

        assertNotEquals(firstUser.getPasswordHash(),
                secondUser.getPasswordHash(),
                "BCrypt should generate different salted hashes.");

        assertTrue(passwordEncoder.matches(
                VALID_PASSWORD, firstUser.getPasswordHash()));
        assertTrue(passwordEncoder.matches(
                VALID_PASSWORD, secondUser.getPasswordHash()));
    }

    @Test
    void rejectsPasswordShorterThanMinimumLength() throws Exception {
        String email = uniqueEmail("password-too-short");

        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Short Password Test",
                                  "email": "%s",
                                  "password": "short"
                                }
                                """.formatted(email)))
                .andExpect(status().isBadRequest());

        assertTrue(userRepository.findByEmail(email).isEmpty(),
                "A user with an invalid password must not be created.");
    }

    @Test
    void rejectsPasswordOver72Utf8BytesEvenWhenCharacterCountIsBelow72()
            throws Exception {
        String email = uniqueEmail("password-utf8-limit");
        String password = "é".repeat(37);

        assertTrue(password.length() < 72);
        assertEquals(74, password.getBytes(StandardCharsets.UTF_8).length);

        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "UTF-8 Password Test",
                                  "email": "%s",
                                  "password": "%s"
                                }
                                """.formatted(email, password)))
                .andExpect(status().isBadRequest());

        assertTrue(userRepository.findByEmail(email).isEmpty(),
                "A password exceeding the UTF-8 byte limit must not create a user.");
    }

    private MvcResult register(String email, String password)
            throws Exception {
        return mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Password Security Test",
                                  "email": "%s",
                                  "password": "%s"
                                }
                                """.formatted(email, password)))
                .andExpect(status().isCreated())
                .andReturn();
    }

    private String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@walletledger.test";
    }
}