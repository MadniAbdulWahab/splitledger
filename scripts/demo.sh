#!/usr/bin/env bash
# End-to-end demo against a running SplitLedger API: two friends, a bank statement imported twice
# plus an overlapping one, a confirmed candidate, a manual expense with a retried request,
# settle-up, hash chain verification and search.
#
# Usage:  scripts/demo.sh                       (API on http://localhost:8080)
#         BASE_URL=https://... scripts/demo.sh  (e.g. the AWS deployment)
#         REGISTRATION_CODE=... scripts/demo.sh (if the server requires one)
# Needs only bash, curl, sed and grep.

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SAMPLES="$ROOT/samples"
RUN="demo-$(date +%s)"   # makes idempotency keys unique per run

section() { printf '\n== %s\n' "$1"; }

# First value of a JSON field, e.g. echo '{"token":"slt_x"}' | field token -> slt_x
# (good enough for this API's responses; values containing quotes, commas or colons are not needed)
field() { grep -o "\"$1\":\"\{0,1\}[^\",}]*" | head -n 1 | sed 's/.*:"\{0,1\}//'; }

api() { # method path token [extra curl args...]
  local method="$1" path="$2" token="$3"; shift 3
  curl -sS --fail-with-body -X "$method" "$BASE_URL$path" -H "Authorization: Bearer $token" "$@"
}

json() { # method path token body [extra headers...]
  local method="$1" path="$2" token="$3" body="$4"; shift 4
  api "$method" "$path" "$token" -H 'Content-Type: application/json' -d "$body" "$@"
}

register() {
  curl -sS --fail-with-body -X POST "$BASE_URL/users" -H 'Content-Type: application/json' \
    ${REGISTRATION_CODE:+-H "X-Registration-Code: $REGISTRATION_CODE"} -d "{\"displayName\":\"$1\"}" | field token
}

section "Health"
curl -sS --fail-with-body "$BASE_URL/actuator/health"; echo

section "Two friends register"
ANNA=$(register Anna)
BEN=$(register Ben)
echo "Anna and Ben have API tokens (${ANNA:0:8}..., ${BEN:0:8}...)"

section "Anna creates a group and invites Ben"
GROUP=$(json POST /groups "$ANNA" '{"name":"WG Kessenich"}' | field id)
json PUT "/groups/$GROUP/members/me/bank-details" "$ANNA" '{"iban":"DE81000010000001234567"}' > /dev/null
INVITE=$(json POST "/groups/$GROUP/members" "$ANNA" '{"displayName":"Ben"}')
ANNA_ID=$(api GET "/groups/$GROUP/members" "$ANNA" | field id)
BEN_ID=$(echo "$INVITE" | sed -n 's/.*"member":{"id":\([0-9]*\).*/\1/p')
json POST /invitations/accept "$BEN" "{\"code\":\"$(echo "$INVITE" | field inviteCode)\"}" > /dev/null
json PUT "/groups/$GROUP/members/me/bank-details" "$BEN" '{"iban":"DE80000020000007654321"}' > /dev/null
echo "group $GROUP: Anna = member $ANNA_ID, Ben = member $BEN_ID (joined with the invite code)"

section "Anna imports her March statement, the same file again, then an overlapping one"
for file in sparkasse-csv-camt-2026-03-a.csv sparkasse-csv-camt-2026-03-a.csv sparkasse-csv-camt-2026-03-b.csv; do
  report=$(api POST "/groups/$GROUP/imports" "$ANNA" -F "file=@$SAMPLES/$file")
  printf '%-36s new=%s duplicates=%s skipped=%s\n' "$file" \
    "$(echo "$report" | field newTransactions)" "$(echo "$report" | field duplicates)" "$(echo "$report" | field skippedCount)"
done

section "Anna confirms two shared payments and ignores the rent"
CANDIDATES=$(api GET "/groups/$GROUP/bank-transactions" "$ANNA")
candidate() { echo "$CANDIDATES" | grep -o "{\"id\":[0-9]*,[^}]*\"counterpartyName\":\"$1\"" | head -n 1 | field id; }
SPLIT="{\"splitMode\":\"EQUAL\",\"participants\":[{\"memberId\":$ANNA_ID},{\"memberId\":$BEN_ID}]}"
for shop in "EDEKA Center Bonn" "Lidl Bonn"; do
  expense=$(json POST "/groups/$GROUP/bank-transactions/$(candidate "$shop")/confirmation" "$ANNA" "$SPLIT")
  echo "expense $(echo "$expense" | field id): $(echo "$expense" | field description), $(echo "$expense" | field amountCents) cents"
done
api POST "/groups/$GROUP/bank-transactions/$(candidate 'Hausverwaltung Rheinblick GmbH')/ignore" "$ANNA" > /dev/null
echo "rent ignored; Ben sees $(api GET "/groups/$GROUP/bank-transactions" "$BEN" | grep -o '"id"' | wc -l | tr -d ' ') of Anna's bank transactions"

section "Ben adds a pizza night; his client retries the request after a timeout"
PIZZA="{\"payerId\":$BEN_ID,\"amountCents\":3600,\"description\":\"Pizza night\",\"expenseDate\":\"2026-03-21\",\"splitMode\":\"EQUAL\",\"participants\":[{\"memberId\":$ANNA_ID},{\"memberId\":$BEN_ID}]}"
for attempt in 1 2; do
  replayed=$(json POST "/groups/$GROUP/expenses" "$BEN" "$PIZZA" -H "Idempotency-Key: $RUN-pizza" -D - -o /dev/null \
    | sed -n 's/^Idempotent-Replayed: \([a-z]*\).*/\1/ip')
  echo "attempt $attempt: Idempotent-Replayed=$replayed"
done

section "Balances and suggested settle-up"
api GET "/groups/$GROUP/balances" "$ANNA"; echo
SETTLEMENT=$(api GET "/groups/$GROUP/settlement" "$BEN")
echo "$SETTLEMENT"
FROM=$(echo "$SETTLEMENT" | field fromMemberId)
TO=$(echo "$SETTLEMENT" | field toMemberId)
AMOUNT=$(echo "$SETTLEMENT" | field amountCents)

section "Ben pays Anna by bank transfer and records it"
json POST "/groups/$GROUP/payments" "$BEN" "{\"fromMemberId\":$FROM,\"toMemberId\":$TO,\"amountCents\":$AMOUNT}" \
  -H "Idempotency-Key: $RUN-settle" > /dev/null
api GET "/groups/$GROUP/balances" "$ANNA"; echo

section "Hash chain"
api GET "/groups/$GROUP/verify" "$BEN"; echo

section "Search with a typo"
api GET "/groups/$GROUP/expenses/search?q=Lidel" "$BEN" | grep -o '"description":"[^"]*"'

section "Done"
