package com.walletledger.reconciliation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ReconciliationScheduledJob {

    private static final Logger log =
            LoggerFactory.getLogger(ReconciliationScheduledJob.class);

    private final ReconciliationRunner reconciliationRunner;

    public ReconciliationScheduledJob(
            ReconciliationRunner reconciliationRunner) {
        this.reconciliationRunner = reconciliationRunner;
    }

    @Scheduled(cron = "${walletledger.reconciliation.cron}")
    public void executeScheduledReconciliation() {

        try {
            boolean executed = reconciliationRunner.runOnce();

            if (executed) {
                log.info("Scheduled reconciliation completed.");
            } else {
                log.info(
                        "Scheduled reconciliation skipped: "
                                + "another instance holds the lock.");
            }

        } catch (Exception exception) {
            log.error(
                    "Scheduled reconciliation failed.",
                    exception);
        }
    }
}
