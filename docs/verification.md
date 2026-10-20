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

**CI** (2026-10-21): first GitHub Actions run on `main` (commit `ebe4eb7`) green; both jobs
passed (`test`: `./mvnw -B verify` with Testcontainers on ubuntu-24.04; `docker-image`: image
build). Run: https://github.com/MadniAbdulWahab/splitledger/actions/runs/39504551446

## 2026-10-21: day 2, tamper-evident history and search (local)

**Test suite** (`cd api && ./mvnw verify`): 55 tests, 0 failures. After the deadlock fix, the
suite as it was then (46 tests, before the search tests) ran three times in a row without failures.

| New or extended test class | Tests | Covers |
|---|---|---|
| `LedgerChainTest` | 9 | chain linked from genesis, head hash; trigger rejects UPDATE/DELETE/TRUNCATE; detects changed amount, deleted entry, changed entry with recomputed hash; full rewrite changes the head hash; reversal once, balances restored; database allows one reversal per entry |
| `ExpenseConcurrencyTest` | 3 | 16 parallel expenses form one gap-free chain; 16 parallel reversals of one expense: exactly 1 succeeds, 15 get 409 |
| `ExpenseSearchApiTest` | 8 | German word forms, typos, compounds, notes ranked below description, member/date/amount filters, keyset pagination without duplicates, other groups invisible, reversed flag, bad parameters |
| `SearchIndexUsageTest` | 1 | EXPLAIN ANALYZE on 50,000 seeded expenses uses both GIN indexes |

**Found by the tests and fixed:**
- Deadlock with `SELECT ... FOR UPDATE` on the group row under 16 parallel writers; fixed with
  `FOR NO KEY UPDATE` (docs/decisions.md, decision 9).
- Sequential scan (138 ms) right after the bulk insert, caused by the GIN pending list; after
  `VACUUM ANALYZE` the indexes are used (decision 11).

**EXPLAIN ANALYZE**: `q = "Geschenk Jonas"`, one group with 50,000 synthetic expenses (about 50
match), PostgreSQL 16 in Testcontainers, after `VACUUM ANALYZE`:

```
Subquery Scan on page  (cost=217.21..266.23 rows=3 width=62) (actual time=1.318..1.325 rows=21 loops=1)
  Buffers: shared hit=1036
  ->  Limit  (cost=217.21..217.22 rows=3 width=61) (actual time=0.701..0.704 rows=21 loops=1)
        Buffers: shared hit=91
        ->  Sort  (cost=217.21..217.22 rows=3 width=61) (actual time=0.700..0.702 rows=21 loops=1)
              Sort Key: (((ts_rank(e.search_document, '''geschenk'' & ''jonas'' | ''geschenk'' & ''jonas'''::tsquery) + word_similarity('Geschenk Jonas'::text, (e.description)::text)))::double precision) DESC, e.id DESC
              Sort Method: top-N heapsort  Memory: 30kB
              Buffers: shared hit=91
              ->  Bitmap Heap Scan on expense e  (cost=205.50..217.18 rows=3 width=61) (actual time=0.465..0.666 rows=50 loops=1)
                    Recheck Cond: ((search_document @@ '''geschenk'' & ''jonas'' | ''geschenk'' & ''jonas'''::tsquery) OR ('Geschenk Jonas'::text <% (description)::text))
                    Filter: ((group_id = '38'::bigint) AND ((search_document @@ '''geschenk'' & ''jonas'' | ''geschenk'' & ''jonas'''::tsquery) OR ('Geschenk Jonas'::text <% (description)::text)))
                    Rows Removed by Filter: 1
                    Heap Blocks: exact=51
                    Buffers: shared hit=91
                    ->  BitmapOr  (cost=205.50..205.50 rows=3 width=0) (actual time=0.444..0.444 rows=0 loops=1)
                          Buffers: shared hit=40
                          ->  Bitmap Index Scan on expense_search_idx  (cost=0.00..30.03 rows=1 width=0) (actual time=0.061..0.061 rows=51 loops=1)
                                Index Cond: (search_document @@ '''geschenk'' & ''jonas'' | ''geschenk'' & ''jonas'''::tsquery)
                                Buffers: shared hit=5
                          ->  Bitmap Index Scan on expense_description_trgm_idx  (cost=0.00..175.48 rows=3 width=0) (actual time=0.382..0.382 rows=1 loops=1)
                                Index Cond: ((description)::text %> 'Geschenk Jonas'::text)
                                Buffers: shared hit=35
  SubPlan 2
    ->  Nested Loop  (cost=0.29..16.33 rows=1 width=8) (actual time=0.153..0.609 rows=9 loops=1)
          Join Filter: (reversal.reference_id = original.id)
          Rows Removed by Join Filter: 2070
          Buffers: shared hit=945
          ->  Index Scan using ledger_entry_reference_idx on ledger_entry original  (cost=0.14..8.16 rows=1 width=16) (actual time=0.028..0.053 rows=231 loops=1)
                Index Cond: ((kind)::text = 'EXPENSE'::text)
                Buffers: shared hit=21
          ->  Index Only Scan using ledger_entry_reversed_once on ledger_entry reversal  (cost=0.14..8.16 rows=1 width=8) (actual time=0.001..0.002 rows=9 loops=231)
                Heap Fetches: 2079
                Buffers: shared hit=924
Planning:
  Buffers: shared hit=63
Planning Time: 1.432 ms
Execution Time: 1.607 ms
```

**Container smoke test** (`docker compose up --build` after `docker compose down -v`): V2 first
refused to run on the day-1 database as intended. After the reset: two expenses created, one
reversed (`200`, second attempt `409`), balances `+2100 / -2100`, `/verify` valid with 9 entries,
search "Einkauf" found "Einkäufe bei Edeka", "Pizerria" found "Pizzeria Napoli" with
`reversed: true`. A manual `UPDATE ledger_entry` in psql failed with
`ledger_entry is append-only: UPDATE is not allowed, write a reversal instead`.

**CI** (2026-10-21): GitHub Actions run on `main` (commit `50175f7`) green, both jobs, including
the 50,000-row index test on the GitHub runner.
Run: https://github.com/MadniAbdulWahab/splitledger/actions/runs/39507781059
