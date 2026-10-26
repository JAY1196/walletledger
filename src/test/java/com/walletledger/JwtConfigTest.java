package com.walletledger;

import com.walletledger.config.JwtConfig;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class JwtConfigTest {

    private final JwtConfig jwtConfig = new JwtConfig();

    @Test
    void rejectsNullSecret() {
        assertThrows(IllegalStateException.class,
                () -> jwtConfig.jwtSigningKey(null));
    }

    @Test
    void rejectsBlankSecret() {
        assertThrows(IllegalStateException.class,
                () -> jwtConfig.jwtSigningKey("   "));
    }

    @Test
    void rejectsInvalidBase64() {
        assertThrows(IllegalStateException.class,
                () -> jwtConfig.jwtSigningKey("%%%not-base64%%%"));
    }

    @Test
    void rejectsSecretShorterThan32DecodedBytes() {
        String secret = Base64.getEncoder().encodeToString(
                "short-key".getBytes(StandardCharsets.UTF_8));

        assertThrows(IllegalStateException.class,
                () -> jwtConfig.jwtSigningKey(secret));
    }

    @Test
    void rejectsKnownDefaultSecret() {
        String secret = Base64.getEncoder().encodeToString(
                "ThisIsADefaultJWTSecretKeyForWalletLedger2026!"
                        .getBytes(StandardCharsets.UTF_8));

        assertThrows(IllegalStateException.class,
                () -> jwtConfig.jwtSigningKey(secret));
    }

    @Test
    void rejectsPlaceholderSecret() {
        String secret = Base64.getEncoder().encodeToString(
                "replace-this-with-a-secure-random-secret-key!"
                        .getBytes(StandardCharsets.UTF_8));

        assertThrows(IllegalStateException.class,
                () -> jwtConfig.jwtSigningKey(secret));
    }

    @Test
    void acceptsValidBase64EncodedSecret() {
        byte[] keyBytes = new byte[32];

        // Deterministic test fixture only; never use it in production.
        for (int i = 0; i < keyBytes.length; i++) {
            keyBytes[i] = (byte) (i + 1);
        }

        String secret = Base64.getEncoder().encodeToString(keyBytes);

        SecretKey key = assertDoesNotThrow(
                () -> jwtConfig.jwtSigningKey(secret));

        assertNotNull(key);
        assertEquals(32, key.getEncoded().length);
    }
}
