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

## 13. API tokens and one central membership rule (2026-10-21)

Members log in with opaque API tokens (`slt_` + 32 random bytes), sent as
`Authorization: Bearer ...`. Only the token's SHA-256 is stored and the token is shown once. A
fast hash is right here: bcrypt exists to slow down guessing of low-entropy passwords, and a
256-bit random token cannot be guessed. JWTs would add signing keys and make revocation harder,
for no benefit in a single service.

- **Access:** one `AuthorizationManager` in the Spring Security filter chain allows
  `/groups/{groupId}/**` only for members of that group. It runs before any controller, so a new
  endpoint under that path is protected automatically (a test calls endpoints as a stranger,
  including ones written after the rule). A missing group and a foreign group both return 403, so
  the response does not reveal which ids exist.
- **Joining:** the group creator becomes the first member. Adding a friend creates a member and
  a one-time invite code (stored hashed); the friend registers and accepts it.
- **CSRF protection is off:** the token travels in a header that browsers never add on their
  own, so cross-site requests cannot carry it. Sessions are stateless.
- **Registration:** open by default; with `splitledger.registration-code` set (on a public host)
  registering needs that code, compared in constant time.

Security came before the import, unlike the original day-3 order: the import needs to know whose
account a statement belongs to, and bank candidates must be visible only to that person.

## 14. Bank import: fingerprints, occurrence index, privacy (2026-10-21)

The parser sits behind `StatementParser`, so CAMT.053 can be added without touching the rest.
The Sparkasse parser finds columns by header name (order and umlaut spelling do not matter),
tries strict UTF-8 and otherwise reads Windows-1252, and parses amounts like `-1.240,00` directly
into cents without floating point.

**Fingerprint:** the Sparkasse CSV has no reference assigned by the bank (the end-to-end reference
is set by the payer and is often `NOTPROVIDED`). So the fingerprint is SHA-256 over account, booking
date, amount, counterparty IBAN and name, purpose text and end-to-end reference, plus an
**occurrence index** among identical rows in the file. Two coffees for 3.20 EUR at the same café on
the same day are identical rows; without the index the second would be dropped as a duplicate.
With it they are coffee #0 and #1 of that day, and an overlapping export numbers them the same way.
Formats with a bank reference (CAMT.053 `AcctSvcrRef`) use that instead. The spec said "bank
reference if present, otherwise the hash"; for this CSV the hash is always used, and the end-to-end
reference is one of its fields rather than a replacement for them.

**Idempotency key vs. fingerprint:** the idempotency key protects one API request from being
applied twice (client retries). The fingerprint protects against the same bank transaction
arriving in two files. Different problems, different keys.

**Duplicates in the database:** `unique (group_id, fingerprint)` with
`INSERT ... ON CONFLICT DO NOTHING`. PostgreSQL decides "new or duplicate" atomically and reports
it per row, so even parallel uploads of the same file store each transaction once with correct
counts (tested with 4 threads).

**Privacy:** the file is never stored. Incoming and pending payments are skipped (pending ones can
still change). Outgoing payments become candidates visible only to the account owner; others get
404, not 403, so they do not learn the id exists. Ignoring a candidate deletes counterparty and
purpose. If the member has an IBAN in the group, a statement for another account is rejected.

**Confirmation** locks the candidate row (`SELECT ... FOR UPDATE`), creates the expense in the same
transaction and marks the row confirmed; a double click waits and gets 409. The confirmed purpose
text is stored with the expense and is part of its search document (weight C).

## 15. Greedy settle-up (2026-10-21)

The member who owes most pays the member who is owed most, as much as possible, repeated until all
balances are zero. Each payment settles at least one of the two, so at most n-1 payments.
Finding the minimal number is NP-hard in general (it means splitting members into as many
zero-sum groups as possible, which contains subset-sum). Greedy is not always minimal: for
+5, +6, +2, -6, -7 it suggests 4 payments where 3 suffice ({+6, -6}, {+5, +2, -7}). A test compares
it with a brute-force optimum: minimal in 99.7% of 5,000 random groups with cent amounts, and 95%
with small whole amounts, where exactly cancelling subsets are more common. Simple and predictable
wins for a group of friends.

Recording a payment writes ledger entries like an expense (payer +amount, receiver -amount) and
uses the same idempotency mechanism, now shared in `IdempotentCreator`.

## 16. AWS account access without IAM Identity Center (2026-10-21)

The account is on the AWS **Free plan**: usage is paid from credits and the card is never
charged; the account is suspended, not billed, when the credits or six months run out. Enabling
AWS Organizations or IAM Identity Center would switch it to the paid plan and drop the credits, so
the usual best practice (Identity Center with `aws configure sso`) is not used. Instead: root user
with MFA and no access keys, an IAM user `madni-admin` with MFA for daily work, and `aws login`,
which gives the CLI short-lived credentials from a browser sign-in. No long-lived access keys
exist anywhere. CI on day 5 gets its own role via GitHub OIDC.

## 17. Cost guard separate from the application stack (2026-10-21)

`infra/bootstrap` holds only the AWS Budget (5 USD/month, email at 50 % and 100 % actual and 100 %
forecast). It is a separate Terraform root, so `terraform destroy` in `infra/app` can never remove
it. It measures cost **before credits** (`include_credit = false`): on the Free plan the credits
make the net cost 0, and an alert on that would never fire.

## 18. Network without NAT gateway (2026-10-22)

A NAT gateway costs about 35 USD/month before traffic, more than everything else together. The API
task runs in a public subnet with a public IP; its security group allows inbound 8080 only and
outbound only 443 (ECR, CloudWatch Logs, SSM) and 5432 to the database security group. The
database runs in private subnets whose route table has no route to the internet, and its security
group accepts 5432 only from the API's security group. The VPC's default security group is
stripped of all rules. Verified on AWS: the database name resolves to `10.20.10.89` and port 5432
is not reachable from outside.

Trade-off: the task's public IP changes with every deployment (`scripts/aws-api-url.sh` looks it
up). An Application Load Balancer would give a stable name and HTTPS, at about 20 USD/month; it can
be added for a demo. VPC interface endpoints instead of the public route would cost about 7 USD/month
each.

## 19. Secrets never in the Terraform state (2026-10-22)

The RDS master password, the application role's password and the registration code are generated
by `ephemeral "random_password"` resources and passed to write-only attributes (`password_wo` on
RDS, `value_wo` on SSM parameters). Terraform uses them during the run and stores nothing; the
state shows `password: null` and empty parameter values (checked after apply). ECS injects them
from SSM at task start (`secrets` in the task definition), so they appear neither in the task
definition nor in the image. `secrets_version` is the one switch to replace all of them.

SSM Parameter Store instead of RDS's managed Secrets Manager password (HashiCorp's first choice):
the spec asks for SSM, standard parameters are free, and Secrets Manager rotates the RDS password
every 7 days while running tasks keep the old one.

## 20. Least-privilege IAM and database roles (2026-10-22)

- **ECS execution role:** instead of the managed `AmazonECSTaskExecutionRolePolicy` (any
  repository, any log group), a policy for exactly this ECR repository, this log group and these
  three SSM parameters. The trust policy is limited to ECS in this account (`aws:SourceAccount`).
- **Task role:** exists but has no permissions; the application needs no AWS API yet (day 6 adds
  SQS and S3).
- **Database:** Flyway migrates as the RDS master user; the application connects as
  `splitledger_app` (migration V7): SELECT/INSERT everywhere, UPDATE only on mutable tables (member,
  bank import, bank transaction, and `expense_group.name`, which `SELECT ... FOR NO KEY UPDATE`
  requires), no DELETE, no access to `flyway_schema_history`, and as a non-owner it cannot disable
  the append-only triggers. The role's password is set after every migration by a Flyway callback
  that lets PostgreSQL quote it (`format('%L')`), so no secret is ever written into a migration.
  Docker Compose uses the same split, and the demo passes under it locally and on AWS.

## 21. Fargate rather than Lambda for the API (2026-10-22)

The API is a Spring Boot application with a connection pool, Flyway migrations at startup and a
start time of about a minute on 0.25 vCPU. On Lambda every cold start would pay that minute (or need
SnapStart and a connection proxy), and the many short-lived connections would strain a micro RDS.
A single small Fargate task keeps the application unchanged (the same image as locally) and costs
about 0.02 USD per hour. Lambda fits the monthly statement worker on day 6: event-driven, short,
no permanent connection.

Deployments stop the old task before starting the new one (`minimum_healthy_percent = 0`): a
short gap is acceptable for a demo, and two versions never migrate at the same moment. The
deployment circuit breaker rolls back a version that fails its health checks.

## 22. Deploy pipeline: GitHub OIDC, Terraform owns infrastructure, the pipeline owns releases (2026-10-22)

GitHub Actions gets AWS credentials through OIDC: GitHub signs a token per job, AWS checks it
against the trust policy and returns a one-hour session. No access keys are stored in GitHub. The
trust policy accepts only tokens whose `sub` is
`repo:MadniAbdulWahab@105889425/splitledger@1427604325:environment:aws`, i.e. this repository
(identified by its numeric ids, so a future repository with the same name does not match) and only
jobs in the GitHub environment `aws`. The first attempt used the name-only format and was rejected;
CloudTrail's `AssumeRoleWithWebIdentity` event showed the actual claim.

The deploy role can push to this one ECR repository, register task definitions, update this one
service, pass exactly the two task roles to ECS, and look up the task's IP for a smoke test. It
cannot touch the network, the database or IAM; those change only through Terraform from a
developer's machine. The service therefore ignores `task_definition` in Terraform; the workflow
starts from the newest revision, so a Terraform change to the task definition (for example the
JSON log setting) ships with the next deployment. Verified: revision 4, registered by the
workflow, has both the new image and the variable Terraform added in revision 3.

Deployments are started by hand and only for commits whose CI run succeeded (the workflow waits
for it). An automatic deploy on every push would fail while the stack is destroyed, which is most
of the time.

## 23. Structured logs instead of a load balancer for HTTP metrics (2026-10-22)

Without an ALB there are no per-request metrics from AWS. The API writes one JSON line per request
(Spring Boot's built-in structured logging, Elastic Common Schema format) with `requestId`,
`groupId`, `method`, `path`, `status` and `durationMs`; `requestId` and `groupId` are in the MDC,
so every line written while handling a request carries them, and the response returns the id as
`X-Request-Id`. CloudWatch metric filters (`{ $.status > 0 }`, `{ $.status >= 500 }`) turn the
lines into `Requests` and `ServerErrors` metrics; Logs Insights finds all lines of one request by
its id. An incoming `X-Request-Id` is reused only if it is short and safe, so nobody can inject
text into the logs. Health checks are not logged (ECS calls them every 30 seconds).

## 24. Which alarms, and what to check first (2026-10-22)

- **5xx rate > 5 % over 5 minutes** (metric math on the two log metrics; missing data counts as
  fine). A rate, not a count: one failure during busy use is noise, one in three requests failing
  is not. First check: Logs Insights on the dashboard ("latest failed or slow requests"), take a
  `requestId`, read all its lines; then the latest deployment.
- **Task CPU > 80 % for 15 minutes** and **memory > 90 % for 15 minutes**: 0.25 vCPU / 0.5 GB is
  tight. First check: did traffic grow (requests widget) or did one request loop (durationMs)?
- Alarms and recoveries email through SNS. The path was tested by forcing the 5xx alarm into
  ALARM (`set-alarm-state`); it returned to OK on the next evaluation.
- Not alarmed yet: "no task running" needs Container Insights (extra cost); a DLQ-depth alarm comes
  with SQS on day 6.

## 25. Everyday hosting for free (2026-10-22)

AWS costs about 30 USD a month if left on, which defeats a project that exists to avoid paying
for Splitwise Pro. `deploy/free-host/` runs the same image with PostgreSQL and Caddy on any free VM
(Oracle Cloud Always Free) or a home machine: Caddy gets a Let's Encrypt certificate so tokens only
travel over HTTPS, the API port is not published, actuator endpoints are not served publicly, and
registration requires a code. AWS remains the place to show the cloud skills: deploy, verify,
destroy, and recreate from Terraform for a demo.

## 26. Transactional outbox instead of publishing to SQS directly (2026-10-22)

Sending to SQS inside the request has two failure modes: the message is sent but the database
transaction rolls back (an event for something that never happened), or the transaction commits
but SQS is unreachable (a lost event). The outbox avoids both: the event is a row written in the
same transaction as the expense, reversal or payment, so it exists exactly when the change
committed. A scheduled publisher sends unpublished rows and marks them; delivery is at-least-once.

- **Order per group:** the event is written after the ledger entries, while the transaction holds
  the group's row lock (decision 9). Within a group, outbox ids are therefore assigned in commit
  order, and a smaller id can never commit after a larger one has been published. Without the lock
  this is the classic outbox trap: id 10 commits after id 11 was already sent.
- **One publisher, one message at a time:** a PostgreSQL advisory lock lets only one publisher run
  (several API tasks would otherwise interleave), and messages are sent one by one, stopping at the
  first failure, so nothing overtakes a waiting event. SendMessageBatch could partially fail out of
  order. The volume here makes single sends cheap.
- **Duplicates** (crash between "SQS accepted" and "row marked"): SQS FIFO drops a repeated
  deduplication id (the outbox id) within five minutes, and the worker skips events it already applied.
- Publishing switches on only when a queue URL is configured; locally and in most tests events are
  written but not sent.

## 27. FIFO queue with message group = expense group, idempotent worker (2026-10-22)

A standard queue delivers in any order and to parallel consumers, so two events of one group could
update the same monthly statement at the same time (lost update) or in the wrong order. With a FIFO
queue and `MessageGroupId = group-<id>`, SQS delivers each group's events in order and hands them to
one invocation at a time; different groups still run in parallel. The worker's read-modify-write of
the S3 object needs no lock for that reason.

Each statement stores the highest event id it has applied; an event with a smaller or equal id is a
repeat and is skipped. Partial batch responses report only failed messages, and after the first
failure in a batch the remaining messages are reported failed without processing, because they may
belong to the same group. After three failed receives a message moves to the FIFO dead-letter queue,
which raises an alarm. Visibility timeout 180 s = 6 x the 30 s function timeout, as AWS recommends.

## 28. Kotlin Lambda for the statements; emulators instead of LocalStack in tests (2026-10-22)

The worker is small, event-driven and short-lived: a good fit for Lambda (decision 21 explains why
the API is not). It is written in Kotlin with kotlinx.serialization: data classes for the event
and statement, and the aggregation as a pure function (`MonthlyStatement.apply`) that is
unit-tested without AWS. Runtime `java21` on arm64 (Graviton, cheaper), the AWS SDK with the JDK's
HTTP client and `TieredStopAtLevel=1` for a shorter cold start. Measured on AWS: 0.85 s init plus
2.5 s for the first invocation, then 0.11-0.15 s; 170 MB used.

The spec suggested LocalStack for SQS and S3 tests. Since March 2026 LocalStack needs an account
token even on its free plan, which a public CI should not depend on. The tests use ElasticMQ (SQS,
including FIFO) and Adobe S3Mock instead: open source, no account, started by Testcontainers.

The Lambda code is deployed by Terraform from the locally built jar (`source_code_hash` detects
changes), while the API image goes through the GitHub pipeline. A second deploy step for the worker
would follow the same pattern (role allowed `lambda:UpdateFunctionCode` on this one function).

## 29. GiroCode (EPC QR) for settling up (2026-10-22)

`GET /groups/{id}/settlement/girocode?from=&to=&amount=` returns a PNG following EPC069-12 version
002: `BCD`, `002`, character set `1` (UTF-8), `SCT`, optional BIC, receiver name (max 70
characters), IBAN, amount as `EUR42.00`, empty purpose and structured reference, transfer text
"SplitLedger settle-up <group> <month>" (max 140 characters). The whole payload may have 331 bytes;
since umlauts take two bytes in UTF-8, the free text is shortened until it fits. Error correction
level M and a four-module quiet zone, as the guideline asks. Only ZXing's core module is used; the
PNG is written with the JDK's ImageIO. Tests decode the PNG with ZXing's reader and compare it with
the payload; a Sparkasse banking app scanned the code from the AWS demo and filled in the transfer.
The response is `Cache-Control: no-store` because it contains an IBAN and a current amount.

## 30. A small React page served by the API (2026-10-22)

`web/` (Vite, React 19, TypeScript, versions pinned) is compiled in a Node stage of the Docker build
and copied into Spring Boot's static folder, so page and API share one origin: no CORS, one
deployment, the same image locally, on a free host and on AWS. The page is public; every request it
makes carries the user's token, which is kept in localStorage. Against script injection a strict
Content-Security-Policy allows scripts, connections and frames only from the server itself
(`script-src 'self'`, `frame-ancestors 'none'`; `style-src` additionally allows inline styles,
which Swagger UI and React style attributes need and which cannot run code). No UI library: a small
CSS design system with light and dark mode, an icon bar at the bottom on phones, inline SVG icons.

Lesson from AWS: `crypto.randomUUID()` only exists on secure pages (HTTPS or localhost). Locally
everything worked; on the plain-HTTP demo adding an expense failed. Idempotency keys now fall back
to a UUID built from `crypto.getRandomValues()`, with a test that simulates the insecure page.

## 31. Splits in the page mirror the server's rule (2026-10-22)

The form offers equal, exact-amount and share splits and shows every person's cents before saving.
`web/src/split.ts` is a port of `SplitCalculator` (largest remainder, ties to the lower member id),
tested with the same cases plus 5,000 random splits, so the preview and the stored result never
differ. Exact amounts show what is left to assign, refuse to save until the total matches and can
"split the rest" over the empty fields. The API now rejects an expense that only the payer shares
(the payer's credit and own share would cancel out, nothing is shared), so the rule holds for the
page, bank confirmations and any other client.

## 32. Who owes whom: pairwise debts next to the simplified plan (2026-10-22)

Net balances ("Madni -5, Tim -5, Lucy +10") are correct but do not answer what people ask: whom do I
owe, and who owes me? `GET /groups/{id}/debts` computes debts per pair in one SQL query: every
participant owes the payer their share, payments reduce it, reversed expenses do not count, and
opposite debts between two people cancel. For each member the pairwise debts add up to exactly
their ledger balance (checked by a randomized test), so the two views never contradict each other.

The page leads with "You owe Lucy 10,00 €" / "Tim owes you 5,00 €" and offers the greedy plan of
decision 15 only as a tip when it needs fewer transfers. The GiroCode endpoint accepts an amount so a
creditor can show their own code for a debtor to scan.

## 33. A browser test in CI (2026-10-22)

`web/e2e/walkthrough.mjs` (playwright-core, driving the installed Chrome, no browser download) goes
through the main path on desktop and on a phone in dark mode: register, create a group, invite,
IBAN, payer-only refusal, equal and exact splits, GiroCode, payment, bank import, history with
search and pagination, who owes whom, and that no JavaScript error occurs. CI starts the real
stack with `docker compose up --wait` (PostgreSQL, API with the built page, least-privilege role)
and runs it; on failure the screenshots and API logs are kept. The same script ran against AWS.

## 34. Edit, delete and undo without overwriting anything (2026-10-23)

Users expect to fix mistakes, and the first version only had a reversal endpoint without a button.
The ledger stays append-only; what users see as editing is built from what it already allows:

- **Delete** writes reversing entries for the expense. The expense and its original entries stay;
  the history shows it crossed out.
- **Edit** (`POST .../expenses/{id}/correction`) reverses the old version and creates the new one
  in one transaction, so the group never sees both or neither. It takes an `Idempotency-Key` like
  creating; the original expense id is part of the request hash, so a key cannot be reused to edit
  another expense. A double click that arrives at the same moment waits for the group lock, finds
  the expense already reversed and gets the first request's result instead of a 409.
- **Undo payment** reverses a payment recorded by mistake; "who owes whom" ignores undone
  payments, and the statement worker gets a new `PAYMENT_REVERSED` event that subtracts it from the
  month (the outbox table's check constraint had to allow the new type).

Who did it is recorded in a new append-only table `correction` (kind, the expense or payment, the
new version for an edit, member, time), written in the same transaction as the reversal. Unique
constraints make every expense and payment correctable at most once; an edited expense's new
version can be edited again. Any member of the group may correct anything, as in Splitwise, because
the history always shows who did it. Deleting an expense that came from a bank transaction does not
put the transaction back into the bank list; it can be entered by hand if needed.

## 35. Sharing a bank payment opens the expense form (2026-10-23)

"Share" used to split the payment equally with everyone at once. Real payments are often only
partly shared, paid on behalf of someone, or belong to another day, so Share now opens the Add tab
filled with the bank's name, amount and date. The confirmation endpoint accepts payer, amount and
date and falls back to the bank's values when they are missing, so older clients keep working. The
bank purpose text is still attached to the expense and stays searchable.

## 36. Passwords and sessions next to API tokens (2026-10-25)

Friends had to keep an `slt_` token shown once in a password manager and paste it on every device;
nobody would. People now sign in with a username and a password. The password is hashed with
bcrypt (from spring-security-crypto, already on the classpath): unlike a 256-bit random token, a
password has little randomness, so every guess has to be slow. bcrypt reads only 72 bytes, so
longer passwords are refused instead of silently cut. Unknown usernames are compared against a
dummy hash, so the answer takes as long as for a wrong password; five wrong passwords in a row lock
a username for a minute (in memory, enough for one API instance).

Every sign-in creates a row in `user_session` with its own token (same `slt_` format, stored as
SHA-256), valid 180 days and revocable: "sign out" revokes this device, "sign out everywhere" all
of them, a password change all others. The old API tokens keep working for scripts and existing
accounts, which can add a username and password later. The application role may update only
`app_user (username, password_hash)`, `user_session (revoked_at)` and `password_reset (used_at)`;
the token hash and creation time of an account stay immutable.

## 37. The invite link is the permission (2026-10-25)

Joining needed the host's registration code plus an invite code. Now adding a friend gives a link
`https://host/#join=inv_…`. Opening it shows "Anna invited you to WG Kessenich"; the friend picks a
username and password and is in the group, in one transaction (an unknown code or a taken username
leaves nothing behind). The invite code already proves that a member invited this person, so the
registration code is only needed to start a new group. Codes live in the URL fragment, which
browsers never send to the server, and in request bodies, never in paths, because the request log
records paths. A lost link is replaced by a new one, which invalidates the old (codes are stored
hashed and cannot be shown again).

## 38. Password resets by a groupmate, shown to everyone (2026-10-25)

There is no email server, so "forgot password" works through the group: any member can create a
one-time link (`#reset=rst_…`, 24 hours) for a friend who has joined. Using it sets the new
password, signs out every device and signs the friend in. A groupmate could use the link
themselves to take over the account, so every reset appears in the group's activity feed with who
made it, and the owner notices because all their devices are signed out. For a group of friends
that is the right trade; a public service would need email or a second factor.

## 39. Categories and "who added it" (2026-10-25)

Expenses get an optional category from a fixed list (groceries, rent, travel, …). The page suggests
one from the description or bank payee ("REWE" is groceries, "DB" transport) with whole-word
keyword rules, and the user can change it. Old expenses have none and count as Other. The category
joins the idempotency hash only when it is set, so the hash of a request without one is unchanged.
Expenses and payments also record the member who entered them (`created_by`), which is not
always the payer. Both are new columns written on insert; the rows stay immutable.

## 40. Activity feed and insights read the append-only tables (2026-10-25)

The activity feed is one `UNION ALL` over expenses, corrections, payments, members and password
resets, newest first with keyset pagination on (time, id). Nothing new is written for it, so it
cannot disagree with the ledger. Corrections and resets now take their timestamp from the
application clock like every other row; the database container's clock can be a few milliseconds
off and would shuffle the order. Insights sum live expenses (no reversing entries) per month,
category and payer in SQL; "your share" is what you consumed, not what you paid. Bank transactions
never appear in either: they stay private to their owner.

## 41. A redesigned page that installs as an app (2026-10-25)

The page was rebuilt around how friends use it on phones: a group card with your balance, quick
actions, people with what is open, a bottom bar with a raised add button, bottom sheets for adding
and settling, and a sidebar layout on wide screens. Still no UI library, router or chart library:
a hash router (`#/g/12/insights`), CSS tokens with light, dark and a manual theme switch, inline
SVG icons and charts. Member colours come from the euro banknotes and follow the member id; chart
colours were checked for colour-vision deficiencies and contrast in both themes.

A web app manifest and a service worker make it installable ("Add to Home Screen"). The service
worker caches only the app shell (page, hashed scripts and styles, icons): API responses, and with
them balances, bank data and tokens, never enter the browser cache, and the page itself is fetched
network-first so a new deployment shows at once.

## 42. Open sign-up with a limit per network (2026-10-25)

A registration code kept strangers out, but it also meant friends needed a code from the host before
they could do anything, unlike every app they know. Now anyone can create an account and then start
a group or join one by pasting an invite link or code (`POST /invitations/accept` existed already;
the page only lacked the field). Strangers gain nothing from an account: every group is visible to
its members only. What remains is junk: bots filling the small free VM with accounts. So one
network may create at most five accounts per hour (`splitledger.signup-limit-per-hour`), counting
only accounts actually created, so a taken username costs nothing; invite sign-ups count as well.
Behind Caddy the client's address comes from `X-Forwarded-For`, which Tomcat accepts only from
proxies in private networks (`server.forward-headers-strategy: native`), so a client cannot fake its
address by sending the header. The registration code stays available: when it is set, sign-up needs
it again and the page shows the field (`GET /users/registration`).

## 43. Shaped by use on a phone (2026-10-26)

After a day of real use on an iPhone the page changed in many small ways. The ones with a reason
worth keeping:

- **One screen per question.** The Activity tab is the full history (search, filters, payments,
  edit and delete). The home card opens "How it added up", only expenses and payments, each with
  what it did to your balance, computed from your own ledger entries, so the entries add up to
  the card's number. Member events stay out of it because they move no money.
- **A page per person,** with an address of its own (`#/g/12/member/34`), so the phone's back
  gesture works.
- **Gestures behave like native apps.** Sheets close on a clear swipe down (a third of their height
  or a fast flick) and spring back otherwise. A gesture's direction is decided after its first 8px,
  so swiping through the categories never drags the sheet. Press and hold opens edit and delete.
  Hover colours apply only to a mouse, because on touch screens they stick after a tap.
- **Double taps book once.** Forms keep one Idempotency-Key while open, and every action ignores
  taps while it runs (see decision 3 for the server side).
