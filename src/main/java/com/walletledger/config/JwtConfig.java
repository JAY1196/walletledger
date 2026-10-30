package com.walletledger.config;

import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

@Configuration
public class JwtConfig {

    @Bean
    public SecretKey jwtSigningKey(
            @Value("${walletledger.security.jwt.secret:${WALLETLEDGER_SECURITY_JWT_SECRET}}") String secret) {

        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "JWT signing secret must be configured.");
        }

        final byte[] keyBytes;

        try {
            keyBytes = Decoders.BASE64.decode(secret);
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                    "JWT signing secret must be valid Base64.", exception);
        }

        if (keyBytes.length < 32) {
            throw new IllegalStateException(
                    "JWT signing secret must contain at least 32 decoded bytes.");
        }

        String decodedSecret = new String(
                keyBytes, StandardCharsets.UTF_8);

        if (isKnownUnsafeSecret(secret)
                || isKnownUnsafeSecret(decodedSecret)) {
            throw new IllegalStateException(
                    "JWT signing secret must not be a known default or placeholder.");
        }

        return Keys.hmacShaKeyFor(keyBytes);
    }

    private boolean isKnownUnsafeSecret(String value) {
        String normalized = value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]", "");

        return normalized.contains("default")
                || normalized.contains("changeme")
                || normalized.contains("replace")
                || normalized.contains("placeholder")
                || normalized.contains("yoursecret")
                || normalized.contains("example")
                || normalized.contains("sample")
                || normalized.contains("insecure");
    }
}
