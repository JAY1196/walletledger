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
const email = __ENV.USER_EMAIL;
const password = __ENV.USER_PASSWORD;
const walletId = __ENV.WALLET_ID;
const runId = `${Date.now()}-${Math.random().toString(16).slice(2)}`;

export function setup() {
  if (!email || !password || !walletId) {
    throw new Error(
      'Set USER_EMAIL, USER_PASSWORD, and WALLET_ID environment variables.'
    );
  }

  const response = http.post(
    `${baseUrl}/api/v1/auth/login`,
    JSON.stringify({ email, password }),
    { headers: { 'Content-Type': 'application/json' } }
  );

  if (response.status !== 200) {
    throw new Error(
      `Login failed: HTTP ${response.status}; body=${response.body}`
    );
  }

  const body = JSON.parse(response.body);

  if (!body.accessToken || body.tokenType !== 'Bearer') {
    throw new Error('Login returned an invalid token response.');
  }

  console.log('Wallet money-movement benchmark authenticated successfully.');
  return { token: body.accessToken };
}

export default function (data) {
  const response = http.post(
    `${baseUrl}/api/v1/wallets/${walletId}/topup`,
    JSON.stringify({ amountPaise: 1 }),
    {
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${data.token}`,
        'Idempotency-Key': `k6-${runId}-${__VU}-${__ITER}`,
      },
    }
  );

  check(response, {
    'top-up returns 201': (r) => r.status === 201,
  });

  sleep(0.5);
}