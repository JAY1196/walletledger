package com.walletledger.service;

import com.walletledger.context.UserContext;
import com.walletledger.dto.WalletResponse;
import com.walletledger.entity.Wallet;
import com.walletledger.entity.WalletType;
import com.walletledger.exception.ApiException;
import com.walletledger.exception.ErrorCode;
import com.walletledger.repository.UserRepository;
import com.walletledger.repository.WalletRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class WalletService {

    private static final UUID SYSTEM_USER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final WalletRepository walletRepository;
    private final UserRepository userRepository;

    public WalletService(
            WalletRepository walletRepository,
            UserRepository userRepository
    ) {
        this.walletRepository = walletRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public WalletResponse createWallet(HttpServletRequest request) {
        UUID userId = UserContext.getRequiredUserId(request);

        if (SYSTEM_USER_ID.equals(userId)) {
            throw new ApiException(
                    ErrorCode.FORBIDDEN,
                    "The system bank cannot create a user wallet."
            );
        }

        if (!userRepository.existsById(userId)) {
            throw new ApiException(
                    ErrorCode.NOT_FOUND,
                    "User not found."
            );
        }

        if (walletRepository.findByUserIdAndCurrency(userId, "INR").isPresent()) {
            throw new ApiException(
                    ErrorCode.CONFLICT,
                    "You already have an INR wallet."
            );
        }

        Wallet wallet = new Wallet(
                UUID.randomUUID(),
                userId,
                WalletType.USER
        );

        Wallet savedWallet = walletRepository.saveAndFlush(wallet);

        return toResponse(savedWallet);
    }

    @Transactional(readOnly = true)
    public WalletResponse getBalance(
            UUID walletId,
            HttpServletRequest request
    ) {
        UUID userId = UserContext.getRequiredUserId(request);

        Wallet wallet = walletRepository
                .findByIdAndUserIdAndWalletType(
                        walletId,
                        userId,
                        WalletType.USER
                )
                .orElseThrow(() -> new ApiException(
                        ErrorCode.NOT_FOUND,
                        "Wallet not found."
                ));

        return toResponse(wallet);
    }

    private WalletResponse toResponse(Wallet wallet) {
        return new WalletResponse(
                wallet.getId(),
                wallet.getUserId(),
                wallet.getWalletType(),
                wallet.getCurrency(),
                wallet.getBalance()
        );
    }
}
