# Sample bank statements (synthetic)

These files imitate a Sparkasse online-banking export in the **CSV-CAMT** layout. They are made
up: the names are invented, and every IBAN uses a bank code starting with `0`, which no German bank
has, so the IBANs pass the check-digit test but belong to no real account.

| File | Booking days | Content |
|---|---|---|
| `sparkasse-csv-camt-2026-03-a.csv` | 01.03.-15.03.2026 | 8 outgoing payments (two identical coffees on 12.03., rent with a thousands separator, umlauts), 1 incoming refund |
| `sparkasse-csv-camt-2026-03-b.csv` | 10.03.-31.03.2026 | overlaps file A on 10.-15.03. (4 outgoing rows including both coffees, the refund), 3 new payments, 1 pending ("Umsatz vorgemerkt") |

Format as exported by Sparkasse: Windows-1252 encoding, CRLF line endings, `;` as separator, every
value in double quotes, dates as `dd.mm.yy`, amounts with decimal comma and `.` as thousands
separator (`-1.240,00`), newest booking first.

Expected import results: A gives 8 new, 1 skipped (incoming); A again gives 8 duplicates; B after A
gives 3 new, 4 duplicates, 2 skipped (pending, incoming).

Never put real statements here. Real exports stay outside the repository (`/private/` and
`*.real.csv` are git-ignored).
