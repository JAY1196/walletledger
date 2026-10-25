package com.walletledger;

import com.walletledger.service.JwtService;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtServiceTest {

    @Test
    void expiredTokenIsRejected() {
        SecretKey key = Jwts.SIG.HS256.key().build();
        JwtService jwtService = new JwtService(key, 900);

        Instant now = Instant.now();
        String expiredToken = Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .issuedAt(Date.from(now.minusSeconds(120)))
                .expiration(Date.from(now.minusSeconds(60)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();

        assertThrows(
                JwtService.InvalidTokenException.class,
                () -> jwtService.validateAndGetUserId(expiredToken));
    }
}
