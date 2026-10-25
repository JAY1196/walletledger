package com.walletledger;

import com.walletledger.config.JwtConfig;
import com.walletledger.service.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AuthSecurityIntegrationTest {

    private static final String PROTECTED_PATH = "/api/v1/wallets/me";

    /*
     * Test-only secret from src/test/resources/application-test.yaml.
     * Never use this key outside tests.
     */
    private static final String TEST_SECRET_BASE64 =
            "dGVzdC1vbmx5LWtleS1mb3Itd2FsbGV0bGVkZ2VyLTIwMjYh";

    private static final SecretKey TEST_KEY =
            Keys.hmacShaKeyFor(Decoders.BASE64.decode(TEST_SECRET_BASE64));

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void rejectsMissingToken() throws Exception {
        assertUnauthorized(null);
    }

    @Test
    void rejectsMalformedToken() throws Exception {
        assertUnauthorized("not.a.valid.jwt");
    }

    @Test
    void rejectsExpiredToken() throws Exception {
        Instant now = Instant.now();

        String token = Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .issuedAt(Date.from(now.minusSeconds(120)))
                .expiration(Date.from(now.minusSeconds(60)))
                .signWith(TEST_KEY)
                .compact();

        assertUnauthorized(token);
    }

    @Test
    void rejectsTokenSignedWithDifferentSecret() throws Exception {
        SecretKey differentKey = Keys.hmacShaKeyFor(
                "another-test-signing-key-that-is-long-enough"
                        .getBytes(StandardCharsets.UTF_8));

        String token = Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .issuedAt(new Date())
                .expiration(Date.from(Instant.now().plusSeconds(300)))
                .signWith(differentKey)
                .compact();

        assertUnauthorized(token);
    }

    @Test
    void rejectsAlgNoneToken() throws Exception {
        String header = base64Url("{\"alg\":\"none\",\"typ\":\"JWT\"}");
        String payload = base64Url(
                "{\"sub\":\"" + UUID.randomUUID()
                        + "\",\"exp\":" + (Instant.now().getEpochSecond() + 300)
                        + "}");

        assertUnauthorized(header + "." + payload + ".");
    }

    @Test
    void rejectsTokenForNonexistentUser() throws Exception {
        UUID nonexistentUserId = UUID.randomUUID();

        assertFalse(userExists(nonexistentUserId),
                "The generated test UUID must not belong to a stored user.");

        assertUnauthorized(validToken(nonexistentUserId.toString()));
    }

    @Test
    void rejectsTokenWithMalformedSubject() throws Exception {
        assertUnauthorized(validToken("not-a-uuid"));
    }

    private void assertUnauthorized(String token) throws Exception {
        var request = get(PROTECTED_PATH);

        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }

        MvcResult result = mockMvc.perform(request).andReturn();

        assertEquals(401, result.getResponse().getStatus());

        String contentType = result.getResponse().getContentType();
        assertNotNull(contentType, "401 response must have a content type.");
        assertTrue(
                MediaType.APPLICATION_PROBLEM_JSON.isCompatibleWith(
                        MediaType.parseMediaType(contentType)),
                "Expected application/problem+json but received " + contentType);

        String body = result.getResponse().getContentAsString();
        JsonNode json = MAPPER.readTree(body);

        assertEquals("UNAUTHENTICATED", json.path("code").asText(),
                "All invalid bearer tokens should use the stable auth error code.");

        assertFalse(json.has("trace"));
        assertFalse(json.has("stackTrace"));
        assertFalse(body.contains("java.lang."));
        assertFalse(body.contains("\tat "));
        assertFalse(body.contains("org.springframework."));
    }

    private String validToken(String subject) {
        Instant now = Instant.now();

        return Jwts.builder()
                .subject(subject)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(300)))
                .signWith(TEST_KEY)
                .compact();
    }

    private boolean userExists(UUID userId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE id = UUID_TO_BIN(?)",
                Integer.class,
                userId.toString());

        return count != null && count > 0;
    }

    private static String base64Url(String value) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
