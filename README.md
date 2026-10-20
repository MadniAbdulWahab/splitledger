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
| Hash chain, reversals, database-level append-only guard, search | planned (day 2) |
| Sparkasse CSV import, settle-up, Spring Security, OpenAPI | planned (day 3) |
| AWS (ECS Fargate, RDS) with Terraform, CI/CD, CloudWatch | planned (days 4-5) |

## Run locally

Requirements: Java 21 and Docker. Maven is not needed; the wrapper `./mvnw` downloads the pinned version.

```bash
cp .env.example .env          # then set your own DB_PASSWORD
docker compose up --build     # API on http://localhost:8080, PostgreSQL on 127.0.0.1:5432
```

```bash
curl -s -X POST localhost:8080/groups -H 'Content-Type: application/json' -d '{"name":"WG"}'
curl -s -X POST localhost:8080/groups/1/members -H 'Content-Type: application/json' \
     -d '{"displayName":"Anna","iban":"DE89 3704 0044 0532 0130 00"}'
curl -s -X POST localhost:8080/groups/1/members -H 'Content-Type: application/json' -d '{"displayName":"Ben"}'
curl -s -X POST localhost:8080/groups/1/expenses -H 'Content-Type: application/json' \
     -H 'Idempotency-Key: 6f1c2a' \
     -d '{"payerId":1,"amountCents":1000,"description":"Groceries","expenseDate":"2026-10-20",
          "splitMode":"EQUAL","participants":[{"memberId":1},{"memberId":2}]}'
curl -s localhost:8080/groups/1/balances
```

Tests (unit tests plus integration tests against PostgreSQL 16 in Testcontainers):

```bash
cd api && ./mvnw verify
```

## API (so far)

| Method and path | Purpose |
|---|---|
| `POST /groups`, `GET /groups/{id}` | Create and read a group |
| `POST /groups/{id}/members`, `GET /groups/{id}/members` | Add and list members (IBAN validated with ISO 13616 mod-97) |
| `POST /groups/{id}/expenses` | Create an expense; requires an `Idempotency-Key` header |
| `GET /groups/{id}/expenses/{expenseId}` | Read an expense with its shares |
| `GET /groups/{id}/balances` | Net balance per member; positive means the group owes them |

Amounts are integer cents. Split modes: `EQUAL`, `EXACT` (cents per person) and `SHARES` (units per
person). Errors are RFC 9457 problem details. Design decisions are in [docs/decisions.md](docs/decisions.md).

## Bank data: assumptions and privacy

- **The CSV importer is built against a made-up file.** My bank (Sparkasse KölnBonn) currently only
  gives me PDF statements, so the importer targets the standard Sparkasse online-banking
  "CSV-CAMT" export layout (semicolon-separated, Windows-1252, decimal commas, `dd.mm.yy` dates,
  columns such as `Buchungstag`, `Verwendungszweck`, `Beguenstigter/Zahlungspflichtiger`,
  `Kontonummer/IBAN`, `Betrag`). The sample file in `samples/` (added with the importer) is
  synthetic: invented names and test IBANs. If a real export becomes available, its header is compared locally and the parser
  adjusted; real statements are never committed. The parser sits behind an interface so other
  banks (and ISO 20022 CAMT.053) can be added.
- Names and IBANs are personal data. Only transactions a member confirms are stored, raw files are
  discarded after parsing, and friends are asked before their IBAN is saved.
