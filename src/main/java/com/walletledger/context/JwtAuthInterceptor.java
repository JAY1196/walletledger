package com.walletledger.context;

import com.walletledger.exception.ApiException;
import com.walletledger.exception.ErrorCode;
import com.walletledger.repository.UserRepository;
import com.walletledger.service.JwtService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.UUID;

@Component
public class JwtAuthInterceptor implements HandlerInterceptor {

    private static final String REGISTRATION_PATH = "/api/v1/users";
    private static final String LOGIN_PATH = "/api/v1/auth/login";

    private final JwtService jwtService;
    private final UserRepository userRepository;

    public JwtAuthInterceptor(
            JwtService jwtService,
            UserRepository userRepository) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler) {

        String method = request.getMethod();
        String path = request.getRequestURI();

        if ("POST".equalsIgnoreCase(method)
                && (REGISTRATION_PATH.equals(path)
                || LOGIN_PATH.equals(path))) {
            return true;
        }

        String authorization = request.getHeader("Authorization");

        if (authorization == null
                || !authorization.startsWith("Bearer ")
                || authorization.substring(7).isBlank()) {
            throw new ApiException(
                    ErrorCode.UNAUTHENTICATED,
                    "A valid Bearer token is required.");
        }

        final UUID userId;

        try {
            userId = jwtService.validateAndGetUserId(
                    authorization.substring(7).trim());
        } catch (JwtService.InvalidTokenException exception) {
            throw new ApiException(
                    ErrorCode.UNAUTHENTICATED,
                    "The bearer token is invalid or expired.");
        }

        if (!userRepository.existsById(userId)) {
            throw new ApiException(
                    ErrorCode.UNAUTHENTICATED,
                    "The bearer token is invalid or expired.");
        }

        UserContext.setUserId(request, userId);
        return true;
    }
}
