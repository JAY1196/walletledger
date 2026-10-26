package com.walletledger.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.walletledger.entity.LedgerEntryDirection;
import com.walletledger.entity.Transaction;
import com.walletledger.entity.TransactionStatus;
import com.walletledger.entity.TransactionType;
import com.walletledger.exception.ApiException;
import com.walletledger.exception.ErrorCode;
import com.walletledger.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.UUID;

@Service
public class TransactionReversalService {

    private final TransactionRepository transactionRepository;
    private final LedgerPostingService ledgerPostingService;
    private final TransactionLifecycleService lifecycleService;
    private final JsonMapper objectMapper = JsonMapper.builder().build();

    public TransactionReversalService(
            TransactionRepository transactionRepository,
            LedgerPostingService ledgerPostingService,
            TransactionLifecycleService lifecycleService) {
        this.transactionRepository = transactionRepository;
        this.ledgerPostingService = ledgerPostingService;
        this.lifecycleService = lifecycleService;
    }

    @Transactional
    public TransferHttpResponse reverse(
            UUID initiatedBy,
            UUID originalTransactionId,
            String idempotencyKey) {

        if (initiatedBy == null || originalTransactionId == null) {
            throw new ApiException(
                    ErrorCode.BAD_REQUEST, "Required request values are missing.");
        }

        if (idempotencyKey == null || idempotencyKey.isBlank()
                || idempotencyKey.length() > 128) {
            throw new ApiException(
                    ErrorCode.BAD_REQUEST,
                    "Idempotency-Key must contain 1 to 128 characters.");
        }

        byte[] requestHash = hashRequest(originalTransactionId);

        var previous = transactionRepository
                .findByInitiatedByAndIdempotencyKey(initiatedBy, idempotencyKey);

        if (previous.isPresent()) {
            return replayOrConflict(
                    previous.get(), originalTransactionId, requestHash);
        }

        Transaction original = transactionRepository
                .findByIdForUpdate(originalTransactionId)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.NOT_FOUND, "Transaction not found."));

        // Serialize different idempotency keys targeting the same transaction.
        var concurrentPrevious = transactionRepository
                .findByInitiatedByAndIdempotencyKey(initiatedBy, idempotencyKey);

        if (concurrentPrevious.isPresent()) {
            return replayOrConflict(
                    concurrentPrevious.get(), originalTransactionId, requestHash);
        }

        if (!initiatedBy.equals(original.getInitiatedBy())) {
            throw new ApiException(
                    ErrorCode.FORBIDDEN,
                    "Only the transaction initiator can reverse this transaction.");
        }

        if (original.getStatus() == TransactionStatus.REVERSED) {
            throw new ApiException(ErrorCode.ALREADY_REVERSED);
        }

        if (original.getType() == TransactionType.REVERSAL) {
            throw new ApiException(
                    ErrorCode.CONFLICT, "A reversal transaction cannot be reversed.");
        }

        if (original.getType() != TransactionType.TOPUP
                && original.getType() != TransactionType.TRANSFER
                && original.getType() != TransactionType.WITHDRAWAL) {
            throw new ApiException(
                    ErrorCode.CONFLICT, "This transaction type cannot be reversed.");
        }

        if (original.getStatus() != TransactionStatus.COMPLETED) {
            throw new ApiException(
                    ErrorCode.CONFLICT, "Only completed transactions can be reversed.");
        }

        UUID reversalId = UUID.randomUUID();
        UUID reversalSourceId = original.getDestinationWalletId();
        UUID reversalDestinationId = original.getSourceWalletId();

        Transaction reversal = new Transaction(
                reversalId,
                TransactionType.REVERSAL,
                idempotencyKey,
                requestHash,
                original.getAmount(),
                initiatedBy,
                reversalSourceId,
                reversalDestinationId,
                original.getId());

        transactionRepository.saveAndFlush(reversal);

        try {
            ledgerPostingService.post(reversal, List.of(
                    new PostingLeg(
                            reversalSourceId,
                            LedgerEntryDirection.DEBIT,
                            original.getAmount()),
                    new PostingLeg(
                            reversalDestinationId,
                            LedgerEntryDirection.CREDIT,
                            original.getAmount())
            ));
        } catch (IllegalStateException ex) {
            if ("Insufficient wallet balance.".equals(ex.getMessage())) {
                throw new ApiException(ErrorCode.INSUFFICIENT_BALANCE);
            }
            throw ex;
        } catch (IllegalArgumentException ex) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_ENTITY,
                    "The reversal cannot be applied to the current balances.");
        }

        ReversalResponse response = new ReversalResponse(
                reversalId,
                original.getId(),
                TransactionStatus.COMPLETED,
                original.getAmount());

        final String responseBody;
        try {
            responseBody = objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(
                    "Could not serialize the reversal response.", ex);
        }

        lifecycleService.completeReversal(
                original,
                reversal,
                (short) 201,
                responseBody,
                "Transaction reversed successfully");

        return new TransferHttpResponse(201, responseBody);
    }

    private TransferHttpResponse replayOrConflict(
            Transaction existing,
            UUID originalTransactionId,
            byte[] requestHash) {

        if (!MessageDigest.isEqual(existing.getRequestHash(), requestHash)
                || existing.getType() != TransactionType.REVERSAL
                || !originalTransactionId.equals(existing.getOriginalTxnId())) {
            throw new ApiException(
                    ErrorCode.CONFLICT,
                    "This idempotency key was already used for a different request.");
        }

        if (existing.getStatus() == TransactionStatus.COMPLETED) {
            Short status = existing.getResponseStatus();
            String body = existing.getResponseBody();

            if (status == null || body == null || body.isBlank()) {
                throw new IllegalStateException(
                        "Completed reversal is missing its stored HTTP response.");
            }

            return new TransferHttpResponse(status.intValue(), body);
        }

        throw new ApiException(
                ErrorCode.CONFLICT,
                "A reversal with this idempotency key is not complete.");
    }

    private byte[] hashRequest(UUID originalTransactionId) {
        String canonical = "REVERSAL|" + originalTransactionId;

        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable.", ex);
        }
    }

    public record ReversalResponse(
            UUID reversalTransactionId,
            UUID originalTransactionId,
            TransactionStatus status,
            long amountPaise) {
    }
}