package com.walletledger.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.UUID;

public record TransferRequest(
        @NotNull UUID sourceWalletId,
        @NotNull UUID destinationWalletId,
        @Positive long amountPaise
) {
}