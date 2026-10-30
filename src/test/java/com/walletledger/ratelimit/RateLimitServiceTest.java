package com.walletledger.ratelimit;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RateLimitServiceTest {

    @Test
    void allowsRequestWhenRedisIsUnavailable() {
        RedisTokenBucketRateLimiter limiter =
                mock(RedisTokenBucketRateLimiter.class);

        RateLimitProperties.Bucket loginBucket =
                new RateLimitProperties.Bucket(10, 10, Duration.ofMinutes(1));

        RateLimitProperties.Bucket moneyBucket =
                new RateLimitProperties.Bucket(30, 30, Duration.ofMinutes(1));

        RateLimitProperties properties =
                new RateLimitProperties(loginBucket, moneyBucket);

        when(limiter.tryAcquire("test-key", loginBucket))
                .thenThrow(new DataAccessResourceFailureException(
                        "Redis unavailable"));

        RateLimitService service = new RateLimitService(limiter, properties);

        RedisTokenBucketRateLimiter.Decision decision =
                service.checkLogin("test-key");

        assertTrue(decision.allowed());
        assertEquals(0, decision.retryAfterSeconds());
    }

    @Test
    void allowsMoneyMovementWhenRedisIsUnavailable() {
        RedisTokenBucketRateLimiter limiter =
                mock(RedisTokenBucketRateLimiter.class);

        RateLimitProperties.Bucket loginBucket =
                new RateLimitProperties.Bucket(10, 10, Duration.ofMinutes(1));

        RateLimitProperties.Bucket moneyBucket =
                new RateLimitProperties.Bucket(30, 30, Duration.ofMinutes(1));

        RateLimitProperties properties =
                new RateLimitProperties(loginBucket, moneyBucket);

        when(limiter.tryAcquire("money-test-key", moneyBucket))
                .thenThrow(new DataAccessResourceFailureException(
                        "Redis unavailable"));

        RateLimitService service = new RateLimitService(limiter, properties);

        RedisTokenBucketRateLimiter.Decision decision =
                service.checkMoneyMovement("money-test-key");

        assertTrue(decision.allowed());
        assertEquals(0, decision.retryAfterSeconds());
    }
}
