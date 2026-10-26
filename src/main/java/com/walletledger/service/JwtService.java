package com.walletledger.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtService {

    private final SecretKey signingKey;
    private final long expirationSeconds;

    public JwtService(
            SecretKey jwtSigningKey,
            @Value("${walletledger.security.jwt.expiration-seconds:900}")
            long expirationSeconds) {

        if (expirationSeconds <= 0) {
            throw new IllegalArgumentException(
                    "JWT expiration must be greater than zero.");
        }

        this.signingKey = jwtSigningKey;
        this.expirationSeconds = expirationSeconds;
    }

    public String generateToken(UUID userId) {
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(expirationSeconds);

        return Jwts.builder()
                .subject(userId.toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }

    public UUID validateAndGetUserId(String token) {
        if (token == null || token.isBlank()) {
            throw new InvalidTokenException();
        }

        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            String subject = claims.getSubject();

            if (subject == null || subject.isBlank()) {
                throw new InvalidTokenException();
            }

            return UUID.fromString(subject);
        } catch (JwtException | IllegalArgumentException exception) {
            throw new InvalidTokenException();
        }
    }

    public static final class InvalidTokenException
            extends RuntimeException {

        public InvalidTokenException() {
            super("The bearer token is invalid or expired.");
        }
    }

    public long getExpirationSeconds() {
        return expirationSeconds;
    }
}