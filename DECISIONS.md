# WalletLedger - Architecture Decisions

Last updated: 2026-10-24
Scope: Architecture decisions, verified implementation status, concurrency tests, and local benchmark evidence through Module 4C

## 1. Java and Spring Boot

**Decision:** Java 21, Maven, Spring Boot 4.1.1.

**Why:** Java 21 is the selected LTS runtime. Spring Initializr generated
the dependency configuration to avoid manually guessing compatible versions.

**Trade-off:** Spring Boot 4 uses updated dependency APIs. Third-party
libraries must be checked for Boot 4 compatibility when adding them.

## 2. MySQL 8.4 LTS instead of PostgreSQL

**Decision:** Use MySQL 8.4 with InnoDB.

**Why:** MySQL is one of my strongest database technologies. PostgreSQL
installation problems also made MySQL the practical choice for this project.

**Trade-offs accepted:**
- No PostgreSQL-style partial indexes; generated-column unique indexes
  enforce selected conditional uniqueness rules.
- MySQL binary-logging rules affect trigger creation privileges.
- DATETIME values have no inherent time zone, so the application,
  JDBC connection, and database session must consistently use UTC.
- MySQL isolation and locking behavior must be tested rather than
  assuming PostgreSQL behavior.

This is a skills and project-fit decision, not a claim that MySQL is
universally superior to PostgreSQL.

## 3. Docker isolation and host ports

**Decision:** Run MySQL and Redis in Docker Compose.

- Docker MySQL: host 3307 -> container 3306.
- Docker Redis: host 6380 -> container 6379.
- Existing local MySQL installations remain separate.
- Host ports bind to 127.0.0.1 for local development.

**Why:** The application must reliably use the same MySQL 8.4 environment
without conflicting with locally installed database servers.

## 4. Database credentials and migration ownership

**Decision:** Use separate walletledger_owner and walletledger_app accounts.

- Flyway uses the owner account.
- The application account has SELECT, INSERT, UPDATE, and DELETE only.
- The application account has no DROP, ALTER, or TRIGGER privilege.

**Why:** Ordinary application operations should not have schema-management
privileges.

MySQL TRUNCATE does not fire DELETE triggers and requires DROP privilege.
Restricting the application account prevents it from using TRUNCATE to
bypass the ledger's append-only triggers.

The development MySQL configuration enables
log_bin_trust_function_creators so the migration owner can create triggers
while binary logging is enabled. This setting is a local development
exception; production requires a separately reviewed privilege policy.

## 5. Flyway owns the schema

**Decision:** Use versioned Flyway migrations and
spring.jpa.hibernate.ddl-auto=validate.

**Why:** Schema changes must be reviewable, repeatable, and recorded in
flyway_schema_history. Hibernate validates mappings instead of silently
creating or changing the schema.

V1 creates the six business tables and append-only triggers.
V2 seeds the fixed system bank user and wallet.

## 6. Money representation

**Decision:** Store monetary values as BIGINT paise.

**Why:** Integer arithmetic avoids floating-point rounding problems.
INR 1.00 is stored as 100 paise.

**Invariant:** User wallets cannot have negative balances. The system
bank wallet may go negative under the selected accounting model.

The service layer must still enforce balanced, atomic money movements;
individual CHECK constraints do not prove that debit and credit totals balance.

## 7. UUID representation

**Decision:** Generate UUIDs in Java and store them as BINARY(16).
MySQL identity keys use BIGINT AUTO_INCREMENT for ledger entries,
audit records, and outbox events.

**Why:** Binary UUIDs use less index space than textual UUIDs.

**Trade-off:** Binary identifiers are less readable in database consoles.
Use BIN_TO_UUID(id, 0) for inspection. Java UUIDs must use the same
standard byte order when converting to and from BINARY(16).

Time-ordered UUIDs are deferred until insert and index-locality benchmarks
show that random UUID inserts are a measurable bottleneck.

## 8. Conditional uniqueness in MySQL

**Decision:** Use stored generated columns with unique indexes.

For SYSTEM wallets, the generated value is 1 for a SYSTEM wallet and
NULL for USER wallets. Multiple NULL values are allowed by the unique index,
while only one SYSTEM wallet can have the value 1.

For reversals, the generated column contains original_txn_id when the
transaction is a REVERSAL and its status is not FAILED; otherwise it is NULL.
A failed reversal therefore does not consume the original transaction's
unique reversal slot. A pending reversal reserves that slot.

## 9. Ledger and audit immutability

**Decision:** Reject UPDATE and DELETE on ledger_entries and
transaction_audit using database triggers.

**Why:** Corrections should be represented by new financial entries or
new audit events, not by rewriting history.

**Limitation:** Triggers and restricted application privileges protect
ordinary application operations; a sufficiently privileged database
administrator can still change the schema or data.

## 10. Transactions and concurrency

**Decision:** Configure READ COMMITTED and use explicit ordered pessimistic row locking for money movement.

**Why:** MySQL defaults to REPEATABLE READ, which can introduce additional gap-lock behavior. READ COMMITTED is selected to reduce unnecessary locking surprises; it does not eliminate all deadlocks.

**Canonical lock-order rule:** Acquire wallet locks in one consistent order, comparing the canonical 16 UUID bytes lexicographically as unsigned bytes. Use one comparator everywhere.

**Verified implementation evidence:**

- `WalletLockOrder` implements unsigned UUID comparison.
- The lock-order utility is used by `LedgerPostingService`, `TransferService`, and `WalletMoneyMovementService`.
- A parity test verifies the relevant ordering behavior.
- Concurrent wallet creation and same-email registration races are covered by tests.
- The 2,000-transfer opposite-direction stress test and controlled real-MySQL error 1213 recovery test are implemented and verified.

**Boundary:** These tests establish the recorded local behavior, not production capacity or production readiness.

## 11. Idempotency and reversals

**Decision:** Enforce UNIQUE(initiated_by, idempotency_key).

**Why:** A request key is unique per caller rather than globally.

The request hash will identify reuse of a key with a different request body.
The service layer must handle concurrent duplicate requests and return
the previously stored response for a legitimate retry.

The database reversal index ensures that only one non-failed reversal
can reserve a given original transaction. Legal state transitions and
response-replay behavior remain service-layer responsibilities.

## 12. Transactional outbox

**Decision:** Include outbox_events in the database schema now.

**Why:** A later publisher can read durable events and send them to Kafka.
The business transaction, audit row, ledger entries, balance updates,
and outbox event must eventually be committed atomically.

The publisher, Kafka integration, and fraud-detection features are
deferred to later modules. Fraud detection is not part of Module 1.

## 13. Redis availability

**Decision:** Redis is a cache/rate-limiting dependency, not the source
of truth for balances or idempotency.

**Why:** The database remains authoritative. The planned rate limiter
fails open when Redis is unavailable, with an operational log entry.

Readiness checks MySQL but deliberately excludes Redis. Overall health
can report Redis failure while readiness remains UP.

Implementation status (2026-10-18): the rate limiter and its fail-open behavior remain deferred unless documented in a later update.

## 14. Development identity and API errors

**Decision:** Use X-User-Id through a HandlerInterceptor during development.

**Why:** It provides a small identity boundary without adding JWT scope
to the foundation module.

**Security limitation:** The header is spoofable and is not authentication.
Replace it with verified JWT identity before production use.

API errors use RFC 9457 ProblemDetail and stable codes. Unexpected errors
return a reference ID and do not expose stack traces to clients.
Unexpected database constraint failures are treated as bugs, not blindly
translated into business conflicts.

## 15. Test strategy and Windows Surefire workaround

**Decision:** Test database constraints against disposable MySQL 8.4
Testcontainers containers, using direct JDBC and Flyway for schema tests.

**Why:** Tests exercise real MySQL constraint and trigger behavior without
depending on the development database.

Testcontainers image tags are pinned to the MySQL 8.4 and Redis 7 lines,
rather than latest.

Surefire is configured with forkCount=0 because forked test JVM startup
failed on this Windows environment with a classpath-root error, even
when individual tests passed. The non-forked test run succeeded.

**Trade-off:** Tests share Maven's JVM and do not have a separate forked
process. Revisit this if parallel testing or process isolation becomes
important.

## 16. Verified Module 1 results

Verified on 2026-10-12 (historical Module 1 baseline):

- Docker MySQL 8.4.11 and Redis 7 are healthy.
- Flyway V1 and V2 applied successfully to the development database.
- Six business tables and four append-only triggers exist.
- System bank user and wallet were seeded.
- Application database permissions were checked.
- APP-user DROP and TRUNCATE were denied in a temporary-table probe.
- Swagger's X-User-Id authorization scheme was verified.
- HTTP 401, 400, and 500 error formats were verified.
- Actuator overall health, liveness, and readiness returned UP.
- Historical Module 1 checkpoint: automated Maven suite passed 9 tests, with zero failures and zero errors.

## 17. Deferred scope and current status


This section records scope boundaries; it is not a claim that every item was completed in Module 1.

- **Verified:** Wallet transfers, idempotency, ledger posting, transaction audit, transactional outbox writes, invariant checks, and bounded MySQL deadlock retry.
- **Verified:** Lock ordering through `WalletLockOrder`, its production call sites, and the parity test.
- **Verified:** Concurrent wallet creation and same-email registration race tests.
- **Verified:** The 2,000 opposite-direction transfer stress test and local k6 benchmark scenarios.
- **Not implemented:** Transaction reversals and customer statement APIs.
- **Open:** Authentication and authorization hardening, outbox publishing, rate limiting, and production deployment controls.
- **Deferred by scope:** Fraud detection; retain the outbox for possible future integration, but do not implement fraud features in this project phase.

### Historical Module 2 implementation record

These details preserve the original users-and-wallets milestone. They are historical implementation notes, not a replacement for the current verification status.

- **Registration:** `POST /api/v1/users` is public. Names are trimmed; emails are trimmed and normalized to lowercase. Application-generated UUIDs identify users, while database constraint `uq_users_email` enforces email uniqueness. Duplicate emails return `409 CONFLICT`; the creation timestamp is database-generated.
- **Wallet creation:** `POST /api/v1/wallets` creates an INR wallet with a zero initial balance. Application-generated UUIDs identify wallets, and `uq_wallets_user_currency` enforces one wallet per user and currency. Creating a wallet does not move money.
- **Money representation:** Wallet balances are stored as `BIGINT` paise and exposed through `balancePaise`, avoiding floating-point monetary arithmetic.
- **Ownership:** `GET /api/v1/wallets/{id}/balance` is owner-scoped. A wallet not owned by the caller returns `404 NOT_FOUND`. Protected `/api/**` endpoints require a valid `X-User-Id` UUID; this is a development identity mechanism, not production authentication.
- **Errors:** Invalid request data returns `400 BAD_REQUEST`; missing identity on protected endpoints returns `401 UNAUTHENTICATED`; duplicate email or wallet creation returns `409 CONFLICT`. Unexpected errors are handled by the global exception handler.
- **Schema and tests:** Flyway V1/V2 were retained unchanged. The historical full-suite checkpoints were 18 passing tests on 15 October 2026 and 46 passing tests on 18 October 2026. A focused idempotency-mismatch test confirmed that reusing a key with a different amount returns `409 CONFLICT` without moving money a second time.
- **Concurrency follow-up:** The original Module 2 notes had not yet confirmed concurrent wallet-creation handling. Later project verification recorded concurrent wallet-creation and same-email registration race tests; see the current verification sections below.

## 18. Verified transfer and idempotency status (2026-10-18)

The following behaviors have passing integration-test evidence as of 2026-10-18:

- `POST /api/v1/transfers` performs a transfer using integer paise and returns a created response.
- Source-wallet ownership is enforced; a caller cannot transfer from another user's wallet.
- Insufficient funds are rejected without changing balances.
- Successful transfers create corresponding debit and credit ledger entries.
- Repeating the same request with the same caller and idempotency key does not transfer funds twice and replays the existing transaction response.
- Reusing an idempotency key for a different request returns `409 CONFLICT`. The focused mismatch test passed on 2026-10-18.
- Concurrent retries with the same key/request are tested to transfer only once.
- Simultaneous transfers are tested against overspending; the verified scenario allows only one competing transfer to succeed.
- Balance overflow failure is tested to roll back wallet balance changes, transaction creation, and ledger entries.
- Historical full-suite checkpoint (2026-10-18): 46 tests, 0 failures, 0 errors, 0 skipped; `BUILD SUCCESS`; packaged artifact: `target/walletledger-0.0.1-SNAPSHOT.jar`.

These test results establish the behaviors covered by the current suite; they do not by themselves establish production readiness, authentication security, load capacity, or completion of deferred modules.

## 19. Open items and scope guardrails

The following are **Open**; they are not represented as completed:

- **Open:** Replace development identity handling with authenticated identity and enforce authorization.
- **Open:** Implement and operate the transactional outbox publisher.
- **Open:** Add API rate limiting, production monitoring, and deployment controls.
- **Open:** Rerun k6 scenarios against the packaged Docker application in a dedicated environment.
- **Not implemented:** Transaction reversal and customer statement APIs.
- **Deferred:** Fraud detection remains out of scope. Do not build fraud features as part of this work.

**Migration safeguard:** Do not edit already-applied Flyway V1/V2 migrations to change the existing schema. Use a new versioned migration for future schema changes.

**Data safeguard:** Preserve the existing development database and Docker volumes during normal development and verification. Do not remove volumes unless intentionally performing a destructive reset.

The existing tests and local benchmarks do not establish production readiness.

## 20. Verified ledger posting and transaction integrity

### Posting validation and rollback evidence

- `LedgerPostingService` performs balanced, atomic ledger posting inside the existing database transaction.
- Posting validates the number of ledger legs, debit/credit totals, duplicate wallet-direction pairs, sufficient funds, and balance overflow before applying balance changes.
- Transaction lifecycle rules prevent invalid state transitions.
- Transfer completion writes transaction audit and outbox records within the same transaction.
- Integration tests cover successful transfers, idempotent retries, competing and opposite-direction transfers, insufficient funds, and rollback when ledger posting or outbox processing fails.
- Database protections reject updates and deletes against append-only ledger and audit records.
- The invariant checker detects integrity problems including completed transactions without the expected ledger entries and inconsistent debit/credit or transaction totals. The later verification also covers cached-wallet-balance reconciliation and the global wallet-balance sum.

These are implementation and test claims, not a guarantee of production readiness. Reversal posting and reversal APIs remain unimplemented.

The ledger core and money-movement integration have progressed beyond the earlier Module 3 snapshot. The latest recorded test baseline is 97 tests across 21 classes, with zero failures, errors, or skipped tests.

Verified behavior includes:

- Double-entry ledger posting with transaction audit and outbox records in the same transaction.
- Wallet-to-ledger and global wallet-balance integrity checks.
- Deterministic wallet locking through `WalletLockOrder`, using unsigned UUID comparison in `LedgerPostingService`, `TransferService`, and `WalletMoneyMovementService`.
- A lock-order parity test, concurrent wallet creation tests, and same-email registration race tests.
- A 2,000-transfer opposite-direction stress test.
- A controlled real-MySQL 1213 deadlock test proving bounded retry recovery.

**Not implemented:** Reversal posting and reversal APIs.

## 21. Earlier combined benchmark and integrity run (2026-10-23)

This is an earlier combined run, distinct from the per-scenario runs documented in Section 22. Its recorded 6,173 iterations must not be added to, or substituted for, the Section 22 total of 6,181 iterations.


### Implementation and verification

- `LedgerInvariantChecker.assertNoViolations()` combines transaction-level ledger checks, wallet cached-balance reconciliation against ledger entries, and the global wallet-balance sum check.
- Wallet reconciliation uses a SQL `GROUP BY` aggregation to compare cached balances against ledger-derived balances, including wallets without ledger entries.
- The global accounting invariant requires the sum of all wallet balances, including the system wallet, to equal zero.
- `MySqlDeadlockRetryExecutor` implements bounded retries for MySQL deadlock error 1213. Lock timeouts and unrelated exceptions are not treated as retryable deadlocks.
- High-volume transfer integration tests include full invariant checks at completion, including the 1,000-transfer wallet-pool test and the 2,000 opposite-direction transfer test.
- A mixed top-up and withdrawal integration test verifies the resulting wallet balance and invokes the full invariant checker.

### Latest automated verification

Verified on 2026-10-23:

- `.\mvnw.cmd clean verify` completed successfully.
- 97 tests passed.
- Failures: 0.
- Errors: 0.
- Skipped: 0.
- The executable Spring Boot JAR was packaged successfully.

The full suite passes, including MySqlDeadlockRetryIntegrationTest. Under controlled Testcontainers conditions, the integration test induced a real MySQL deadlock (error 1213), verified that the bounded retry executor recovered, and confirmed both operations completed with the expected database values.

### k6 concurrency benchmark

Benchmark run ID: `walletledger-20261023-174832`

Workload:

- Hot-pair scenario: 5 VUs for 30 seconds, transferring between two wallets in opposite directions.
- Wallet-pool scenario: ramping workload across 10 funded wallets, increasing to 25 VUs.
- Transfer amount: 1 paise per request.
- Ten benchmark users and wallets were prepared successfully.

Recorded results:

- HTTP request rate: 45.70 requests/second.
- HTTP request failure rate: 0.00%.
- HTTP request duration p90: 160.91 ms.
- HTTP request duration p95: 203.50 ms.
- HTTP request duration p99: 290.47 ms.
- Expected 4xx responses: 0.
- Unexpected 4xx responses: 0.
- Unexpected 5xx responses: 0.
- Unexpected other responses: 0.
- Completed iterations: 6,173.
- Interrupted iterations: 0.

The original run notes recorded the configured HTTP failure-rate and p95 latency thresholds as met. See Section 22 for the later threshold-format diagnostic. These measurements represent a local benchmark and do not establish production capacity.

### Post-benchmark SQL integrity verification

After the benchmark, the following read-only integrity checks were executed against the MySQL `walletledger` database:

- Global wallet balance: `COALESCE(SUM(balance), 0)` returned `0` paise.
- Wallet-to-ledger reconciliation: the query comparing each wallet's cached balance with its ledger-derived balance returned `Empty set`.
- The wallet-to-ledger reconciliation query was executed twice; both executions returned `Empty set`.

These results confirm that the checked database state satisfied the global balance invariant and wallet-to-ledger balance reconciliation after the recorded benchmark.

### Invariant regression coverage

The invariant-checker integration tests cover:

- Detection of a nonzero cached wallet balance with no ledger entries.
- Detection of a cached wallet balance that differs from ledger history.
- Detection of a nonzero global wallet-balance sum.
- Detection of completed transactions with missing ledger entries.
- Balanced and unbalanced transactions, reversed transactions, and SYSTEM wallet reconciliation.

The existing k6 summary export remains associated with benchmark run `walletledger-20261023-174832`.

### Scope boundaries

- The outbox remains available for future integration; fraud-detection functionality is outside the current scope.
- The integration test demonstrates real-MySQL-deadlock recovery under controlled test conditions; it does not establish production readiness or production load capacity.


## Section 22 - Module 4C: Per-scenario concurrency benchmarks and integrity validation (2026-10-24)

These are two separate scenario runs: 1,088 hot-pair iterations plus 5,093 wallet-pool iterations, totalling 6,181. Section 21 records an earlier combined run (6,173 iterations), not a conflicting count for these runs.


### Environment and methodology

- Database: MySQL 8.4.11 running in Docker.
- Runtime: Java 21 and Spring Boot 4.1.1.
- Connection pool: HikariCP, maximum pool size 10.
- Load generator: k6.
- The application, k6, and MySQL ran on the same local machine.
- The application was launched using `.\mvnw.cmd spring-boot:run`, rather than from the packaged Docker image.
- These results are local benchmark observations, not production capacity guarantees.

### Hot-pair benchmark

- Scenario: two wallets under concurrent load.
- Load: 5 constant virtual users for 30 seconds.
- Iterations: 1,088.
- Total HTTP requests, including setup: 1,118.
- Throughput: 35.75 requests/second.
- Average latency: 37.53 ms.
- Median latency (p50): 30.40 ms.
- p90 latency: 56.28 ms.
- p95 latency: 76.82 ms.
- p99 latency: 118.55 ms.
- Maximum latency: 235.17 ms.
- k6 reported HTTP failure rate: 0%.
- Checks passed: 1,088.
- Unexpected 4xx, 5xx, and other responses: 0.

### Wallet-pool benchmark

- Scenario: 10 wallets.
- Load stages: 5 VUs for 30 seconds, 10 VUs for 30 seconds, 25 VUs for 30 seconds, followed by a 10-second ramp-down.
- Total duration: 100 seconds.
- Iterations: 5,093.
- Total HTTP requests, including setup: 5,123.
- Throughput: 50.88 requests/second.
- Average latency: 78.22 ms.
- Median latency (p50): 47.52 ms.
- p90 latency: 176.69 ms.
- p95 latency: 222.69 ms.
- p99 latency: 299.70 ms.
- Maximum latency: 510.70 ms.
- k6 reported HTTP failure rate: 0%.
- Checks passed: 5,093.
- Unexpected 4xx, 5xx, and other responses: 0.

### Integrity and deadlock observations

- The InnoDB deadlock counter was zero before and after each benchmark run.
- Four read-only SQL integrity checks reported zero violations after each benchmark.
- The controlled real-MySQL error 1213 test passed.
- The bounded deadlock retry recovered within its configured maximum of three attempts.
- The test suite contained 97 passing tests, with zero failures, errors, or skipped tests.

### Benchmark threshold caveat

The saved k6 JSON summaries mark all five configured threshold flags as `false`. A diagnostic using an intentionally impossible duration threshold confirmed that `false` indicates a passing threshold in this k6 summary format. The reported HTTP failure rate was 0%, unexpected response counts were zero, and p95 latency was below 500 ms in both runs. For the `http_req_failed` rate metric, `passes: 0` and `fails` equal to the request count are consistent with zero requests having a true failure value. These are local benchmark measurements, not production capacity guarantees.

### Scope boundaries

- These local results do not demonstrate production capacity or production readiness.
- The benchmark was not run against the packaged Docker application.
- Fraud detection remains out of scope; the outbox is retained for future integration.
