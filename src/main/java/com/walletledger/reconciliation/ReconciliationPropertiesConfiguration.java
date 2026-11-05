package com.walletledger.reconciliation;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ReconciliationProperties.class)
public class ReconciliationPropertiesConfiguration {
}
