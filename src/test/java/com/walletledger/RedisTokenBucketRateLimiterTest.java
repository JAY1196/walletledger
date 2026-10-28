package com.walletledger;

import com.walletledger.ratelimit.RateLimitProperties;
import com.walletledger.ratelimit.RedisTokenBucketRateLimiter;

import java.time.Duration;
import java.util.UUID;

import com.walletledger.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class RedisTokenBucketRateLimiterTest {

    @Autowired
    private RedisTokenBucketRateLimiter limiter;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    void allowsRequestsUpToCapacityThenRejectsExcessRequest() {
        String key = "walletledger:test:rate:" + UUID.randomUUID();

        RateLimitProperties.Bucket bucket =
                new RateLimitProperties.Bucket(
                        2, 1, Duration.ofMinutes(1));

        try {
            var first = limiter.tryAcquire(key, bucket);
            var second = limiter.tryAcquire(key, bucket);
            var third = limiter.tryAcquire(key, bucket);

            assertTrue(first.allowed(), "First request should be allowed");
            assertTrue(second.allowed(), "Second request should be allowed");
            assertFalse(third.allowed(), "Third request should be rejected");
            assertTrue(third.retryAfterSeconds() >= 1);
        } finally {
            redisTemplate.delete(key);
        }
    }
}
