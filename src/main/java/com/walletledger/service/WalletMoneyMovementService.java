package com.walletledger.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.walletledger.dto.MoneyMovementRequest;
import com.walletledger.entity.LedgerEntryDirection;
import com.walletledger.entity.Transaction;
import com.walletledger.entity.TransactionStatus;
import com.walletledger.entity.TransactionType;
import com.walletledger.entity.User;
import com.walletledger.entity.Wallet;
import com.walletledger.entity.WalletType;
import com.walletledger.exception.ApiException;
import com.walletledger.exception.ErrorCode;
import com.walletledger.repository.TransactionRepository;
import com.walletledger.repository.UserRepository;
import com.walletledger.repository.WalletRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class WalletMoneyMovementService {

    private static final UUID SYSTEM_USER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SYSTEM_WALLET_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000002");

    private final WalletRepository walletRepository;
    private final UserRepository userRepository;
    private final TransactionRepository transactionRepository;
    private final LedgerPostingService ledgerPostingService;
    private final TransactionLifecycleService transactionLifecycleService;
    private final JsonMapper objectMapper = JsonMapper.builder().build();

    public WalletMoneyMovementService(
            WalletRepository walletRepository,
            UserRepository userRepository,
            TransactionRepository transactionRepository,
            LedgerPostingService ledgerPostingService,
            TransactionLifecycleService transactionLifecycleService) {
        this.walletRepository = walletRepository;
        this.userRepository = userRepository;
        this.transactionRepository = transactionRepository;
        this.ledgerPostingService = ledgerPostingService;
        this.transactionLifecycleService = transactionLifecycleService;
    }

    @Transactional
    public TransferHttpResponse topUp(
            UUID initiatedBy,
            UUID userWalletId,
            String idempotencyKey,
            MoneyMovementRequest request) {
        return move(initiatedBy, userWalletId, idempotencyKey, request, TransactionType.TOPUP);
    }

    @Transactional
    public TransferHttpResponse withdraw(
            UUID initiatedBy,
            UUID userWalletId,
            String idempotencyKey,
            MoneyMovementRequest request) {
        return move(initiatedBy, userWalletId, idempotencyKey, request, TransactionType.WITHDRAWAL);
    }

    private TransferHttpResponse move(
            UUID initiatedBy,
            UUID userWalletId,
            String idempotencyKey,
            MoneyMovementRequest request,
            TransactionType type) {

        if (initiatedBy == null || userWalletId == null || request == null) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Required request values are missing.");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()
                || idempotencyKey.length() > 128) {
            throw new ApiException(ErrorCode.BAD_REQUEST,
                    "Idempotency-Key must contain 1 to 128 characters.");
        }
        if (request.amountPaise() <= 0) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Amount must be positive.");
        }
        if (SYSTEM_USER_ID.equals(initiatedBy)
                || userRepository.findById(initiatedBy).isEmpty()) {
            throw new ApiException(ErrorCode.FORBIDDEN,
                    "A valid non-system user must initiate this operation.");
        }

        byte[] requestHash = hashRequest(type, userWalletId, request.amountPaise());

        var previous = transactionRepository
                .findByInitiatedByAndIdempotencyKey(initiatedBy, idempotencyKey);
        if (previous.isPresent()) {
            return replayOrConflict(previous.get(), requestHash);
        }

        List<UUID> orderedIds = List.of(userWalletId, SYSTEM_WALLET_ID)
                .stream()
                .distinct()
                .sorted(WalletLockOrder.COMPARATOR)
                .toList();

        List<Wallet> locked = new ArrayList<>();
        for (UUID walletId : orderedIds) {
            walletRepository.findByIdForUpdate(walletId).ifPresent(locked::add);
        }

        // Another request may have completed while we waited for wallet locks.
        var concurrentPrevious = transactionRepository
                .findByInitiatedByAndIdempotencyKey(initiatedBy, idempotencyKey);
        if (concurrentPrevious.isPresent()) {
            return replayOrConflict(concurrentPrevious.get(), requestHash);
        }

        Wallet userWallet = locked.stream()
                .filter(wallet -> wallet.getId().equals(userWalletId))
                .findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Wallet not found."));

        Wallet systemWallet = locked.stream()
                .filter(wallet -> wallet.getId().equals(SYSTEM_WALLET_ID))
                .findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.SERVICE_UNAVAILABLE,
                        "The system wallet is unavailable."));

        if (userWallet.getWalletType() != WalletType.USER
                || !initiatedBy.equals(userWallet.getUserId())) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Wallet not found.");
        }
        if (systemWallet.getWalletType() != WalletType.SYSTEM
                || !SYSTEM_USER_ID.equals(systemWallet.getUserId())) {
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE,
                    "The system wallet configuration is invalid.");
        }
        if (!"INR".equals(userWallet.getCurrency())
                || !userWallet.getCurrency().equals(systemWallet.getCurrency())) {
            throw new ApiException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "Only matching INR wallets are supported.");
        }
        if (type == TransactionType.WITHDRAWAL
                && userWallet.getBalance() < request.amountPaise()) {
            throw new ApiException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "Insufficient wallet balance.");
        }

        UUID sourceWalletId = type == TransactionType.TOPUP
                ? SYSTEM_WALLET_ID : userWalletId;
        UUID destinationWalletId = type == TransactionType.TOPUP
                ? userWalletId : SYSTEM_WALLET_ID;

        Transaction transaction = new Transaction(
                UUID.randomUUID(),
                type,
                idempotencyKey,
                requestHash,
                request.amountPaise(),
                initiatedBy,
                sourceWalletId,
                destinationWalletId,
                null
        );
        transactionRepository.saveAndFlush(transaction);

        try {
            ledgerPostingService.post(transaction, List.of(
                    new PostingLeg(sourceWalletId, LedgerEntryDirection.DEBIT,
                            request.amountPaise()),
                    new PostingLeg(destinationWalletId, LedgerEntryDirection.CREDIT,
                            request.amountPaise())
            ));
        } catch (IllegalArgumentException | IllegalStateException ex) {
            throw new ApiException(ErrorCode.UNPROCESSABLE_ENTITY,
                    "The money movement cannot be applied to the current balances.");
        }

        MoneyMovementResponse response = new MoneyMovementResponse(
                transaction.getId(), type, TransactionStatus.COMPLETED,
                userWalletId, request.amountPaise());

        final String responseBody;
        try {
            responseBody = objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(
                    "Could not serialize the money-movement response.", ex);
        }

        transactionLifecycleService.complete(
                transaction,
                (short) 201,
                responseBody,
                type == TransactionType.TOPUP
                        ? "Wallet top-up completed" : "Wallet withdrawal completed");

        return new TransferHttpResponse(201, responseBody);
    }

    private TransferHttpResponse replayOrConflict(
            Transaction existing, byte[] requestHash) {
        if (!MessageDigest.isEqual(existing.getRequestHash(), requestHash)) {
            throw new ApiException(ErrorCode.CONFLICT,
                    "This idempotency key was already used for a different request.");
        }
        if (existing.getStatus() == TransactionStatus.COMPLETED) {
            Short status = existing.getResponseStatus();
            String body = existing.getResponseBody();
            if (status == null || body == null || body.isBlank()) {
                throw new IllegalStateException(
                        "Completed transaction is missing its stored HTTP response.");
            }
            return new TransferHttpResponse(status.intValue(), body);
        }
        throw new ApiException(ErrorCode.CONFLICT,
                "A transaction with this idempotency key is not complete.");
    }

    private byte[] hashRequest(
            TransactionType type, UUID walletId, long amountPaise) {
        String canonical = type.name() + "|" + walletId + "|" + amountPaise;
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable.", ex);
        }
    }

    public record MoneyMovementResponse(
            UUID transactionId,
            TransactionType type,
            TransactionStatus status,
            UUID walletId,
            long amountPaise) {
    }
}