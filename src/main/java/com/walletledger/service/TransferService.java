package com.walletledger.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.walletledger.dto.TransferRequest;
import com.walletledger.dto.TransferResponse;

import com.walletledger.entity.Transaction;
import com.walletledger.entity.TransactionStatus;
import com.walletledger.entity.TransactionType;
import com.walletledger.entity.Wallet;
import com.walletledger.entity.WalletType;
import com.walletledger.exception.ApiException;
import com.walletledger.exception.ErrorCode;
import com.walletledger.entity.LedgerEntryDirection;
import com.walletledger.repository.TransactionRepository;
import com.walletledger.repository.WalletRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.UUID;

@Service
public class TransferService {

    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;

    private final JsonMapper objectMapper;

    private final LedgerPostingService ledgerPostingService;
    private final TransactionLifecycleService transactionLifecycleService;

    public TransferService(
            WalletRepository walletRepository,
            TransactionRepository transactionRepository,
            LedgerPostingService ledgerPostingService,
            TransactionLifecycleService transactionLifecycleService) {
        this.walletRepository = walletRepository;
        this.transactionRepository = transactionRepository;
        this.ledgerPostingService = ledgerPostingService;
        this.transactionLifecycleService = transactionLifecycleService;
        this.objectMapper = JsonMapper.builder().build();
    }

    @Transactional
    public TransferHttpResponse transfer(
            UUID initiatedBy,
            String idempotencyKey,
            TransferRequest request) {

        if (idempotencyKey == null || idempotencyKey.isBlank()
                || idempotencyKey.length() > 128) {
            throw new ApiException(ErrorCode.BAD_REQUEST,
                    "Idempotency-Key must contain 1 to 128 characters.");
        }

        if (request.sourceWalletId().equals(request.destinationWalletId())) {
            throw new ApiException(ErrorCode.BAD_REQUEST,
                    "Source and destination wallets must differ.");
        }

        byte[] requestHash = hashRequest(request);

        var previous = transactionRepository
                .findByInitiatedByAndIdempotencyKey(initiatedBy, idempotencyKey);

        if (previous.isPresent()) {
            Transaction existing = previous.get();

            if (!MessageDigest.isEqual(existing.getRequestHash(), requestHash)) {
                throw new ApiException(ErrorCode.CONFLICT,
                        "This idempotency key was already used for a different request.");
            }

            if (existing.getStatus() == TransactionStatus.COMPLETED) {
                return toHttpResponse(existing);
            }

            throw new ApiException(ErrorCode.CONFLICT,
                    "A transaction with this idempotency key is not complete.");
        }

        List<UUID> orderedWalletIds = List.of(
                        request.sourceWalletId(), request.destinationWalletId())
                .stream()
                .distinct()
                .sorted(WalletLockOrder.COMPARATOR)
                .toList();

        List<Wallet> locked = new java.util.ArrayList<>();

        for (UUID walletId : orderedWalletIds) {
            walletRepository.findByIdForUpdate(walletId)
                    .ifPresent(locked::add);
        }

        // Recheck after locking wallets to handle concurrent retries.
        var concurrentPrevious = transactionRepository
                .findByInitiatedByAndIdempotencyKey(initiatedBy, idempotencyKey);

        if (concurrentPrevious.isPresent()) {
            Transaction existing = concurrentPrevious.get();

            if (!MessageDigest.isEqual(existing.getRequestHash(), requestHash)) {
                throw new ApiException(ErrorCode.CONFLICT,
                        "This idempotency key was already used for a different request.");
            }

            if (existing.getStatus() == TransactionStatus.COMPLETED) {
                return toHttpResponse(existing);
            }

            throw new ApiException(ErrorCode.CONFLICT,
                    "A transaction with this idempotency key is not complete.");
        }

        Wallet source = locked.stream()
                .filter(w -> w.getId().equals(request.sourceWalletId()))
                .findFirst()
                .orElseThrow(() -> new ApiException(
                        ErrorCode.NOT_FOUND, "Source wallet not found."));

        Wallet destination = locked.stream()
                .filter(w -> w.getId().equals(request.destinationWalletId()))
                .findFirst()
                .orElseThrow(() -> new ApiException(
                        ErrorCode.NOT_FOUND, "Destination wallet not found."));

        if (source.getWalletType() != WalletType.USER
                || !source.getUserId().equals(initiatedBy)) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Source wallet not found.");
        }

        if (destination.getWalletType() != WalletType.USER) {
            throw new ApiException(ErrorCode.NOT_FOUND,
                    "Destination wallet not found.");
        }

        if (!source.getCurrency().equals(destination.getCurrency())) {
            throw new ApiException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "Wallet currencies must match.");
        }

        if (request.amountPaise() <= 0) {
            throw new ApiException(ErrorCode.BAD_REQUEST,
                    "Transfer amount must be positive.");
        }

        if (source.getBalance() < request.amountPaise()) {
            throw new ApiException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "Insufficient wallet balance.");
        }

        UUID transactionId = UUID.randomUUID();

        Transaction transaction = new Transaction(
                transactionId,
                TransactionType.TRANSFER,
                idempotencyKey,
                requestHash,
                request.amountPaise(),
                initiatedBy,
                source.getId(),
                destination.getId(),
                null
        );

        // Persist the transaction first so ledger entries can reference it.
        transactionRepository.saveAndFlush(transaction);

        try {
            ledgerPostingService.post(transaction, List.of(
                    new PostingLeg(source.getId(),
                            LedgerEntryDirection.DEBIT, request.amountPaise()),
                    new PostingLeg(destination.getId(),
                            LedgerEntryDirection.CREDIT, request.amountPaise())
            ));
        } catch (IllegalArgumentException ex) {
            throw new ApiException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "The transfer would exceed the supported balance range.");
        }

        TransferResponse response = new TransferResponse(
                transactionId,
                TransactionStatus.COMPLETED,
                source.getId(),
                destination.getId(),
                request.amountPaise()
        );

        final String responseBody;
        try {
            responseBody = objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(
                    "Could not serialize the transfer response.", ex);
        }

        transactionLifecycleService.complete(
                transaction,
                (short) 201,
                responseBody,
                "Transfer completed successfully"
        );

        return new TransferHttpResponse(201, responseBody);
    }

    private byte[] hashRequest(TransferRequest request) {
        String canonical = request.sourceWalletId() + "|"
                + request.destinationWalletId() + "|"
                + request.amountPaise();

        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable.", ex);
        }
    }

    private TransferHttpResponse toHttpResponse(Transaction transaction) {
        Short status = transaction.getResponseStatus();
        String body = transaction.getResponseBody();

        if (status == null || body == null || body.isBlank()) {
            throw new IllegalStateException(
                    "Completed transaction is missing its stored HTTP response.");
        }

        return new TransferHttpResponse(status.intValue(), body);
    }

    private TransferResponse toResponse(Transaction transaction) {
        return new TransferResponse(
                transaction.getId(),
                transaction.getStatus(),
                transaction.getSourceWalletId(),
                transaction.getDestinationWalletId(),
                transaction.getAmount()
        );
    }
}
