import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  vus: 1,
  duration: '30s',
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<500'],
  },
};

const baseUrl = __ENV.BASE_URL || 'http://localhost:8080';
const userId = __ENV.USER_ID || 'd4a69e03-982b-481f-8774-e9f4a5350a9e';
const walletId = __ENV.WALLET_ID || 'f73a497d-2f52-4dc7-bd88-f6a91f20d95e';
const runId = `${Date.now()}-${Math.random().toString(16).slice(2)}`;

export default function () {
  const response = http.post(
    `${baseUrl}/api/v1/wallets/${walletId}/topup`,
    JSON.stringify({ amountPaise: 1 }),
    {
      headers: {
        'Content-Type': 'application/json',
        'X-User-Id': userId,
        'Idempotency-Key': `k6-${runId}-${__VU}-${__ITER}`,
      },
    }
  );

  check(response, {
    'top-up returns 201': (r) => r.status === 201,
  });

  sleep(0.5);
}
