# SplitLedger

My friends and I share costs and track them in Splitwise. Every shared payment already exists in
someone's German bank account, yet it has to be typed in again by hand; settling up means copying
IBANs into a banking app; any entry can be edited or deleted later without a trace; and searching
the history ("what did we pay for the Berlin trip?") needs the paid plan.

SplitLedger is a small ledger service for one group of friends that fixes exactly those four things:

1. **Import instead of typing.** Upload a bank statement export; shared payments become expense
   candidates, and importing the same file twice never creates duplicates.
2. **Settle with a scan.** "You owe Ali 42.00 EUR" comes with a GiroCode (EPC QR) for German banking apps.
3. **History nobody can quietly rewrite.** Append-only, double-entry ledger with a SHA-256 hash chain.
4. **Search the whole history.** PostgreSQL full-text and trigram search, free.

It is a personal project, not a payment service: no money is moved and no bank API is called.

## Status

Built in daily steps (see [docs/verification.md](docs/verification.md) for dated evidence).

| Part | State |
|---|---|
| Groups, members with IBAN check, expenses with idempotency keys, double-entry ledger, balances | done (day 1) |
| Hash chain with `/verify`, reversals, append-only trigger, full-text and typo-tolerant search | done (day 2) |
| API tokens and group membership checks, Sparkasse CSV import, settle-up and payments, OpenAPI, demo script | done (day 3) |
| AWS (ECS Fargate, RDS) with Terraform, CI/CD, CloudWatch | planned (days 4-5) |

## Run locally

Requirements: Java 21 and Docker. Maven is not needed; the wrapper `./mvnw` downloads the pinned version.

```bash
cp .env.example .env          # then set your own DB_PASSWORD
docker compose up --build     # API on http://localhost:8080, PostgreSQL on 127.0.0.1:5432 (DB_PORT in .env)
```

Then run the whole story (two friends, a statement imported twice plus an overlapping one,
confirmed candidates, a retried request, settle-up, chain verification, search):

```bash
scripts/demo.sh                 # BASE_URL=... for another server
```

Interactive API docs: http://localhost:8080/swagger-ui.html (register with `POST /users`, then use
the token with "Authorize").

Tests (unit tests plus integration tests against PostgreSQL 16 in Testcontainers):

```bash
cd api && ./mvnw verify
```

## API

Every request except registration, health and the API docs needs `Authorization: Bearer <token>`.
Everything under `/groups/{id}` is only for members of that group (403 otherwise).

| Method and path | Purpose |
|---|---|
| `POST /users`, `GET /me` | Register (returns the API token once); your groups |
| `POST /groups`, `GET /groups/{id}` | Create a group (you become its first member) and read it |
| `POST /groups/{id}/members`, `GET /groups/{id}/members` | Add a friend (returns a one-time invite code) and list members |
| `POST /invitations/accept` | Link your account to the member the invite code belongs to |
| `PUT /groups/{id}/members/me/bank-details` | Set your IBAN/BIC (validated with ISO 13616 mod-97) |
| `POST /groups/{id}/expenses` | Create an expense; requires an `Idempotency-Key` header |
| `GET /groups/{id}/expenses/{expenseId}` | Read an expense with its shares |
| `POST /groups/{id}/expenses/{expenseId}/reversal` | Cancel an expense with reversing entries (once) |
| `GET /groups/{id}/expenses/search?q=&member=&from=&to=&min=&max=&limit=&cursor=` | Search descriptions, notes and bank purpose texts (German word forms, typos); filters; keyset pagination |
| `POST /groups/{id}/imports` | Upload your bank statement (multipart `file`); returns new / duplicate / skipped counts |
| `GET /groups/{id}/bank-transactions` | Your expense candidates (only you see your bank data) |
| `POST /groups/{id}/bank-transactions/{tid}/confirmation`, `.../ignore` | Turn a candidate into a shared expense, or drop it |
| `GET /groups/{id}/balances` | Net balance per member; positive means the group owes them |
| `GET /groups/{id}/settlement` | Suggested payments that settle everyone, with the receiver's IBAN |
| `POST /groups/{id}/payments`, `GET /groups/{id}/payments` | Record a settle-up payment (`Idempotency-Key`) and list them |
| `GET /groups/{id}/verify` | Recompute the hash chain; report the first broken entry and the head hash |

Amounts are integer cents. Split modes: `EQUAL`, `EXACT` (cents per person) and `SHARES` (units per
person). Errors are RFC 9457 problem details. Ledger entries, expenses and shares cannot be updated
or deleted, not even with SQL: a trigger rejects it, and `/verify` detects changes made by someone
who bypasses the trigger. Design decisions are in [docs/decisions.md](docs/decisions.md).

## Bank data: assumptions and privacy

- **The CSV importer is built against a made-up file.** My bank (Sparkasse KölnBonn) currently only
  gives me PDF statements, so the importer targets the standard Sparkasse online-banking
  "CSV-CAMT" export layout (semicolon-separated, Windows-1252, decimal commas, `dd.mm.yy` dates,
  columns such as `Buchungstag`, `Verwendungszweck`, `Beguenstigter/Zahlungspflichtiger`,
  `Kontonummer/IBAN`, `Betrag`). The sample file in `samples/` (added with the importer) is
  synthetic: invented names and test IBANs. If a real export becomes available, its header is compared locally and the parser
  adjusted; real statements are never committed. The parser sits behind an interface so other
  banks (and ISO 20022 CAMT.053) can be added.
- Names and IBANs are personal data. The uploaded file is parsed in memory and never stored; incoming
  and pending payments are not stored at all. Outgoing payments wait as candidates that only the
  account owner can see. Confirming one makes it a shared expense; ignoring one deletes its
  counterparty and purpose text and keeps only a fingerprint, date and amount, so a re-import still
  recognizes it. Each member enters their own IBAN; nobody sets someone else's.
