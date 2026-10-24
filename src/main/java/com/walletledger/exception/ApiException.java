package com.walletledger.exception;

import java.util.Objects;

public class ApiException extends RuntimeException {

    private final ErrorCode errorCode;

    public ApiException(ErrorCode errorCode) {
        this(errorCode, Objects.requireNonNull(errorCode).defaultMessage());
    }

    public ApiException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = Objects.requireNonNull(errorCode);
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}