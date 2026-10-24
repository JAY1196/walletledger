package com.walletledger.dto;

import jakarta.validation.constraints.Positive;

public record MoneyMovementRequest(
        @Positive long amountPaise
) {
}