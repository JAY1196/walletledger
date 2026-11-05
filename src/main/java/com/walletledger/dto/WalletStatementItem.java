package com.walletledger.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.walletledger.entity.LedgerEntryDirection;
import com.walletledger.entity.TransactionStatus;
import com.walletledger.entity.TransactionType;

import java.time.Instant;
import java.util.UUID;

public record WalletStatementItem(
        @JsonProperty("entry_id")
        long entryId,

        @JsonProperty("transaction_id")
        UUID transactionId,

        @JsonProperty("type")
        TransactionType type,

        @JsonProperty("status")
        TransactionStatus status,

        @JsonProperty("direction")
        LedgerEntryDirection direction,

        @JsonProperty("amount_paise")
        long amountPaise,

        @JsonProperty("counterparty_wallet_id")
        UUID counterpartyWalletId,

        @JsonProperty("created_at")
        Instant createdAt
) {
}