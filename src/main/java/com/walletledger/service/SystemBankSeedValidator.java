package com.walletledger.service;

import com.walletledger.entity.User;
import com.walletledger.entity.Wallet;
import com.walletledger.entity.WalletType;
import com.walletledger.repository.UserRepository;
import com.walletledger.repository.WalletRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Component
public class SystemBankSeedValidator implements ApplicationRunner {

    private static final UUID SYSTEM_USER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static final UUID SYSTEM_WALLET_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000002");

    private static final String SYSTEM_USER_NAME = "WalletLedger System Bank";
    private static final String SYSTEM_USER_EMAIL =
            "system-bank@walletledger.internal";

    private final UserRepository userRepository;
    private final WalletRepository walletRepository;

    public SystemBankSeedValidator(
            UserRepository userRepository,
            WalletRepository walletRepository
    ) {
        this.userRepository = userRepository;
        this.walletRepository = walletRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public void run(ApplicationArguments args) {
        User systemUser = userRepository.findById(SYSTEM_USER_ID)
                .orElseThrow(() -> new IllegalStateException(
                        "Required system-bank user is missing."
                ));

        if (!SYSTEM_USER_NAME.equals(systemUser.getName())
                || !SYSTEM_USER_EMAIL.equals(systemUser.getEmail())) {
            throw new IllegalStateException(
                    "System-bank user identity does not match the expected seed."
            );
        }

        Wallet systemWallet = walletRepository.findById(SYSTEM_WALLET_ID)
                .orElseThrow(() -> new IllegalStateException(
                        "Required system-bank wallet is missing."
                ));

        if (!SYSTEM_USER_ID.equals(systemWallet.getUserId())
                || systemWallet.getWalletType() != WalletType.SYSTEM
                || !"INR".equals(systemWallet.getCurrency())) {
            throw new IllegalStateException(
                    "System-bank wallet ownership, type, or currency is invalid."
            );
        }
    }
}