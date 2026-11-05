package com.walletledger.reconciliation;

public record ReconciliationViolationCounts(
        long transactionLedgerViolations,
        long reversalViolations,
        long walletBalanceViolations,
        long negativeUserWalletViolations,
        long globalBalanceViolations) {

    public long totalViolations() {
        return transactionLedgerViolations
                + reversalViolations
                + walletBalanceViolations
                + negativeUserWalletViolations
                + globalBalanceViolations;
    }

    public boolean hasViolations() {
        return totalViolations() > 0;
    }
}
