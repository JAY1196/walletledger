package com.walletledger.exception;

import com.walletledger.ratelimit.RateLimitExceededException;

import jakarta.persistence.OptimisticLockException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log =
            LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> handleApiException(
            ApiException ex,
            HttpServletRequest request) {

        ErrorCode errorCode = ex.getErrorCode();

        ProblemDetail body = createProblem(
                errorCode.status(),
                errorCode.status().getReasonPhrase(),
                ex.getMessage(),
                errorCode.name(),
                request
        );

        if (ex instanceof RateLimitExceededException rateLimitException) {
            return ResponseEntity.status(errorCode.status())
                    .header(HttpHeaders.RETRY_AFTER,
                            Long.toString(rateLimitException.getRetryAfterSeconds()))
                    .body(body);
        }

        return ResponseEntity.status(errorCode.status()).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(
            MethodArgumentNotValidException ex,
            HttpServletRequest request) {

        ProblemDetail body = createProblem(
                HttpStatus.BAD_REQUEST,
                "Validation failed",
                "One or more fields failed validation.",
                ErrorCode.BAD_REQUEST.name(),
                request
        );

        List<Map<String, String>> errors = new ArrayList<>();

        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("field", fieldError.getField());
            item.put("message", fieldError.getDefaultMessage() == null
                    ? "Invalid value"
                    : fieldError.getDefaultMessage());
            errors.add(item);
        }

        body.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraintViolation(
            ConstraintViolationException ex,
            HttpServletRequest request) {

        ProblemDetail body = createProblem(
                HttpStatus.BAD_REQUEST,
                "Validation failed",
                "One or more parameters failed validation.",
                ErrorCode.BAD_REQUEST.name(),
                request
        );

        List<Map<String, String>> errors = new ArrayList<>();

        for (ConstraintViolation<?> violation : ex.getConstraintViolations()) {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("field", violation.getPropertyPath().toString());
            item.put("message", violation.getMessage());
            errors.add(item);
        }

        body.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,
            MissingRequestHeaderException.class
    })
    public ResponseEntity<ProblemDetail> handleBadRequest(
            Exception ex,
            HttpServletRequest request) {

        ProblemDetail body = createProblem(
                HttpStatus.BAD_REQUEST,
                "Bad request",
                "The request is malformed or contains an invalid parameter.",
                ErrorCode.BAD_REQUEST.name(),
                request
        );

        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler({
            OptimisticLockingFailureException.class,
            OptimisticLockException.class
    })
    public ResponseEntity<ProblemDetail> handleOptimisticLockFailure(
            Exception ex,
            HttpServletRequest request) {

        ErrorCode errorCode = ErrorCode.CONFLICT;

        ProblemDetail body = createProblem(
                errorCode.status(),
                errorCode.status().getReasonPhrase(),
                "The resource changed concurrently. Retry the operation.",
                errorCode.name(),
                request
        );

        return ResponseEntity.status(errorCode.status()).body(body);
    }

    @ExceptionHandler({
            NoHandlerFoundException.class,
            NoResourceFoundException.class
    })
    public ResponseEntity<ProblemDetail> handleNotFound(
            Exception ex,
            HttpServletRequest request) {

        ErrorCode errorCode = ErrorCode.NOT_FOUND;

        ProblemDetail body = createProblem(
                errorCode.status(),
                errorCode.status().getReasonPhrase(),
                errorCode.defaultMessage(),
                errorCode.name(),
                request
        );

        return ResponseEntity.status(errorCode.status()).body(body);
    }

    @ExceptionHandler({
            PessimisticLockingFailureException.class,
            DataAccessResourceFailureException.class,
            TransientDataAccessResourceException.class
    })
    public ResponseEntity<ProblemDetail> handleDatabaseContention(
            Exception ex,
            HttpServletRequest request) {

        log.warn("Temporary database contention or resource failure on {}",
                request.getRequestURI(), ex);

        ErrorCode errorCode = ErrorCode.SERVICE_UNAVAILABLE;

        ProblemDetail body = createProblem(
                errorCode.status(),
                errorCode.status().getReasonPhrase(),
                "The service is temporarily unavailable. Retry the operation using the same idempotency key.",
                errorCode.name(),
                request
        );

        return ResponseEntity.status(errorCode.status()).body(body);
    }
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleDataIntegrityViolation(
            DataIntegrityViolationException ex,
            HttpServletRequest request) {

        if (isDuplicateConstraint(ex, "uq_users_email")) {
            ErrorCode errorCode = ErrorCode.CONFLICT;

            ProblemDetail body = createProblem(
                    errorCode.status(),
                    errorCode.status().getReasonPhrase(),
                    "An account with this email already exists.",
                    errorCode.name(),
                    request
            );

            return ResponseEntity.status(errorCode.status()).body(body);
        }

        if (isDuplicateConstraint(ex, "uq_transactions_caller_idempotency")) {
            ErrorCode errorCode = ErrorCode.CONFLICT;

            ProblemDetail body = createProblem(
                    errorCode.status(),
                    errorCode.status().getReasonPhrase(),
                    "This idempotency key has already been used. Retry the original request or use a new key.",
                    errorCode.name(),
                    request
            );

            return ResponseEntity.status(errorCode.status()).body(body);
        }

        if (isDuplicateConstraint(ex, "uq_wallets_user_currency")) {
            ErrorCode errorCode = ErrorCode.CONFLICT;

            ProblemDetail body = createProblem(
                    errorCode.status(),
                    errorCode.status().getReasonPhrase(),
                    "You already have an INR wallet.",
                    errorCode.name(),
                    request
            );

            return ResponseEntity.status(errorCode.status()).body(body);
        }

        return handleUnexpected(ex, request);
    }

    private boolean isDuplicateConstraint(Throwable ex, String constraintName) {
        Throwable current = ex;

        while (current != null) {
            if (current instanceof java.sql.SQLException sqlException
                    && sqlException.getErrorCode() == 1062
                    && sqlException.getMessage() != null
                    && sqlException.getMessage().contains(constraintName)) {
                return true;
            }

            current = current.getCause();
        }

        return false;
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(
            Exception ex,
            HttpServletRequest request) {

        String errorRef = UUID.randomUUID().toString();

        // Keep stack traces server-side; never expose them in the response.
        log.error("Unhandled exception; errorRef={}", errorRef, ex);

        ProblemDetail body = createProblem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Internal server error",
                "An unexpected error occurred. Reference ID: " + errorRef,
                ErrorCode.INTERNAL_ERROR.name(),
                request
        );

        body.setProperty("errorRef", errorRef);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(body);
    }

    private ProblemDetail createProblem(
            HttpStatus status,
            String title,
            String detail,
            String code,
            HttpServletRequest request) {

        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setTitle(title);
        body.setInstance(URI.create(request.getRequestURI()));
        body.setProperty("code", code);

        return body;
    }
}