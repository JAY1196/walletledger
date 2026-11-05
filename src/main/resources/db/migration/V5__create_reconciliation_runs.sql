-- WalletLedger V5
-- Persistent history for scheduled ledger reconciliation runs.

CREATE TABLE reconciliation_runs (
    id BIGINT NOT NULL AUTO_INCREMENT,

    started_at DATETIME(6) NOT NULL,
    finished_at DATETIME(6) NULL,

    status VARCHAR(24) NOT NULL,

    transaction_ledger_violation_count INT UNSIGNED NOT NULL DEFAULT 0,
    reversal_violation_count INT UNSIGNED NOT NULL DEFAULT 0,
    wallet_balance_violation_count INT UNSIGNED NOT NULL DEFAULT 0,
    negative_user_wallet_violation_count INT UNSIGNED NOT NULL DEFAULT 0,
    global_balance_violation_count INT UNSIGNED NOT NULL DEFAULT 0,

    total_violation_count INT UNSIGNED NOT NULL DEFAULT 0,
    duration_ms BIGINT UNSIGNED NULL,

    error_message VARCHAR(1000) NULL,

    CONSTRAINT pk_reconciliation_runs PRIMARY KEY (id),

    CONSTRAINT chk_reconciliation_runs_status
        CHECK (status IN (
            'RUNNING',
            'SUCCESS',
            'VIOLATIONS_FOUND',
            'FAILED'
        )),

    INDEX idx_reconciliation_runs_started_at (started_at DESC),
    INDEX idx_reconciliation_runs_status_started (status, started_at DESC)

) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;