package com.walletledger.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class RateLimitService {

    private static final Logger log =
            LoggerFactory.getLogger(RateLimitService.class);

    private final RedisTokenBucketRateLimiter limiter;
    private final RateLimitProperties properties;

    public RateLimitService(
            RedisTokenBucketRateLimiter limiter,
            RateLimitProperties properties) {
        this.limiter = limiter;
        this.properties = properties;
    }

    public RedisTokenBucketRateLimiter.Decision checkLogin(String key) {
        return check(key, properties.login());
    }

    public RedisTokenBucketRateLimiter.Decision checkMoneyMovement(String key) {
        return check(key, properties.moneyMovement());
    }

    private RedisTokenBucketRateLimiter.Decision check(
            String key,
            RateLimitProperties.Bucket bucket) {
        try {
            return limiter.tryAcquire(key, bucket);
        } catch (org.springframework.dao.DataAccessException ex) {
            log.warn("Redis rate limiting unavailable; allowing request.");
            return new RedisTokenBucketRateLimiter.Decision(true, 0);
        }
    }
}
