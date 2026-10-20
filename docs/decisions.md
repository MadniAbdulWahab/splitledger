# Design decisions

Short records of decisions and their reasons. Newest last.

## 1. Money as integer cents (2026-10-20)

All amounts are `long` cents in Java and `bigint` in PostgreSQL. Binary floating point cannot
represent 0.10 exactly, so sums drift; `BigDecimal` would work but invites mixing scales. With one
currency (EUR) and two decimals, integer cents are exact, fast and impossible to misuse silently.
Amounts are capped at 1,000,000.00 EUR per expense, so `amount * share units` never overflows
(and `Math.multiplyExact` would throw if it did).

## 2. Split remainders: largest remainder, ties to the lower member id (2026-10-20)

10.00 EUR over three people cannot be split evenly. Every participant first gets
`floor(amount * weight / totalWeight)`; the leftover cents (always fewer than the number of
participants) go one each to the participants with the largest dropped fraction, and ties go to the
lower member id. Result for 10.00 / 3: 3.34, 3.33, 3.33.

Why: the result is deterministic (same input, same split, regardless of the order of participants
in the request), every share is the exact proportional value rounded down or up and never off by
more than one cent, and the parts always add up to the amount. A randomized test checks these
properties over 10,000 splits.

## 3. Idempotency key stored on the expense (2026-10-20)

`POST /groups/{id}/expenses` requires an `Idempotency-Key` header. The key and a SHA-256 hash of the
canonical request body are stored on the expense row with `unique (group_id, idempotency_key)`.

- Same key, same body: the stored expense is returned again (`201`, `Idempotent-Replayed: true`).
- Same key, different body: `409 Conflict`; the client has a bug.
- Two identical requests at the same moment: both miss the lookup, both try to insert, PostgreSQL
  lets exactly one win. The loser's transaction is rolled back and it reads the winner in a new
  transaction, which is why the service uses `TransactionTemplate` instead of one `@Transactional`
  method. The concurrency test showed 14 of 16 simultaneous requests taking this path.

Expenses are immutable, so the response can be rebuilt from the row; no separate table of stored
responses is needed. (The idempotency key protects API retries; bank-import fingerprints, coming on
day 3, protect against re-importing the same file. They solve different problems.)

## 4. Balances are derived, never stored (2026-10-20)

A member's balance is `SUM(ledger_entry.amount_cents)`. There is no balance column that two
concurrent expenses could both read, change and overwrite (lost update). Every business event writes
entries that sum to zero (payer `+amount`, each participant `-share`), checked in code before the
insert and verified in tests, so the balances of a group always sum to zero.

## 5. Integrity in the database, not only in Java (2026-10-20)

Composite foreign keys such as `(group_id, payer_id) -> member(group_id, id)` make PostgreSQL reject
a payer, participant or ledger entry that belongs to a different group, even if a service has a bug.
Flyway owns the schema; Hibernate only validates that entities match it (`ddl-auto: validate`).
The ledger repository extends Spring Data's bare `Repository` and declares no update or delete
method; day 2 adds the database-level guard.

## 6. Spring Boot 4 instead of 3 (2026-10-20)

The plan said Spring Boot 3.x, but open-source support for 3.5 ended in mid-2026 and Spring
Initializr only offers 4.x. The project uses Spring Boot 4.1.1 (Spring Framework 7, Hibernate 7,
Jackson 3) on Java 21.
