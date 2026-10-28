package com.walletledger.config;

import com.walletledger.context.JwtAuthInterceptor;
import com.walletledger.ratelimit.LoginRateLimitInterceptor;
import com.walletledger.ratelimit.MoneyMovementRateLimitInterceptor;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final JwtAuthInterceptor jwtAuthInterceptor;
    private final LoginRateLimitInterceptor loginRateLimitInterceptor;
    private final MoneyMovementRateLimitInterceptor moneyMovementRateLimitInterceptor;

    public WebConfig(
            JwtAuthInterceptor jwtAuthInterceptor,
            LoginRateLimitInterceptor loginRateLimitInterceptor,
            MoneyMovementRateLimitInterceptor moneyMovementRateLimitInterceptor) {
        this.jwtAuthInterceptor = jwtAuthInterceptor;
        this.loginRateLimitInterceptor = loginRateLimitInterceptor;
        this.moneyMovementRateLimitInterceptor = moneyMovementRateLimitInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(loginRateLimitInterceptor)
                .addPathPatterns("/api/v1/auth/login");

        registry.addInterceptor(jwtAuthInterceptor)
                .addPathPatterns("/api/**");

        registry.addInterceptor(moneyMovementRateLimitInterceptor)
                .addPathPatterns(
                        "/api/v1/wallets/*/topup",
                        "/api/v1/wallets/*/withdraw",
                        "/api/v1/transfers",
                        "/api/v1/transactions/*/reverse");
    }
}
