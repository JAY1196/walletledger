package com.walletledger.service;

import com.walletledger.exception.ApiException;
import com.walletledger.exception.ErrorCode;

import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.util.Base64;

@Component
public class StatementCursorCodec {

    private static final int CURSOR_BYTE_LENGTH = Long.BYTES;

    public String encode(long entryId) {
        if (entryId <= 0) {
            throw new IllegalArgumentException(
                    "Ledger entry ID must be positive.");
        }

        byte[] bytes = ByteBuffer.allocate(CURSOR_BYTE_LENGTH)
                .putLong(entryId)
                .array();

        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(bytes);
    }

    public long decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            throw invalidCursor();
        }

        final byte[] bytes;

        try {
            bytes = Base64.getUrlDecoder().decode(cursor);
        } catch (IllegalArgumentException exception) {
            throw invalidCursor();
        }

        if (bytes.length != CURSOR_BYTE_LENGTH) {
            throw invalidCursor();
        }

        long entryId = ByteBuffer.wrap(bytes).getLong();

        if (entryId <= 0) {
            throw invalidCursor();
        }

        if (!encode(entryId).equals(cursor)) {
            throw invalidCursor();
        }

        return entryId;
    }

    private ApiException invalidCursor() {
        return new ApiException(
                ErrorCode.BAD_REQUEST,
                "The statement cursor is invalid.");
    }
}