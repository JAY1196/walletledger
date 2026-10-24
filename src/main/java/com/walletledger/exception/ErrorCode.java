package com.walletledger.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

    BAD_REQUEST(HttpStatus.BAD_REQUEST, "The request is invalid."),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Authentication is required."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "You are not allowed to perform this action."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "The requested resource was not found."),
    CONFLICT(HttpStatus.CONFLICT, "The request conflicts with the current state."),
    UNPROCESSABLE_ENTITY(HttpStatus.UNPROCESSABLE_CONTENT, "The request cannot be processed."),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Too many requests."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred."),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "The service is temporarily unavailable.");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}