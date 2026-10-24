-- Stable IDs reserved for the WalletLedger system bank.
-- UUID_TO_BIN(..., 0) uses standard UUID byte order.

INSERT INTO users (
    id,
    name,
    email,
    created_at
)
VALUES (
    UUID_TO_BIN('00000000-0000-0000-0000-000000000001', 0),
    'WalletLedger System Bank',
    'system-bank@walletledger.internal',
    UTC_TIMESTAMP(6)
);

INSERT INTO wallets (
    id,
    user_id,
    wallet_type,
    currency,
    balance,
    version,
    created_at,
    updated_at
)
VALUES (
    UUID_TO_BIN('00000000-0000-0000-0000-000000000002', 0),
    UUID_TO_BIN('00000000-0000-0000-0000-000000000001', 0),
    'SYSTEM',
    'INR',
    0,
    0,
    UTC_TIMESTAMP(6),
    UTC_TIMESTAMP(6)
);