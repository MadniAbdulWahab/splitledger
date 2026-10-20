# Verification evidence

Dated records of what actually ran. Only what is listed here may be claimed.

## 2026-10-20: day 1, ledger core (local)

Environment: Windows 11, Temurin 21.0.8, Docker 28.4, PostgreSQL 16 (`postgres:16-alpine`).

**Test suite** (`cd api && ./mvnw verify`): 36 tests, 0 failures.

| Test class | Tests | Covers |
|---|---|---|
| `IbanTest` | 11 | mod-97 check, swapped digits, normalization |
| `SplitCalculatorTest` | 9 | remainder rule, shares, exact, input order, 10,000 random splits |
| `GroupApiTest` | 4 | IBAN normalization, problem details, duplicate names, 404 |
| `ExpenseApiTest` | 10 | zero-sum ledger, balances, replay, 409 on changed body, group isolation |
| `ExpenseConcurrencyTest` | 2 | 16 threads with one key create exactly one expense; 16 parallel expenses keep the group balanced |

In the same-key concurrency test, 14 of 16 threads hit the `expense_idempotency_key_unique`
constraint and were answered with the winning expense (seen in the test log).

**Container smoke test** (`docker compose up --build`): API healthy via
`/actuator/health/readiness`, process runs as uid 10001 (non-root). Over HTTP: expense created
(`201`), same key and body replayed (`201`, `Idempotent-Replayed: true`), same key with a changed
amount rejected (`409`), balances `+500 / -500`, invalid IBAN rejected (`400` with field error).
