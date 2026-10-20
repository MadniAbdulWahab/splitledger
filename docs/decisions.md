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

## 7. Hash chain per group (2026-10-21)

Each ledger entry stores `seq` (1, 2, 3, ... per group), `previous_hash` and
`hash = SHA-256(previous_hash | group | seq | member | amount | kind | reference | created_at)`.
`GET /groups/{id}/verify` walks the chain and reports the first entry where `seq` has a gap
(entry deleted), `previous_hash` does not match (entry removed or reordered) or the hash does not
match the content (entry changed). Changing an entry and recomputing its own hash is still caught,
because the next entry points to the old hash.

One chain per group, not one global chain: groups are independent, so writers of different groups
never wait for each other, and a group can be verified on its own.

The format lives in one class (`LedgerHasher`) used by both writing and verifying. The database
id is not hashed because it is only known after the insert; `seq` fixes the position instead.
`created_at` is hashed as epoch microseconds, exactly what PostgreSQL stores.

**Limit:** whoever controls the database can change an entry and recompute every hash after it.
The chain is then valid again, but it ends in a different head hash. `/verify` returns the head
hash so members can note it (for example in the group chat after settling up); a test shows that a
full rewrite changes it. Anchoring the head hash outside the database closes that gap.

## 8. Append-only enforced by PostgreSQL (2026-10-21)

A trigger rejects UPDATE, DELETE and TRUNCATE on `ledger_entry`, `expense` and `expense_share` for
every role, including the table owner. Tests check each statement and also simulate an attacker
with superuser rights (`SET LOCAL session_replication_role = replica` turns triggers off for one
transaction), who can then change rows, which `/verify` detects.

**Planned for day 4:** the application will connect as its own role with only SELECT and INSERT on
these tables, while Flyway migrates as the owner. Then the application cannot disable the trigger
either. It is deferred because creating that role's password belongs together with the RDS and SSM
secret handling.

## 9. Group row lock with FOR NO KEY UPDATE (2026-10-21)

Two transactions appending to the same group would both read the same head and fork the chain. Every
writer therefore locks the group row first; `unique (group_id, seq)` is the database's backstop.

The first version used `SELECT ... FOR UPDATE` and the concurrency test deadlocked: inserting an
expense takes `FOR KEY SHARE` on the group row (foreign key check), both transactions held it, and
both then waited for `FOR UPDATE`, which conflicts with the other's `KEY SHARE`.
`FOR NO KEY UPDATE` still lets only one appender through but is compatible with foreign key
checks. It is the lock PostgreSQL itself takes for an UPDATE that does not change the key.

## 10. Corrections are reversals (2026-10-21)

`POST /groups/{id}/expenses/{expenseId}/reversal` writes one REVERSAL entry per original entry, with
the opposite amount and `reference_id` = the original entry's id. The original expense and entries
stay unchanged, so the history shows the mistake and its correction. A partial unique index
(`reference_id where kind = 'REVERSAL'`) lets each entry be reversed at most once; the service also
checks this under the group lock and answers `409`. A wrong expense is reversed and entered again.

An idempotent replay returns the expense as it is now. That equals the original response, except
that `reversed` becomes true once the expense has been reversed.

## 11. Search with PostgreSQL, not a search engine (2026-10-21)

The data is small and already in PostgreSQL, so no Elasticsearch or OpenSearch to run and pay for.

- A generated `tsvector` column combines the `german` configuration (stemming: "Einkäufe" and
  "Einkauf" share the stem `einkauf`; stopwords dropped) and `simple` (words exactly as typed, for
  names and shops). Description has weight A, notes weight B. GIN index.
- A trigram GIN index on the description handles typos ("Pizerria") and words inside German
  compounds ("Einkauf" in "Wocheneinkauf"), which the German stemmer does not split.
- A row matches if either matches; PostgreSQL combines both indexes with a BitmapOr.
- The word similarity threshold is 0.5 instead of the default 0.6: one-letter typos in short words
  ("Lidel") score exactly 0.5. Checked against typical expense words; the only extra hit was
  "Abo" matching "Abschlag", which ranks below real matches.
- Ranking: `ts_rank + word_similarity`. Keyset pagination on `(score, id)`: unlike OFFSET it never
  skips or repeats rows when new expenses arrive, and later pages are as fast as the first.
- The SQL is built from fixed fragments with only the filters that are set (values are always bound
  parameters), so PostgreSQL plans the actual query instead of a generic `(:x is null or ...)` one.

**GIN pending list:** right after bulk-inserting 50,000 rows the planner chose a sequential scan
(138 ms). GIN first collects new rows in an unsorted pending list that every search has to scan;
VACUUM merges it into the index. After `VACUUM ANALYZE` the same query used both indexes and took
about 1.6 ms. Autovacuum does this in normal operation; after a bulk load, run VACUUM.

## 12. Schema changes before the first deployment (2026-10-21)

V2 adds NOT NULL hash columns. Instead of carrying a backfill for data that exists nowhere except
local development databases, the migration stops with a clear message if `ledger_entry` is not
empty (`docker compose down -v` resets it). Applied migrations are never edited: Flyway would
reject the changed checksum. Once real data exists, schema changes follow expand, backfill,
contract.
