package com.walletledger.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record WalletStatementResponse(
        @JsonProperty("items")
        List<WalletStatementItem> items,

        @JsonProperty("next_cursor")
        String nextCursor
) {
    public WalletStatementResponse {
        items = List.copyOf(items);
    }
}