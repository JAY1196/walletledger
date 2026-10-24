package com.walletledger.dto;

import com.walletledger.entity.WalletType;

import java.util.UUID;

public record WalletResponse(
        UUID id,
        UUID userId,
        WalletType walletType,
        String currency,
        long balancePaise
) {
}
