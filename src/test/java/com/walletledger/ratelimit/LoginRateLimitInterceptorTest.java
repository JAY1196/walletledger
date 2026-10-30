package com.walletledger.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LoginRateLimitInterceptorTest {

    private RateLimitService service;
    private LoginRateLimitInterceptor interceptor;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        service = mock(RateLimitService.class);
        interceptor = new LoginRateLimitInterceptor(service);
        request = new MockHttpServletRequest();
        request.setMethod("POST");
        request.setRemoteAddr("192.0.2.10");
        response = new MockHttpServletResponse();
    }

    @Test
    void allowsPostWhenRateLimitAllowsIt() {
        when(service.checkLogin("walletledger:rate:login:192.0.2.10"))
                .thenReturn(new RedisTokenBucketRateLimiter.Decision(true, 0));

        assertTrue(interceptor.preHandle(request, response, new Object()));

        verify(service).checkLogin("walletledger:rate:login:192.0.2.10");
    }

    @Test
    void rejectsPostWhenRateLimitIsExceeded() {
        when(service.checkLogin("walletledger:rate:login:192.0.2.10"))
                .thenReturn(new RedisTokenBucketRateLimiter.Decision(false, 5));

        RateLimitExceededException exception = assertThrows(
                RateLimitExceededException.class,
                () -> interceptor.preHandle(request, response, new Object()));

        org.junit.jupiter.api.Assertions.assertEquals(
                5, exception.getRetryAfterSeconds());
    }

    @Test
    void skipsRateLimitForNonPostRequests() {
        request.setMethod("GET");

        assertTrue(interceptor.preHandle(request, response, new Object()));

        org.mockito.Mockito.verifyNoInteractions(service);
    }
}
