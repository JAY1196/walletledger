package com.walletledger.context;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.servlet.HandlerInterceptor;

import com.walletledger.exception.ApiException;
import com.walletledger.exception.ErrorCode;

public class XUserIdInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler) {

        if ("POST".equalsIgnoreCase(request.getMethod())
                && "/api/v1/users".equals(request.getRequestURI())) {
            return true;
        }

        String rawUserId = request.getHeader("X-User-Id");

        if (rawUserId == null || rawUserId.isBlank()) {
            throw new ApiException(
                    ErrorCode.UNAUTHENTICATED,
                    "The X-User-Id header is required."
            );
        }

        final UUID userId;

        try {
            userId = UUID.fromString(rawUserId.trim());
        } catch (IllegalArgumentException ex) {
            throw new ApiException(
                    ErrorCode.BAD_REQUEST,
                    "The X-User-Id header must contain a valid UUID."
            );
        }

        UserContext.setUserId(request, userId);
        return true;
    }
}