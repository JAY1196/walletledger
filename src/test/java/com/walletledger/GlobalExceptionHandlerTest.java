package com.walletledger;

import com.walletledger.exception.ErrorCode;
import com.walletledger.ratelimit.RateLimitExceededException;
import com.walletledger.exception.GlobalExceptionHandler;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
        request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/wallets/test/withdraw");
    }

    @Test
    void databaseLockContentionReturnsServiceUnavailable() {
        CannotAcquireLockException exception =
                new CannotAcquireLockException("Database deadlock");

        ResponseEntity<ProblemDetail> response =
                handler.handleDatabaseContention(exception, request);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(
                ErrorCode.SERVICE_UNAVAILABLE.name(),
                response.getBody().getProperties().get("code"));
        assertEquals(
                request.getRequestURI(),
                response.getBody().getInstance().toString());
    }

    @Test
    void databaseResourceFailureReturnsServiceUnavailable() {
        DataAccessResourceFailureException exception =
                new DataAccessResourceFailureException("Database unavailable");

        ResponseEntity<ProblemDetail> response =
                handler.handleDatabaseContention(exception, request);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(
                ErrorCode.SERVICE_UNAVAILABLE.name(),
                response.getBody().getProperties().get("code"));
    }

    @Test
    void rateLimitReturnsTooManyRequestsAndRetryAfterHeader() {
        RateLimitExceededException exception =
                new RateLimitExceededException(7);

        ResponseEntity<ProblemDetail> response =
                handler.handleApiException(exception, request);

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatusCode());
        assertEquals("7", response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
        assertNotNull(response.getBody());
        assertEquals(
                ErrorCode.RATE_LIMITED.name(),
                response.getBody().getProperties().get("code"));
    }
}
