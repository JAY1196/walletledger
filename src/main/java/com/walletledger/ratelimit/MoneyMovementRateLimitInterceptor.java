package com.walletledger.ratelimit;

import com.walletledger.context.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class MoneyMovementRateLimitInterceptor implements HandlerInterceptor {

    private final RateLimitService rateLimitService;

    public MoneyMovementRateLimitInterceptor(RateLimitService rateLimitService) {
        this.rateLimitService = rateLimitService;
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler) {

        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String key = "walletledger:rate:money:"
                + UserContext.getRequiredUserId(request);

        RedisTokenBucketRateLimiter.Decision decision =
                rateLimitService.checkMoneyMovement(key);

        if (!decision.allowed()) {
            throw new RateLimitExceededException(decision.retryAfterSeconds());
        }

        return true;
    }
}
