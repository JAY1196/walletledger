# WalletLedger — Module 4 Validation Report

## Automated regression
- Command: `.\mvnw.cmd clean verify`
- Tests: 84 passed; 0 failures, 0 errors, 0 skipped.
- Build result: SUCCESS.

## Live database integrity
- Database: `walletledger` on MySQL 8.4.11.
- Completed/reversed transaction-ledger violations: 0.
- Wallet balance reconciliation violations: 0.

## k6 baseline
- Duration: 30 seconds; 1 virtual user.
- Requests: 55; failed requests: 0.
- Average response time: 48.83 ms.
- p95 response time: 60.31 ms.
- Maximum response time: 153.18 ms.
- Approximate request rate: 1.82 requests/second.

## Live smoke test
- 55 top-ups of 1 paise produced a balance of 55 paise.
- A 1-paise withdrawal succeeded; resulting balance: 54 paise.

## Limitations
This is a low-volume baseline, not a production-capacity or multi-user concurrency benchmark. The SQL checks validate database state at the time of checking and do not prove every crash-recovery scenario.

## Summary
The full regression, live money-movement smoke test, both live read-only integrity checks, and low-volume k6 baseline passed.

## Module 4B: Multi-VU Transfer Benchmark

### Workload
- Tool: k6
- Virtual users: 5
- Duration: 30 seconds
- Scenario: concurrent transfers in opposite directions between two USER wallets
- Transfer amount: 1 paise per request
- Initial funding: 1,000,000 paise (₹10,000) per wallet
- Benchmark wallets' combined initial balance: ₹20,000

### Performance Results
- Completed transfer iterations: 1,024
- HTTP requests: 1,030 (including setup requests)
- Transfer checks passed: 1,024 / 1,024 (100%)
- Failed HTTP requests: 0 / 1,030 (0%)
- Average HTTP request duration: 46.51 ms
- Median HTTP request duration: 39.17 ms
- p90 HTTP request duration: 73 ms
- p95 HTTP request duration: 95.22 ms
- Maximum HTTP request duration: 244.42 ms
- Threshold `http_req_failed: rate<0.01`: PASSED
- Threshold `http_req_duration: p(95)<500`: PASSED

### Post-Benchmark Database Integrity Checks
Executed read-only SQL checks against the WalletLedger MySQL database.

1. Wallet balance reconciliation against net CREDIT/DEBIT ledger postings: zero mismatches.
2. Completed transaction posting checks: zero rows violating the query's two-entry, equal-debit/credit, and transaction-amount conditions.
3. Negative USER wallet balances: zero rows.
4. Benchmark wallet balances:
   - Wallet A: 999,794 paise (₹9,997.94)
   - Wallet B: 1,000,206 paise (₹10,002.06)
   - Combined: 2,000,000 paise (₹20,000.00), unchanged from initial funding.

### Interpretation and Limitations
The tested workload completed without failed HTTP requests, both k6 thresholds passed, and the recorded database checks returned no violations. The benchmark provides evidence for this five-VU workload; it does not establish production-scale capacity or prove all possible financial invariants. Database checks cover only the conditions encoded in the executed queries.


## Module 4C: Isolated Hot-Pair and Wallet-Pool Benchmarks

### Run Metadata

- Tool: k6 2.2.0
- Hot-pair run ID: `hot-pair-20261024-112703`
- Wallet-pool run ID: `wallet-pool-20261024-112915`
- Summary files: `k6/hot-pair-only.json` and `k6/wallet-pool-only.json`
- API base URL: `http://localhost:8080`
- Each run prepared 10 funded wallets.
- Each wallet was initially funded with 1,000,000 paise.
- HTTP request counts include setup requests; iteration counts represent completed workload iterations.

### Workload Configuration

| Property | Hot-pair | Wallet-pool |
|---|---|---|
| Scenario | `hot_pair` | `wallet_pool` |
| VUs | 5 | Ramped to 25 |
| Workload duration | 30 seconds | 100 seconds |
| Stages | Constant 5 VUs | 30s at 5, 30s at 10, 30s at 25, 10s ramp-down |
| Completed iterations | 1,088 | 5,093 |
| Interrupted iterations | 0 | 0 |
| k6 exit code | 0 | 0 |

### Performance Results

| Metric | Hot-pair | Wallet-pool |
|---|---:|---:|
| HTTP requests | 1,118 | 5,123 |
| HTTP request rate | 35.75/s | 50.88/s |
| Average HTTP request duration | 37.53 ms | 78.22 ms |
| Median HTTP request duration | 30.40 ms | 47.52 ms |
| p90 HTTP request duration | 56.28 ms | 176.69 ms |
| p95 HTTP request duration | 76.82 ms | 222.69 ms |
| p99 HTTP request duration | 118.55 ms | 299.70 ms |
| Maximum HTTP request duration | 235.17 ms | 510.70 ms |
| Reported HTTP failure rate | 0% | 0% |
| Transfer checks | 1,088 passed, 0 failed | 5,093 passed, 0 failed |
| Unexpected 4xx responses | 0 | 0 |
| Unexpected 5xx responses | 0 | 0 |
| Unexpected other responses | 0 | 0 |

### Threshold Reporting Caveat

The saved k6 JSON summaries report threshold flags as false for all five configured thresholds in both runs. This conflicts with the recorded metric values: reported HTTP failure rate is 0%, unexpected response counts are zero, and p95 latency is below the configured 500 ms limit in both runs.

The `http_req_failed` summaries also report zero failed requests in the metric value while recording zero passes and a number of failed threshold evaluations equal to the total request count. These fields are internally inconsistent and have not been independently resolved.

Therefore, the k6 exit code of 0 and the observed performance values are recorded as evidence, but the configured thresholds are **not claimed as passed**. The threshold-reporting discrepancy requires investigation.

### Post-Benchmark Database Integrity

Read-only SQL checks were run after each isolated benchmark.

| Integrity check | After hot-pair | After wallet-pool |
|---|---:|---:|
| Global wallet balance sum (paise) | 0 | 0 |
| Wallet-to-ledger reconciliation mismatches | 0 | 0 |
| Completed/reversed transaction-ledger violations | 0 | 0 |
| Negative USER wallet balances | 0 | 0 |

The completed/reversed transaction query checks for at least two ledger entries and debit and credit totals equal to the transaction amount. These checks validate only the conditions encoded in the executed SQL; they do not establish every possible financial invariant.

### MySQL Deadlock Counter

The `lock_deadlocks` metric was enabled throughout the observed period.

| Observation | Captured time | MySQL uptime (seconds) | Deadlock counter |
|---|---|---:|---:|
| Baseline | 2026-10-24 05:52:38 | 2,105 | 0 |
| After hot-pair | 2026-10-24 05:58:41 | 2,468 | 0 |
| After wallet-pool | 2026-10-24 06:02:40 | 2,707 | 0 |

- Observed deadlock-counter delta from baseline to after hot-pair: 0.
- Observed deadlock-counter delta from after hot-pair to after wallet-pool: 0.
- Observed deadlock-counter delta across the full recorded period: 0.
- The increasing uptime values are consistent with MySQL remaining up during these observations; no MySQL restart was performed as part of the benchmark sequence.

A zero counter delta means no deadlocks were recorded by this metric during the observed interval. It does not prove that deadlocks are impossible under other workloads.

### Interpretation and Limitations

The hot-pair workload completed 1,088 iterations and the wallet-pool workload completed 5,093 iterations. Both reported zero failed transfer checks, zero HTTP request failures, zero unexpected response counts, and zero violations in the four executed database integrity checks.

The wallet-pool workload produced higher observed throughput and higher latency than the hot-pair workload. These are results from two separate workloads and are not a controlled capacity comparison.

This is local benchmark evidence, not proof of production-scale capacity, universal crash safety, or every financial invariant. The unresolved k6 threshold-reporting inconsistency must be addressed before treating the configured thresholds as validated.

### k6 Threshold Reporting Clarification

Both the hot-pair and wallet-pool k6 runs exited with code 0. A throwaway diagnostic using an intentionally impossible duration threshold confirmed that, in this k6 summary format, a threshold flag of `false` indicates a pass and `true` indicates a failure. However, other saved summary fields were recorded as internally inconsistent in the observations above. The flag interpretation is clarified, but the saved summaries have not been fully reconciled; do not treat this note alone as independent validation of every configured threshold.

These are local benchmark results and do not establish production-scale capacity.
