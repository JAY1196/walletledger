package com.walletledger.service;

import com.walletledger.context.UserContext;
import com.walletledger.dto.WalletStatementItem;
import com.walletledger.dto.WalletStatementResponse;
import com.walletledger.entity.LedgerEntryDirection;
import com.walletledger.entity.TransactionStatus;
import com.walletledger.entity.TransactionType;
import com.walletledger.entity.Wallet;
import com.walletledger.entity.WalletType;
import com.walletledger.exception.ApiException;
import com.walletledger.exception.ErrorCode;
import com.walletledger.repository.LedgerEntryRepository;
import com.walletledger.repository.LedgerEntryRepository.StatementRow;
import com.walletledger.repository.WalletRepository;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class WalletStatementService {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final WalletRepository walletRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final StatementCursorCodec cursorCodec;

    public WalletStatementService(
            WalletRepository walletRepository,
            LedgerEntryRepository ledgerEntryRepository,
            StatementCursorCodec cursorCodec) {
        this.walletRepository = walletRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.cursorCodec = cursorCodec;
    }

    @Transactional(readOnly = true)
    public WalletStatementResponse getStatement(
            UUID walletId,
            Integer requestedLimit,
            String cursor,
            HttpServletRequest request) {

        UUID userId = UserContext.getRequiredUserId(request);

        Wallet wallet = walletRepository
                .findByIdAndUserIdAndWalletType(
                        walletId,
                        userId,
                        WalletType.USER)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.NOT_FOUND,
                        "Wallet not found."));

        int limit = requestedLimit == null
                ? DEFAULT_LIMIT
                : requestedLimit;

        if (limit < 1 || limit > MAX_LIMIT) {
            throw new ApiException(
                    ErrorCode.BAD_REQUEST,
                    "Limit must be between 1 and 100.");
        }

        Long beforeId = cursor == null
                ? null
                : cursorCodec.decode(cursor);

        List<StatementRow> rows =
                ledgerEntryRepository.findStatementPage(
                        wallet.getId(),
                        beforeId,
                        limit + 1);

        boolean hasMore = rows.size() > limit;

        List<StatementRow> pageRows = hasMore
                ? rows.subList(0, limit)
                : rows;

        List<WalletStatementItem> items = pageRows.stream()
                .map(this::toStatementItem)
                .toList();

        String nextCursor = hasMore
                ? cursorCodec.encode(
                        pageRows.get(pageRows.size() - 1).getEntryId())
                : null;

        return new WalletStatementResponse(items, nextCursor);
    }

    private WalletStatementItem toStatementItem(StatementRow row) {
        return new WalletStatementItem(
                row.getEntryId(),
                UUID.fromString(row.getTransactionId()),
                TransactionType.valueOf(row.getType()),
                TransactionStatus.valueOf(row.getStatus()),
                LedgerEntryDirection.valueOf(row.getDirection()),
                row.getAmountPaise(),
                row.getCounterpartyWalletId() == null
                        ? null
                        : UUID.fromString(row.getCounterpartyWalletId()),
                row.getCreatedAt());
    }
}