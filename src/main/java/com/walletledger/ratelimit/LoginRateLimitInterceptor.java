package com.walletledger.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class LoginRateLimitInterceptor implements HandlerInterceptor {

    private final RateLimitService rateLimitService;

    public LoginRateLimitInterceptor(RateLimitService rateLimitService) {
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

        String key = "walletledger:rate:login:" + request.getRemoteAddr();
        RedisTokenBucketRateLimiter.Decision decision =
                rateLimitService.checkLogin(key);

        if (!decision.allowed()) {
            throw new RateLimitExceededException(decision.retryAfterSeconds());
        }

        return true;
    }
}
