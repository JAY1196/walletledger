import http from 'k6/http';
import { Counter } from 'k6/metrics';

const allowed = new Counter('allowed_201');
const limited = new Counter('limited_429');
const unexpected = new Counter('unexpected_status');
const missingRetryAfter = new Counter('missing_retry_after');

export const options = {
    scenarios: {
        burst: {
            executor: 'per-vu-iterations',
            vus: 100,
            iterations: 1,
            maxDuration: '30s',
        },
    },
    thresholds: {
        unexpected_status: ['count==0'],
        limited_429: ['count>0'],
        missing_retry_after: ['count==0'],
    },
};

export default function () {
    const token = __ENV.TOKEN;
    const walletId = __ENV.WALLET_ID;

    const response = http.post(
        `http://localhost:8080/api/v1/wallets/${walletId}/topup`,
        JSON.stringify({ amountPaise: 1 }),
        {
            headers: {
                Authorization: `Bearer ${token}`,
                'Content-Type': 'application/json',
                'Idempotency-Key': `w1-burst-${__VU}-${__ITER}`,
            },
        }
    );

    if (response.status === 201) {
        allowed.add(1);
    } else if (response.status === 429) {
        limited.add(1);
        if (!response.headers['Retry-After']) {
            missingRetryAfter.add(1);
        }
    } else {
        unexpected.add(1);
        console.error(`Unexpected HTTP ${response.status}: ${response.body}`);
    }
}

export function handleSummary(data) {
    const count = (name) => data.metrics[name]?.values?.count ?? 0;

    return {
        stdout: [
            '',
            '====== WalletLedger Money-Movement Rate Limit ======',
            `Allowed (201): ${count('allowed_201')}`,
            `Rate limited (429): ${count('limited_429')}`,
            `Unexpected responses: ${count('unexpected_status')}`,
            `Missing Retry-After: ${count('missing_retry_after')}`,
            '=====================================================',
            '',
        ].join('\n'),
    };
}