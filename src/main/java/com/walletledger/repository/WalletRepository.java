package com.walletledger.repository;

import com.walletledger.entity.Wallet;
import com.walletledger.entity.WalletType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WalletRepository extends JpaRepository<Wallet, UUID> {

    Optional<Wallet> findByUserIdAndCurrency(UUID userId, String currency);

    Optional<Wallet> findByIdAndUserId(UUID id, UUID userId);

    Optional<Wallet> findByIdAndUserIdAndWalletType(
            UUID id,
            UUID userId,
            WalletType walletType
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM Wallet w WHERE w.id = :id")
    Optional<Wallet> findByIdForUpdate(@Param("id") UUID id);

    @Query(
        value = "SELECT COALESCE(SUM(balance), 0) FROM wallets",
        nativeQuery = true
    )
    BigDecimal sumAllWalletBalances();
}