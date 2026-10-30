package com.walletledger;

import com.walletledger.ratelimit.RateLimitProperties;
import com.walletledger.ratelimit.RedisTokenBucketRateLimiter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        String key = newKey();
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

    @Test
    void refillsTokenAfterConfiguredInterval() throws InterruptedException {
        String key = newKey();
        RateLimitProperties.Bucket bucket =
                new RateLimitProperties.Bucket(
                        1, 1, Duration.ofSeconds(1));

        try {
            assertTrue(limiter.tryAcquire(key, bucket).allowed());
            assertFalse(limiter.tryAcquire(key, bucket).allowed());

            Thread.sleep(1_200);

            assertTrue(
                    limiter.tryAcquire(key, bucket).allowed(),
                    "A token should be available after the refill interval");
        } finally {
            redisTemplate.delete(key);
        }
    }

    @Test
    void fiftyParallelRequestsAllowExactlyBucketCapacity()
            throws Exception {
        String key = newKey();
        int requestCount = 50;
        int capacity = 13;

        RateLimitProperties.Bucket bucket =
                new RateLimitProperties.Bucket(
                        capacity, 1, Duration.ofMinutes(1));

        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<RedisTokenBucketRateLimiter.Decision>> futures =
                new ArrayList<>();

        try {
            for (int i = 0; i < requestCount; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();

                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException(
                                "Timed out waiting to start concurrent request");
                    }

                    return limiter.tryAcquire(key, bucket);
                }));
            }

            assertTrue(
                    ready.await(10, TimeUnit.SECONDS),
                    "All workers should be ready");

            start.countDown();

            int allowed = 0;
            int rejected = 0;

            for (Future<RedisTokenBucketRateLimiter.Decision> future : futures) {
                if (future.get(15, TimeUnit.SECONDS).allowed()) {
                    allowed++;
                } else {
                    rejected++;
                }
            }

            assertEquals(capacity, allowed);
            assertEquals(requestCount - capacity, rejected);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(
                    executor.awaitTermination(5, TimeUnit.SECONDS),
                    "Worker threads should terminate");
            redisTemplate.delete(key);
        }
    }

    private String newKey() {
        return "walletledger:test:rate:" + UUID.randomUUID();
    }
}
