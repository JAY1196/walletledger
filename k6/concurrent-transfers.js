
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';

// Custom response counters.
const expected4xx = new Counter('expected_4xx');
const unexpected4xx = new Counter('unexpected_4xx');
const unexpected5xx = new Counter('unexpected_5xx');
const unexpectedOther = new Counter('unexpected_other');
const rateLimited429 = new Counter('rate_limited_429');

// Set RUN_ID once from PowerShell for consistent reporting.
const runId = __ENV.RUN_ID || 'walletledger-k6';
const baseUrl = __ENV.BASE_URL || 'http://localhost:8080';

const expected4xxStatuses = (__ENV.EXPECTED_4XX || '')
  .split(',')
  .map((status) => Number(status.trim()))
  .filter((status) => status >= 400 && status < 500);

const initialFundingPaise = 1000000;
const transferAmountPaise = 1;
const walletCount = 10;

const scenarioSelection = __ENV.SCENARIO || 'both';
if (!['both', 'hot_pair', 'pool'].includes(scenarioSelection)) {
  throw new Error('SCENARIO must be both, hot_pair, or pool');
}

const scenarioDefinitions = {
  hot_pair: {
    executor: 'constant-vus',
    vus: 5,
    duration: '30s',
    startTime: '0s',
    exec: 'hotPair',
  },
  wallet_pool: {
    executor: 'ramping-vus',
    startTime: '35s',
    startVUs: 0,
    stages: [
      { duration: '30s', target: 5 },
      { duration: '30s', target: 10 },
      { duration: '30s', target: 25 },
      { duration: '10s', target: 0 },
    ],
    gracefulRampDown: '10s',
    exec: 'walletPool',
  },
};

const selectedScenarios =
  scenarioSelection === 'hot_pair'
    ? { hot_pair: scenarioDefinitions.hot_pair }
    : scenarioSelection === 'pool'
      ? {
          wallet_pool: {
            ...scenarioDefinitions.wallet_pool,
            startTime: '0s',
          },
        }
      : scenarioDefinitions;

export const options = {
  summaryTrendStats: [
    'avg',
    'min',
    'med',
    'max',
    'p(90)',
    'p(95)',
    'p(99)',
  ],

  scenarios: selectedScenarios,

  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<500'],
    unexpected_4xx: ['count==0'],
    unexpected_5xx: ['count==0'],
    unexpected_other: ['count==0'],
    rate_limited_429: ['count==0'],
  },
};

function requestHeaders(token, idempotencyKey) {
  const headers = { 'Content-Type': 'application/json' };

  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }

  if (idempotencyKey) {
    headers['Idempotency-Key'] = idempotencyKey;
  }

  return { headers };
}

function requireCreated(response, operation) {
  if (response.status !== 201) {
    throw new Error(
      `${operation} failed: HTTP ${response.status}; body=${response.body}`
    );
  }
}

function createUser(suffix) {
  const response = http.post(
    `${baseUrl}/api/v1/users`,
    JSON.stringify({
      name: `k6 benchmark ${suffix}`,
      email: `k6-${runId}-${suffix}@example.test`,
      password: 'ValidPassword123!',
    }),
    requestHeaders(null, null)
  );

  requireCreated(response, `Create user ${suffix}`);
  return JSON.parse(response.body).id;
}

function loginUser(email, password, suffix) {
  const response = http.post(
    `${baseUrl}/api/v1/auth/login`,
    JSON.stringify({ email, password }),
    requestHeaders(null, null)
  );

  if (response.status !== 200) {
    throw new Error(
      `Login ${suffix} failed: HTTP ${response.status}; body=${response.body}`
    );
  }

  const body = JSON.parse(response.body);

  if (!body.accessToken || body.tokenType !== 'Bearer') {
    throw new Error(`Login ${suffix} returned an invalid token response`);
  }

  return body.accessToken;
}

function createWallet(token, suffix) {
  const response = http.post(
    `${baseUrl}/api/v1/wallets`,
    null,
    requestHeaders(token, null)
  );

  requireCreated(response, `Create wallet ${suffix}`);
  return JSON.parse(response.body).id;
}

function fundWallet(token, walletId, suffix) {
  const response = http.post(
    `${baseUrl}/api/v1/wallets/${walletId}/topup`,
    JSON.stringify({ amountPaise: initialFundingPaise }),
    requestHeaders(token, `k6-fund-${runId}-${suffix}`)
  );

  requireCreated(response, `Fund wallet ${suffix}`);
}

export function setup() {
  const tokens = [];
  const wallets = [];
  const password = 'ValidPassword123!';

  for (let i = 0; i < walletCount; i++) {
    const suffix = `wallet-${i + 1}`;
    const email = `k6-${runId}-${suffix}@example.test`;

    createUser(suffix);
    const token = loginUser(email, password, suffix);
    const walletId = createWallet(token, suffix);

    fundWallet(token, walletId, suffix);

    tokens.push(token);
    wallets.push(walletId);
  }

  console.log(
    `Prepared ${walletCount} authenticated, funded wallets for run ${runId}`
  );

  return { tokens, wallets };
}

function recordResponse(response) {
  const status = response.status;

  if (status === 429) {
    rateLimited429.add(1);
  }

  if (status >= 400 && status < 500) {
    if (expected4xxStatuses.includes(status)) {
      expected4xx.add(1);
    } else {
      unexpected4xx.add(1);
    }
  } else if (status >= 500 && status < 600) {
    unexpected5xx.add(1);
  } else if (status !== 201) {
    unexpectedOther.add(1);
  }

  check(response, {
    'transfer returns 201': (r) => r.status === 201,
  });
}

function transfer(data, sourceIndex, destinationIndex) {
  const sourceWalletId = data.wallets[sourceIndex];
  const destinationWalletId = data.wallets[destinationIndex];
  const token = data.tokens[sourceIndex];

  const response = http.post(
    `${baseUrl}/api/v1/transfers`,
    JSON.stringify({
      sourceWalletId,
      destinationWalletId,
      amountPaise: transferAmountPaise,
    }),
    requestHeaders(
      token,
      `k6-transfer-${runId}-${__VU}-${__ITER}`
    )
  );

  recordResponse(response);
  sleep(0.1);
}

export function hotPair(data) {
  const forward = __VU % 2 === 1;
  transfer(data, forward ? 0 : 1, forward ? 1 : 0);
}

export function walletPool(data) {
  const sourceIndex = (__VU + __ITER) % walletCount;
  const offset = 1 + ((__VU + __ITER) % (walletCount - 1));
  const destinationIndex = (sourceIndex + offset) % walletCount;

  transfer(data, sourceIndex, destinationIndex);
}

function metricValue(data, metricName, valueName) {
  const metric = data.metrics[metricName];

  if (
    !metric ||
    !metric.values ||
    metric.values[valueName] === undefined
  ) {
    return valueName === 'count' ? 0 : 'n/a';
  }

  return metric.values[valueName];
}

function formatNumber(value, decimals = 2) {
  return typeof value === 'number' ? value.toFixed(decimals) : value;
}

export function handleSummary(data) {
  const failureRate = metricValue(data, 'http_req_failed', 'rate');

  const summary = [
    '',
    '========== WalletLedger k6 Benchmark ==========',
    `Run ID: ${runId}`,
    `HTTP request rate: ${formatNumber(metricValue(data, 'http_reqs', 'rate'))} requests/s`,
    `HTTP request failures: ${typeof failureRate === 'number' ? formatNumber(failureRate * 100) : failureRate}%`,
    `http_req_duration p(90): ${formatNumber(metricValue(data, 'http_req_duration', 'p(90)'))} ms`,
    `http_req_duration p(95): ${formatNumber(metricValue(data, 'http_req_duration', 'p(95)'))} ms`,
    `http_req_duration p(99): ${formatNumber(metricValue(data, 'http_req_duration', 'p(99)'))} ms`,
    `Expected 4xx responses: ${metricValue(data, 'expected_4xx', 'count')}`,
    `Unexpected 4xx responses: ${metricValue(data, 'unexpected_4xx', 'count')}`,
    `Unexpected 5xx responses: ${metricValue(data, 'unexpected_5xx', 'count')}`,
    `Unexpected other responses: ${metricValue(data, 'unexpected_other', 'count')}`,
    `HTTP 429 rate-limit responses: ${metricValue(data, 'rate_limited_429', 'count')}`,
    '==============================================',
    '',
  ].join('\n');

  return {
    stdout: summary,
  };
}
