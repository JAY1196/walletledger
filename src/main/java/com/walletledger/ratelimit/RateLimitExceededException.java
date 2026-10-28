package com.walletledger.ratelimit;

import com.walletledger.exception.ApiException;
import com.walletledger.exception.ErrorCode;

public class RateLimitExceededException extends ApiException {

    private final long retryAfterSeconds;

    public RateLimitExceededException(long retryAfterSeconds) {
        super(ErrorCode.RATE_LIMITED, ErrorCode.RATE_LIMITED.defaultMessage());
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
