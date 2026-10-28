package com.walletledger.ratelimit;

import java.util.List;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public class RedisTokenBucketRateLimiter {

    private static final DefaultRedisScript<List> SCRIPT;

    static {
        SCRIPT = new DefaultRedisScript<>();
        SCRIPT.setResultType(List.class);
        SCRIPT.setScriptText("""
            local capacity = tonumber(ARGV[1])
            local refillTokens = tonumber(ARGV[2])
            local refillPeriod = tonumber(ARGV[3])
            local ttlSeconds = tonumber(ARGV[4])

            local time = redis.call('TIME')
            local now = tonumber(time[1]) * 1000
                + math.floor(tonumber(time[2]) / 1000)

            local values = redis.call(
                'HMGET', KEYS[1], 'tokens', 'updated'
            )

            local tokens = tonumber(values[1]) or capacity
            local updated = tonumber(values[2]) or now

            local elapsed = math.max(0, now - updated)
            tokens = math.min(
                capacity,
                tokens + elapsed * refillTokens / refillPeriod
            )

            local allowed = 0
            local retrySeconds = 0

            if tokens >= 1 then
                tokens = tokens - 1
                allowed = 1
            else
                retrySeconds = math.ceil(
                    (1 - tokens) * refillPeriod
                    / refillTokens / 1000
                )
            end

            redis.call(
                'HSET', KEYS[1],
                'tokens', tostring(tokens),
                'updated', tostring(now)
            )
            redis.call('EXPIRE', KEYS[1], ttlSeconds)

            return {allowed, retrySeconds}
            """);
    }

    private final StringRedisTemplate redisTemplate;

    public RedisTokenBucketRateLimiter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public Decision tryAcquire(
            String key,
            RateLimitProperties.Bucket bucket) {

        if (bucket == null
                || bucket.capacity() < 1
                || bucket.refillTokens() < 1
                || bucket.refillDuration() == null
                || bucket.refillDuration().isZero()
                || bucket.refillDuration().isNegative()) {
            throw new IllegalStateException(
                    "Rate-limit bucket configuration is invalid.");
        }

        long refillMillis = bucket.refillDuration().toMillis();
        long ttlSeconds = Math.max(
                1,
                (long) Math.ceil(
                        2.0 * refillMillis / 1000.0
                        * bucket.capacity() / bucket.refillTokens()));

        List<?> result = redisTemplate.execute(
                SCRIPT,
                List.of(key),
                Long.toString(bucket.capacity()),
                Long.toString(bucket.refillTokens()),
                Long.toString(refillMillis),
                Long.toString(ttlSeconds));

        if (result == null || result.size() < 2) {
            throw new IllegalStateException(
                    "Redis returned an invalid rate-limit decision.");
        }

        boolean allowed = Long.parseLong(
                String.valueOf(result.get(0))) == 1;
        long retrySeconds = Long.parseLong(
                String.valueOf(result.get(1)));

        return new Decision(allowed, retrySeconds);
    }

    public record Decision(boolean allowed, long retryAfterSeconds) {
    }
}
