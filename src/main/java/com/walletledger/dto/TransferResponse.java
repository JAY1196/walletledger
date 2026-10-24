package com.walletledger.dto;

import com.walletledger.entity.TransactionStatus;

import java.util.UUID;

public record TransferResponse(
        UUID transactionId,
        TransactionStatus status,
        UUID sourceWalletId,
        UUID destinationWalletId,
        long amountPaise
) {
}