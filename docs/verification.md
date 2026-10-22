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

## 2026-10-21: day 3, security, bank import, settle-up (local)

**Test suite** (`cd api && ./mvnw verify`): 108 tests, 0 failures.

| New test class | Tests | Covers |
|---|---|---|
| `SecurityApiTest` | 5 | 401 without or with an invalid token, public health and API docs, token stored only as hash, stranger gets 403 on every group endpoint, invite code links an account once |
| `RegistrationCodeTest` | 1 | registration needs the configured code |
| `ParsingBuildingBlocksTest` | 19 | German amounts (incl. `-1.240,00`) and dates, Windows-1252/UTF-8/BOM, quoted CSV fields |
| `SparkasseCsvCamtParserTest` | 5 | synthetic export, pending rows, columns by name and with umlauts, unreadable rows with line numbers, foreign formats rejected |
| `FingerprintsTest` | 5 | two identical coffees stay two, same rows in another export match, whitespace, bank reference |
| `BankImportApiTest` | 8 | same file twice (8 new, then 8 duplicates), overlapping file (3 new, 4 duplicates, 2 skipped, both coffees kept), confirmation creates a searchable expense and 409 on repeat, ignore deletes personal data, privacy between members, foreign account rejected, foreign format rejected, 4 parallel uploads store 8 rows |
| `SettlementPlannerTest` | 5 | greedy plan, counterexample (4 vs. 3 payments), 5,000 random groups vs. brute-force optimum (minimal in 4,987) |
| `SettlementApiTest` | 3 | suggested payments settle the group, payment idempotency, invalid payments, payment table append-only |
| `OpenApiDocsTest` | 1 | `/v3/api-docs` lists every endpoint and the bearer scheme; Swagger UI reachable |

**Demo** (`scripts/demo.sh` against `docker compose up --build` after `docker compose down -v`):

```
== Anna imports her March statement, the same file again, then an overlapping one
sparkasse-csv-camt-2026-03-a.csv     new=8 duplicates=0 skipped=1
sparkasse-csv-camt-2026-03-a.csv     new=0 duplicates=8 skipped=1
sparkasse-csv-camt-2026-03-b.csv     new=3 duplicates=4 skipped=2

== Anna confirms two shared payments and ignores the rent
expense 1: EDEKA Center Bonn, 5412 cents
expense 2: Lidl Bonn, 2345 cents
rent ignored; Ben sees 0 of Anna's bank transactions

== Ben adds a pizza night; his client retries the request after a timeout
attempt 1: Idempotent-Replayed=false
attempt 2: Idempotent-Replayed=true

== Balances and suggested settle-up
{"groupId":2,"balances":[{"memberId":3,"displayName":"Anna","balanceCents":2078},{"memberId":4,"displayName":"Ben","balanceCents":-2078}]}
{"groupId":2,"payments":[{"fromMemberId":4,"fromName":"Ben","toMemberId":3,"toName":"Anna","toIban":"DE81000010000001234567","toBic":null,"amountCents":2078}]}

== Ben pays Anna by bank transfer and records it
{"groupId":2,"balances":[{"memberId":3,"displayName":"Anna","balanceCents":0},{"memberId":4,"displayName":"Ben","balanceCents":0}]}

== Hash chain
{"groupId":2,"valid":true,"entriesChecked":11,"headHash":"64d77cd4c662d7bf19aca9883f772975ef1879123a5b3261955e4358e0020327","balanced":true,"firstBrokenEntry":null}

== Search with a typo
"description":"Lidl Bonn"
```

Checked by hand: EDEKA 54.12 paid by Anna (+27.06), Lidl 23.45 (Anna's share 11.73 under the
remainder rule, so +11.72), pizza 36.00 paid by Ben (-18.00): Anna +20.78. 3 expenses x 3 entries
plus 2 payment entries = 11 chain entries.

The first demo run stopped with 422 because of a bug in the script's JSON helper (it took the last
`id` in a list, so Anna and Ben got the same member id); the API correctly rejected the duplicate
participant. Fixed in the script.

**CI** (2026-10-21): GitHub Actions run on `main` (commit `5968b32`) green, both jobs.
Run: https://github.com/MadniAbdulWahab/splitledger/actions/runs/39514641087

## 2026-10-21: day 4 start, AWS account and cost guard

- New AWS account on the **Free plan** (credits, card not charged; Organizations and IAM Identity
  Center deliberately not enabled, because either would switch the account to the paid plan).
- Root user protected with MFA, no root access keys. Everyday work uses the IAM user
  `madni-admin` (MFA) through `aws login`: short-lived CLI credentials, no access keys on disk.
- `infra/bootstrap` applied in `eu-central-1`: `Apply complete! Resources: 1 added, 0 changed,
  0 destroyed.` `aws budgets describe-budgets` shows `splitledger-monthly`, limit 5.0 USD,
  `IncludeCredit: False` (usage is measured before credits, so the alert also works on the Free plan).
  Email alerts at 50 % and 100 % actual and 100 % forecast.

## 2026-10-22: day 4, the API runs on AWS (ECS Fargate + RDS), provisioned only by Terraform

**Least-privilege database role** (before deploying): `AppRolePrivilegesTest` (14 tests) checks
that `splitledger_app` can do what the application needs and gets `permission denied` / `must be
owner` for UPDATE/DELETE on history tables, DELETE on members, reading `flyway_schema_history`,
disabling a trigger and dropping a table; the role logs in with a password containing quotes and
`; drop table member; --`. Full suite: 122 tests, 0 failures. `scripts/demo.sh` passes against
Docker Compose with the application connected as `splitledger_app` (seen in `pg_stat_activity`).

**Apply** (`infra/app`, region eu-central-1):

```
Plan: 33 to add, 0 to change, 0 to destroy.
aws_db_instance.main: Creation complete after 7m38s [id=db-VIWGCNPD247Y3Q2RNVK3HLQ76Q]
Apply complete! Resources: 33 added, 0 changed, 0 destroyed.
```

**Secrets not in the state:** after apply, `terraform.tfstate` contains `"password": null`,
`"password_wo": null` for RDS and an empty `"value"` / `"value_wo": null` for all three SSM
parameters; the plan showed `password_wo = (write-only attribute)`.

**Image:** built and pushed with `scripts/push-image.sh`, tag `b11a219302ee` (git commit), digest
`sha256:0891e35ba11eae2041898006fd21d9945b3483949bdd968a8e1a58481e0c143a`. Second apply with
`api_desired_count=1`: `Apply complete! Resources: 1 added, 1 changed, 1 destroyed.` (new task
definition revision replaces the old one).

**CloudWatch Logs** (`/ecs/splitledger-api`), first task:

```
Successfully applied 7 migrations to schema "public", now at version v7 (execution time 00:00.678s)
Login password of database role splitledger_app set
Started SplitledgerApiApplication in 58.2 seconds (process running for 60.401)
```

**Network checks:**

```
aws rds describe-db-instances: public=false, encrypted=true, class=db.t4g.micro, engine=16.13
nslookup <rds endpoint>: Address 10.20.10.89 (private)
TCP connect to <rds endpoint>:5432 from the internet: not reachable
aws ec2 describe-nat-gateways (available/pending): 0
```

**Demo against AWS** (`BASE_URL=$(scripts/aws-api-url.sh)`, registration code from SSM). Without
the code, `POST /users` returned `403`. With it:

```
== Anna imports her March statement, the same file again, then an overlapping one
sparkasse-csv-camt-2026-03-a.csv     new=8 duplicates=0 skipped=1
sparkasse-csv-camt-2026-03-a.csv     new=0 duplicates=8 skipped=1
sparkasse-csv-camt-2026-03-b.csv     new=3 duplicates=4 skipped=2

== Anna confirms two shared payments and ignores the rent
expense 4: EDEKA Center Bonn, 5412 cents
expense 5: Lidl Bonn, 2345 cents
rent ignored; Ben sees 0 of Anna's bank transactions

== Ben adds a pizza night; his client retries the request after a timeout
attempt 1: Idempotent-Replayed=false
attempt 2: Idempotent-Replayed=true

== Balances and suggested settle-up
{"groupId":2,"balances":[{"memberId":3,"displayName":"Anna","balanceCents":2078},{"memberId":4,"displayName":"Ben","balanceCents":-2078}]}
{"groupId":2,"payments":[{"fromMemberId":4,"fromName":"Ben","toMemberId":3,"toName":"Anna","toIban":"DE81000010000001234567","toBic":null,"amountCents":2078}]}

== Ben pays Anna by bank transfer and records it
{"groupId":2,"balances":[{"memberId":3,"displayName":"Anna","balanceCents":0},{"memberId":4,"displayName":"Ben","balanceCents":0}]}

== Hash chain
{"groupId":2,"valid":true,"entriesChecked":11,"headHash":"f23b981805f1b4e2a35be232f1763775312aa969cf286efaf68fdbe38ddf0286","balanced":true,"firstBrokenEntry":null}

== Search with a typo
"description":"Lidl Bonn"
```

(Group 2: a first run had stopped at the retry step because of a Windows path issue in the script,
fixed; its group 1 stays as harmless test data.)

**Problems on the way, all in tooling, not in the application:** `docker login` to ECR fails with
the Windows Credential Manager (token too long), solved with a throwaway Docker config in
`push-image.sh`; Git Bash path conversion, solved with `cygpath` in the scripts.
