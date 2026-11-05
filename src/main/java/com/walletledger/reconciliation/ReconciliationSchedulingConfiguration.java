package com.walletledger.reconciliation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "walletledger.reconciliation",
        name = "enabled",
        havingValue = "true")
@EnableScheduling
public class ReconciliationSchedulingConfiguration {
}
