package com.walletledger.config;

import com.walletledger.context.XUserIdInterceptor;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final XUserIdInterceptor xUserIdInterceptor;

    public WebConfig() {
        this.xUserIdInterceptor = new XUserIdInterceptor();
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(xUserIdInterceptor)
                .addPathPatterns("/api/**");
    }
}