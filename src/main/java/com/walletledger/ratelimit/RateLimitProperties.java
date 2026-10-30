package com.walletledger.ratelimit;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "walletledger.rate-limit")
public record RateLimitProperties(Bucket login, Bucket moneyMovement) {

    public record Bucket(
            long capacity,
            long refillTokens,
            Duration refillDuration) {
    }
}
