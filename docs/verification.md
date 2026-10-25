# Verification evidence

Dated records of what actually ran. Only what is listed here may be claimed.

Commit ids and image tags below are the ones recorded at the time. On 2026-10-22 the history was
rewritten to drop one file, which changed every commit id but no code; the cited commits are now:

| Recorded | Now |
|---|---|
| `ebe4eb7` | `3f38a8a` |
| `50175f7` | `34d4949` |
| `5968b32` | `a573641` |
| `b11a219` | `de88b63` |
| `9301c89` | `6b30a0f` |
| `31acdbe` | `43dbd96` |
| `28e5dfb` | `46491f4` |

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

## 2026-10-22: day 5, deploy pipeline and observability

**Test suite:** 124 tests, 0 failures (`StructuredLoggingTest` added: JSON request line with
`requestId`, `groupId`, `method`, `path`, `status`, `durationMs`; rejected requests logged; unsafe
`X-Request-Id` replaced).

**Terraform** (`infra/app`): `Apply complete! Resources: 12 added, 0 changed, 1 destroyed.` (SNS
topic and email subscription, 2 metric filters, 3 alarms, dashboard, GitHub OIDC provider, deploy
role and policy; the task definition got a new revision with JSON logging). Fix after the first
deploy attempt: `Resources: 0 added, 1 changed, 0 destroyed.` (OIDC subject with repository ids).

**Deploy workflow** (Actions -> Deploy, commit `9301c89`): attempt 1 failed with
`Not authorized to perform sts:AssumeRoleWithWebIdentity`; CloudTrail showed the token subject
`repo:MadniAbdulWahab@105889425/splitledger@1427604325:environment:aws`, the trust policy was
corrected. Attempt 2 failed the CI check (the run list did not yet return the finished CI run);
the check now retries and waits. Attempt 3: **success**, 9 min 15 s including the wait for CI;
summary `Deployed 9301c894db60 to http://18.199.142.125:8080`.

**After the deployment, from AWS:**

```
service uses: task-definition/splitledger-api:4
image: .../splitledger-api:9301c894db60, LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs
running=1, rollout=COMPLETED, deployments=1
```

`scripts/demo.sh` against the new task: all steps as on day 4 (8 new / 8 duplicates / 3 new + 4
duplicates; replay `true` on retry; balances 0 after settle-up; chain valid, 11 entries; "Lidel"
finds "Lidl Bonn"). Groups 1 and 2 from day 4 were still in the database after the deployment.

**CloudWatch:**

```
{"@timestamp":"2026-10-22T16:31:26.906868942Z","log":{"level":"INFO","logger":"dev.splitledger.common.RequestLoggingFilter"},...,"message":"GET /groups/3/verify -> 200","requestId":"ebea58fc-d2b1-4bcc-82e6-c59004a076fa","groupId":"3","method":"GET","path":"/groups/3/verify","status":200,"durationMs":61,"ecs":{"version":"8.11"}}
```

- Filter `{ $.requestId = "1c7432d4-..." }` returns the lines of one import request
  (`POST /groups/3/imports -> 201`, 230 ms).
- Metric `SplitLedger/Requests`: 26 requests in the 5-minute period of the demo run.
- Alarm path: `set-alarm-state` put `splitledger-api-5xx-rate` into ALARM at 18:32:12; history shows
  `Successfully executed action arn:aws:sns:eu-central-1:718543338703:splitledger-alarms`; back to
  OK at 18:33:55. The SNS email subscription is confirmed. All three alarms OK afterwards.

**Free-host setup** (`deploy/free-host`, run locally with `DOMAIN=localhost`): HTTPS 200 on
`/v3/api-docs`, HTTP redirects with 308 to HTTPS, `/actuator/health` 404 from outside, API port
not reachable directly, registration 403 without and 201 with the code, API connected as
`splitledger_app`. Not yet run on a real VM with a public domain (Let's Encrypt not exercised).

**Screenshots** (2026-10-22): [CloudWatch dashboard](evidence/2026-10-22-cloudwatch-dashboard.png):
26 requests in one minute during the demo, no 5xx; task CPU spiking at start-up then near 0 %,
memory about 60 % of 0.5 GB; RDS CPU low with 10 connections (the Hikari pool size); all three
alarms OK; the "failed or slow requests" table lists the first two requests after the deployment
(`POST /users` 1131 ms, `POST /groups` 1006 ms), the JVM warming up on 0.25 vCPU. The two alarm
emails arrived (ALARM at 16:32:12 UTC with the test reason, OK at 16:33:55 UTC); they are not
committed because they contain the topic's unsubscribe link.

## 2026-10-22: day 6, outbox, SQS, Kotlin Lambda, S3 statements

**Tests:** API 131 tests, 0 failures (`OutboxTest`: one event per expense, reversal and payment
with its postings, none for a rejected request, publisher stops at the first SQS failure and marks
only what was sent; `OutboxPublisherSqsTest`: real delivery to an ElasticMQ FIFO queue, ordered,
message group per expense group, nothing sent twice; app-role checks for `outbox_event`).
Statement worker (Kotlin) 12 tests, 0 failures (`MonthlyStatementTest`, `StatementHandlerTest`
including the rest-of-batch retry after a failure, `S3StatementStoreTest` against S3Mock). The
first run found an error in the test data (an odd amount split into two equal halves, so the
postings did not sum to zero); the zero-sum invariant test caught it.

**Terraform:** `Apply complete! Resources: 15 added, 1 changed, 1 destroyed.` (FIFO queue and DLQ,
redrive allow policy, S3 bucket with public access block, ownership controls and encryption,
Lambda with log group, role and event source mapping, API task role policy, DLQ alarm; dashboard
updated; new task definition revision with the queue URL).

**Deployment** through the GitHub workflow: commit `31acdbe`, success in 6 min 48 s, service on
task definition revision 6 with image `31acdbe7a900` and `SPLITLEDGER_OUTBOX_QUEUE_URL` set; the
API log shows `Successfully applied 1 migration` (V8). `scripts/demo.sh` passed (group 4).

**Events end to end:** the Lambda log shows four messages `APPLIED` (two confirmed bank payments,
the pizza, the settle-up payment); queue and DLQ empty afterwards. S3:

```
statements/group-4/2026-03.json   732 bytes
statements/group-4/2026-10.json   725 bytes
```

March statement: `expenseCount 3`, `expensesCents 11357` (54.12 + 23.45 + 36.00 EUR); Anna
`paidCents 7757`, `shareCents 5679`, `netChangeCents 2078`; Ben `paidCents 3600`, `shareCents
5678`, `netChangeCents -2078`, which equals the balances before the settle-up. October statement:
`paymentCount 1`, `paymentsCents 2078`. Lambda timings: cold start `Init Duration 851 ms`, first
invocation 2468 ms, then 109-155 ms; `Max Memory Used 171 MB` of 512.

**Dead-letter queue and alarm:** an invalid message sent by hand to the queue (own message group
`dlq-test`, 17:20:11 UTC) failed three times (`MissingFieldException` in the worker log), was in the
DLQ at 17:29:17, and `splitledger-ledger-events-dlq` went to ALARM at 17:31:03 with
`Successfully executed action ...:splitledger-alarms`. The DLQ held only that message
(`{"note":"deliberately invalid event to test the dead-letter queue"}`) and was purged afterwards.
The alarm returned to OK (about 17:38 UTC) after the purge.

## 2026-10-22: day 7, GiroCode and the web page

**Tests at the final commit `28e5dfb`:** API 146 tests, statement worker 12 tests, web 27 unit tests
(money, idempotency keys, split rule incl. 5,000 random splits), all passing; CI run
https://github.com/MadniAbdulWahab/splitledger/actions/runs/39855011411 green with five jobs
(`test`, `statement-worker`, `web`, `docker-image`, `e2e`). New API tests: `GiroCodeTest` (payload
line by line, field and byte limits with umlauts, invalid input, PNG decoded back with ZXing),
GiroCode endpoint tests, payer-only expense rejected, `DebtsApiTest` (pairwise debts incl. the
example "Madni owes Lucy 10, Tim owes Madni 5", netting, payments, reversals, randomized check that
pairwise debts add up to the balances), web page and Content-Security-Policy access rules.

**Found by trying it on AWS and fixed:** adding an expense failed on the plain-HTTP demo
(`crypto.randomUUID is not a function`); fixed with a fallback and a test (decision 30). Feedback
from use led to closable messages, a full history with payments, an icon bar on phones, exact and
share splits, and the pairwise "who owes whom" view.

**GiroCode on a phone:** the code shown by the AWS deployment was scanned with a Sparkasse banking
app, which filled in receiver, IBAN, amount and transfer text (checked, not sent).

**Final deployment** through the GitHub workflow: commit `28e5dfb`, task definition revision 11,
image `28e5dfb0de06`. Against it, over plain HTTP:

```
npm run e2e -- http://18.185.110.237:8080      Walkthrough passed: desktop and phone
scripts/demo.sh                                 exit 0, chain valid with 11 entries
```

Screenshots from AWS: [desktop](evidence/2026-10-22-web-page-on-aws.png),
[phone, dark mode](evidence/2026-10-22-web-page-on-aws-phone.png).

**Deploy history (all through GitHub OIDC):** 9 runs; the first two failed (trust policy subject,
then the CI check), the seven after them succeeded.

## 2026-10-22: stack destroyed

After the evidence above, `terraform destroy` in `infra/app` (21:22 UTC):

```
Plan: 0 to add, 0 to change, 58 to destroy.
aws_db_instance.main: Destruction complete after 1m51s
Apply complete! Resources: 0 added, 0 changed, 58 destroyed.
terraform state list: 0 resources
```

Checked in the account afterwards: no RDS instances, automated backups or snapshots (the automated
snapshot `rds:splitledger-db-2026-10-22-15-29` was removed by AWS a few minutes after the instance),
no ECS clusters, ECR repositories, Lambda functions, SQS queues, S3 buckets, non-default VPCs, NAT
gateways, Elastic IPs, SplitLedger log groups, SSM parameters or alarms. Only the budget
`splitledger-monthly` from `infra/bootstrap` remains, as intended.

The application stack ran from 15:28 to 21:22 UTC (about six hours, roughly 0.30 USD of Free-plan
credits). It can be recreated from Terraform in about ten minutes; the GitHub Deploy workflow fails
until it is.

## 2026-10-23: real bank export, edit, delete and undo

**Stack recreated** from Terraform for testing by hand: `Apply complete! Resources: 58 added, 0
changed, 0 destroyed.`, image `48e37db505cd` pushed, then one ECS task started (`Resources: 0
added, 1 changed, 0 destroyed.`). The page and `/v3/api-docs` answered with 200.

**Real Sparkasse export:** Abdul exported his Sparkasse KölnBonn transactions as CSV-CAMT from
online banking and uploaded them on the AWS deployment. The importer read the file without any
change to the parser; the bank tab listed 126 open outgoing payments with names, dates, amounts and
purpose texts (screenshot from Abdul, who confirmed it read the file correctly). The file is not in the
repository; no names or amounts from it are recorded here.

**Tests:** API 157 tests, 0 failures (`ExpenseCorrectionApiTest`: any member deletes and the
history shows who and when, edit replaces in one step with the old version kept, edit replay and
409s, payer-only rule on edit, strangers get 403; payment undo brings the debt back, once only, not
from another group; outbox `PAYMENT_REVERSED` with opposite postings; sharing a bank payment with
a different payer, amount and date; app role cannot update or delete `correction`). Statement worker
13 tests (undoing a payment takes it back out of the month). Web 27 unit tests and typecheck.

**Browser walkthrough** against `docker compose up --build` (new steps: Share opens the form filled
from the bank, undo a payment, delete an expense, edit an expense; the overview ends at "Ben owes
you 87,00"): `Walkthrough passed: desktop and phone`.

**Destroyed again** after the manual tests (2026-10-23): `Apply complete! Resources: 0 added, 0
changed, 58 destroyed.`, `terraform state list` empty. In the account afterwards: no RDS instances,
ECS clusters, Lambda functions, Elastic IPs or NAT gateways; only the budget and the automated RDS
snapshot, which AWS removes after the instance (as on 2026-10-22). Everyday use moves to the free
Oracle Cloud host (`deploy/free-host`).

## 2026-10-23: free hosting on Oracle Cloud

`deploy/free-host` set up by Abdul on an Oracle Cloud Always Free VM (VM.Standard.A1.Flex, 2 OCPU,
12 GB, Ubuntu 24.04, eu-frankfurt-1) with the DuckDNS name `splitledger-madni.duckdns.org`: ports
80 and 443 opened in the OCI security list and the host firewall, Docker installed, `.env` with
random passwords generated on the VM, `docker compose up -d --build`. From the start-up log:

```
Image splitledger-free-host-api  Built  103.1s   (on the ARM VM)
Successfully applied 9 migrations to schema "public", now at version v9
Started SplitledgerApiApplication in 21.379 seconds
GET / -> 200, GET /assets/index-….js -> 200      (requests through Caddy)
```

Checked from outside the same day:

```
https://splitledger-madni.duckdns.org/            200, certificate valid
http://splitledger-madni.duckdns.org/             308 -> https://…
https://splitledger-madni.duckdns.org/actuator/health   404 (kept internal)
certificate: issuer Let's Encrypt (YE1), valid until 2027-01-20
```

## 2026-10-25: accounts for friends, categories, insights, activity, redesigned installable web app

Commits `754723a` (passwords, sessions, invite links, password resets), `ef45545` (categories,
`created_by`, insights, activity feed) and `e03e38f` (redesigned, installable web app).

**Tests, local, Windows 11 with Docker Desktop:**

```
api              ./mvnw verify          199 tests, 0 failures, 0 errors   (one run)
statement-worker ./mvnw clean verify     13 tests, 0 failures, 0 errors
web              npm test                62 tests passed; npm run build: type check and bundle OK
```

New API tests: `PasswordLoginApiTest` (sign-in on a second device, same answer for unknown user
and wrong password, lock after five wrong passwords, sign out one device or all, expiry, API-token
accounts moving to a password, password change signs out other devices, username and password
rules), `InviteLinkApiTest` (preview, sign-up through the link without the registration code,
single use, a failed sign-up leaves no account, a new link replaces the old), `PasswordResetApiTest`
(one-time link, expiry, only groupmates, accounts without username), `ExpenseCategoryApiTest`,
`InsightsApiTest` (window, my share, deleted and edited expenses left out), `ActivityApiTest`
(every kind in order with who did it, pages without gaps or repeats, strangers get 403), plus new
grants in `AppRolePrivilegesTest`, a category filter in the `SearchIndexUsageTest` EXPLAIN check,
and the new endpoints in the stranger check of `SecurityApiTest`.

Earlier the same day the machine ran out of memory while the IDE, Docker and the tests ran
together (`hs_err`: "insufficient memory"); those runs crashed test JVMs, not tests. After memory
was freed the suite above ran in one go.

**Browser walkthrough** (`web/e2e/walkthrough.mjs`, rewritten for two people in two browser
contexts) against `docker compose up --build`, desktop light and phone dark: Anna starts a group
with a username and password, invites Ben, Ben opens the `#join=` link, picks a password and is in
the group; REWE suggests groceries; equal and exact splits; Ben sees Anna's expenses in his
activity feed with an unread dot; GiroCode and "mark as received"; bank import and sharing EDEKA;
typo search and the category filter; Lucy, undo, delete and edit end at "Ben owes you 87,00";
insights; Ben signs out and back in with his password.

```
Walkthrough passed: desktop and phone, two friends each
```

The same build serves the installable-app files: `GET /manifest.webmanifest` 200
`application/manifest+json`, `/sw.js` 200 `text/javascript`, `/icons/icon-192.png` 200
`image/png`. Screenshots from the run: `docs/evidence/2026-10-25-*.png`.

Not yet done: deploying this to the free host (`git pull && docker compose up -d --build` in
`deploy/free-host`; Flyway applies V10 and V11 on start) and installing it on a real phone.

## 2026-10-25 (evening): open sign-up with a limit per network, join with a code

```
api              ./mvnw clean verify    204 tests, 0 failures, 0 errors
statement-worker ./mvnw clean verify     13 tests, 0 failures, 0 errors
web              npm test                64 tests passed; npm run build OK
```

New: `SignupThrottleTest` (limit within an hour, freed after the hour, checks alone use no slot),
`RegistrationCodeTest` (the page is told a code is needed; one address creates at most the limit,
failed sign-ups do not count, invite sign-ups count, another address is not affected),
`PasswordLoginApiTest` (without a code anyone can sign up), `inviteCodeFrom` in `route.test.ts`.

Browser walkthrough against `docker compose up --build`, now with three people per run: Lucy signs
up without any code, lands on "Join a group", pastes the invite link Anna sent and sees the group.
`Walkthrough passed: desktop and phone, three friends each`. A first run stopped at the sixth
account from the same address with 429, as designed; the local stack (`compose.yaml`) now allows
100 sign-ups per hour, the free host keeps 5.
