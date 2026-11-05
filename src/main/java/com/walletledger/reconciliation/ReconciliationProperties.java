package com.walletledger.reconciliation;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "walletledger.reconciliation")
public record ReconciliationProperties(
        String cron,
        String lockName,
        int lockTimeoutSeconds) {
}
