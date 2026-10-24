# WalletLedger - Architecture Decisions

Last updated: 2026-10-18
Scope: Architecture decisions and verified implementation status through the transfer/idempotency milestone

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

**Decision:** Configure READ COMMITTED and use explicit ordered
pessimistic row locking for money movement.

**Why:** MySQL defaults to REPEATABLE READ, which can introduce additional
gap-lock behavior. READ COMMITTED is selected to reduce unnecessary
locking surprises; it does not eliminate all deadlocks.

Implementation rule for the money-movement module: acquire wallet locks
in one consistent order, comparing the canonical 16 UUID bytes
lexicographically as unsigned bytes. Use one comparator everywhere.

Implementation status (2026-10-18): money movement is implemented and integration-tested. Wallet rows are locked in UUID order by the repository query. The canonical unsigned-byte comparator described above is the intended architecture rule; verify the implementation uses that exact comparator consistently before treating this detail as fully confirmed.

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
- Automated Maven suite: 9 tests passed, with zero failures and zero errors.

## 17. Deliberately deferred from Module 1

The following were not implemented in Module 1. This is a historical scope record, not a statement that each item remains unimplemented today:

- User and wallet business APIs (implemented in the subsequent users/wallets milestone).
- Money movement and concurrency-safe transfers (implemented and integration-tested by 2026-10-18).
- Idempotency response-replay service logic (implemented and integration-tested for retries and request mismatch by 2026-10-18).
- Reversal service logic (status not confirmed by the verification recorded here; database reversal constraints are tested).
- Redis rate limiter (deferred; see Section 13).
- Kafka publisher (deferred; see Section 12).
- JWT authentication (deferred; see Section 14).
- Fraud detection (explicitly deferred; do not implement in the current scope).
- Performance benchmarks (not confirmed as completed).

These belong to subsequent modules and must not be represented as
completed features on the CV.
## Module 2 - Users and Wallets

### 1. User Registration
- Users register through `POST /api/v1/users`.
- Registration is public and does not require the `X-User-Id` header.
- Names are trimmed, and email addresses are trimmed and normalized to lowercase.
- User IDs are generated as UUIDs in the application.
- Email uniqueness is enforced by the database using `uq_users_email`.
- Duplicate email registration returns `409 CONFLICT`.
- The API returns the database-generated creation timestamp.

### 2. Wallet Creation
- Users create wallets through `POST /api/v1/wallets`.
- Each user can have only one wallet per currency.
- Wallets are created with currency `INR` and an initial balance of zero.
- Wallet IDs are generated as UUIDs in the application.
- The database constraint `uq_wallets_user_currency` enforces wallet uniqueness.
- Wallet creation does not perform money transfers or modify another wallet.

### 3. Balance Representation
- Wallet balances are stored as `BIGINT` values in paise.
- API responses expose the amount through `balancePaise`.
- Integer paise avoids floating-point arithmetic for monetary amounts.

### 4. Wallet Ownership and Access
- `GET /api/v1/wallets/{id}/balance` returns a wallet balance only to its owner.
- A wallet not owned by the caller returns `404 NOT_FOUND`, avoiding disclosure of another user's wallet.
- Protected `/api/**` endpoints require a valid `X-User-Id` UUID.
- `X-User-Id` is a development-only identity mechanism, not production authentication.

### 5. API Error Handling
- Invalid request data returns `400 BAD_REQUEST`.
- Missing identity headers return `401 UNAUTHENTICATED` on protected endpoints.
- Duplicate email or wallet creation returns `409 CONFLICT`.
- Unexpected errors are handled by the global exception handler.

### 6. Database and Testing
- Existing Flyway migrations V1 and V2 remain unchanged.
- Automated integration tests cover user registration, duplicate email handling, wallet creation, duplicate wallet prevention, balance retrieval, and ownership enforcement.
- Historical verification: the 15 October 2026 full Maven run passed 18 tests.
- Latest full verification on 18 October 2026: `mvnw.cmd clean verify` passed 46 tests with zero failures, zero errors, and zero skipped tests. The executable Spring Boot JAR was generated.
- A focused idempotency mismatch test passed: reusing a key with a different amount returns `409 CONFLICT` and does not move money a second time.
- Concurrent wallet-creation error handling has not been explicitly confirmed by the test evidence recorded here; retain it as a verification item.

### 7. Scope Boundaries
- The original Module 2 scope was user registration, wallet creation, and owner-scoped balance retrieval.
- Transfer processing and idempotency are now implemented in the subsequent development work and have passing integration tests; they should not be described as still unimplemented.
- Reversal service behavior, Redis rate limiting, Kafka publishing, JWT authentication, fraud detection, and performance benchmarking should be treated as deferred or unverified unless a later section records completion.


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
- Latest full suite: 46 tests, 0 failures, 0 errors, 0 skipped; `BUILD SUCCESS`; packaged artifact: `target/walletledger-0.0.1-SNAPSHOT.jar`.

These test results establish the behaviors covered by the current suite; they do not by themselves establish production readiness, authentication security, load capacity, or completion of deferred modules.

## 19. Open verification items and guardrails

- Explicitly verify concurrent wallet creation returns a stable `409 CONFLICT` for the losing request.
- Confirm the transfer lock ordering exactly matches the canonical unsigned lexicographic UUID-byte rule in Section 10.
- Keep `X-User-Id` limited to local development/testing; replace it with verified JWT or another proper authentication mechanism before production.
- Do not edit already-applied Flyway V1/V2 migrations to change existing schema. Use a new versioned migration for future schema changes.
- Preserve the existing development database and Docker volumes during normal development and verification.
- Do not implement fraud detection in the current scope. Keep the outbox schema available for a later publisher/integration module.

## 20. Verified Module 3 - Ledger Posting and Transaction Integrity (2026-10-19)

### Implemented and verified

- `LedgerPostingService` performs balanced, atomic ledger posting within an existing database transaction.
- Posting validates the number of legs, debit/credit totals, duplicate wallet-direction pairs, sufficient funds, and balance overflow before applying changes.
- Wallet rows are locked in a consistent UUID-string order during posting and transfers.
- Transaction lifecycle rules prevent invalid state transitions.
- Transfer completion records audit and outbox data within the transaction.
- Integration tests cover successful transfers, idempotent retries, competing transfers, opposite-direction transfers, insufficient funds, and rollback when posting or outbox processing fails.
- Database protections reject updates and deletes to append-only ledger and audit records.
- `LedgerInvariantChecker` detects completed or reversed transactions with missing ledger entries or inconsistent debit, credit, and transaction totals.
- The latest `mvnw.cmd clean verify` run on 2026-10-19 passed 66 tests with zero failures, zero errors, and zero skipped tests. The executable JAR was packaged successfully.

### Remaining verification and scope boundaries

- Concurrent wallet creation and concurrent duplicate-email registration still need dedicated concurrency verification.
- Explicit posting tests for every planned transaction type (TOPUP, WITHDRAWAL, and REVERSAL) are not confirmed by the current test inventory.
- A separate thousands-of-iterations concurrency stress test has not been confirmed. Existing high-volume integration tests and the recorded k6 benchmark are documented in Section 21.
- Verify that the implementation's UUID lock ordering matches the canonical unsigned-byte ordering rule in Section 10 before claiming that exact rule is implemented.
- Kafka publishing, Redis rate limiting, production authentication, and fraud detection remain deferred or unverified.
- Section 21 records the verified wallet reconciliation checks, global balance invariant checks, and real-MySQL deadlock-retry test.

The passing test suite verifies only the behaviors exercised by those tests. The local benchmark and controlled integration tests do not establish production readiness or completion of deferred features.

## 21. Verified Wallet Integrity and Concurrency Status (2026-10-23)

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

The run met the configured HTTP failure-rate and p95 latency thresholds. These measurements represent a local benchmark and do not establish production capacity.

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


## Section 22 — Module 4C: Concurrency Benchmark and Integrity Validation (2026-10-24)

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

The k6 JSON summaries reported inconsistent threshold results: all five configured threshold evaluations were marked failed, including `http_req_failed`, even though the reported failure rate was 0%, the recorded requests had no unexpected responses, and p95 latency was below 500 ms in both runs.

Therefore, do not claim that all k6 thresholds passed. The threshold discrepancy requires separate investigation before those threshold results can be treated as valid. The observed request metrics and passing checks are recorded above as reported measurements.

### Scope boundaries

- These local results do not demonstrate production capacity or production readiness.
- The benchmark was not run against the packaged Docker application.
- Fraud detection remains out of scope; the outbox is retained for future integration.
