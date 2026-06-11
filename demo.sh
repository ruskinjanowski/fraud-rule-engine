#!/usr/bin/env bash
#
# Guided walkthrough of the fraud rule engine: ingestion (Kafka), rule evaluation,
# idempotency, both dead-letter paths, and the query API. Ingestion is Kafka-only —
# events are published to the 'transactions' topic; the REST API is retrieval-only.
#
# Prerequisites: the stack is up — `docker compose --profile demo up --build`
# (the demo profile adds Kafbat UI at http://localhost:8088 for watching Kafka).
#
# Usage:
#   ./demo.sh             # pauses between steps (press Enter to advance)
#   ./demo.sh --no-pause  # run straight through
#
set -euo pipefail
cd "$(dirname "$0")"

BASE="${DEMO_BASE_URL:-http://localhost:8080}"
PAUSE=true
[[ "${1:-}" == "--no-pause" ]] && PAUSE=false

# Fresh ids per run so reruns don't inherit rule history from earlier ones.
RUN="$(uuidgen | tr 'A-Z' 'a-z' | cut -c1-8)"
TODAY="$(date -u +%Y-%m-%d)"

bold() { printf '\033[1m%s\033[0m\n' "$*"; }
step() { echo; bold "── $1 ─────────────────────────────────────────"; echo "$2"; echo; }
pause() { if $PAUSE; then echo; read -r -p "  [Enter to continue] "; fi; }
uuid() { uuidgen | tr 'A-Z' 'a-z'; }

pretty() {
  if command -v jq >/dev/null 2>&1; then jq .
  elif command -v python3 >/dev/null 2>&1; then python3 -m json.tool
  else cat; echo; fi
}

# One-line decision summary: flagged, score, and which rules hit.
summary() {
  if command -v jq >/dev/null 2>&1; then
    jq -r '"  → flagged=\(.flagged)  score=\(.score)  hits=[\(.ruleResults | map(select(.hit) | .ruleCode) | join(", "))]"'
  else
    head -c 200; echo
  fi
}

# Publish one transaction event to the 'transactions' topic, then poll the query API
# for the resulting decision (the consumer evaluates and stores asynchronously). The
# JSON is collapsed to a single line because the console producer treats each line as a
# separate message. Sets BODY (decision JSON, empty on timeout) and FOUND (true/false).
publish_txn() {
  local json eid
  json="$(printf '%s' "$1" | tr '\n' ' ')"
  eid="$2"
  printf '%s\n' "$json" | docker compose exec -T kafka /opt/kafka/bin/kafka-console-producer.sh \
    --bootstrap-server localhost:9092 --topic transactions >/dev/null 2>&1
  BODY=""
  FOUND=false
  for _ in $(seq 1 40); do
    if BODY="$(curl -sf "$BASE/api/evaluations/$eid")"; then FOUND=true; break; fi
    sleep 0.5
  done
  if ! $FOUND; then echo "  → not consumed within 20s — is the app's Kafka connection up?"; fi
}

# Read the dead-letter topic from the beginning and assert a record carrying $1 arrived.
assert_dead_lettered() {
  local marker="$1" out
  out="$(docker compose exec -T kafka /opt/kafka/bin/kafka-console-consumer.sh \
    --bootstrap-server localhost:9092 --topic transactions-dlt \
    --from-beginning --timeout-ms 10000 2>/dev/null || true)"
  if grep -q "$marker" <<<"$out"; then
    grep "$marker" <<<"$out" | sed 's/^/  /'
    echo "  → dead-lettered ✓ (also visible in Kafbat UI → Topics → transactions-dlt)"
  else
    echo "  → not in the DLT yet — check Kafbat UI → Topics → transactions-dlt"
  fi
}

# ── Preflight ────────────────────────────────────────────────────────────────

if ! curl -sf "$BASE/actuator/health" >/dev/null; then
  echo "App not reachable at $BASE." >&2
  echo "Start the stack first:  docker compose --profile demo up --build" >&2
  exit 1
fi

bold "Fraud Rule Engine demo (run id: $RUN)"
echo "App: $BASE   Swagger UI (query API): $BASE/swagger-ui.html   Kafbat UI: http://localhost:8088"

# ── 1. Normal transaction ────────────────────────────────────────────────────

step "1/11  Normal transaction" \
"A routine daytime purchase, published to the 'transactions' topic. The consumer
evaluates and stores it; we poll the query API for the decision. All 8 rules run;
none should fire. (Watch it arrive in Kafbat UI → Topics → transactions.)"
EID="$(uuid)"
publish_txn "{\"eventId\":\"$EID\",\"transactionId\":\"txn-$RUN-1\",\"customerId\":\"cust-$RUN-alice\",
  \"amount\":1499.50,\"currency\":\"ZAR\",\"category\":\"POS\",\"merchant\":\"Checkers\",
  \"country\":\"ZA\",\"occurredAt\":\"${TODAY}T12:00:00Z\"}" "$EID"
printf '%s' "$BODY" | pretty
pause

# ── 2. High amount → flagged ─────────────────────────────────────────────────

step "2/11  High-amount transaction → flagged" \
"R25 000 online — AMOUNT_THRESHOLD fires (score 50 ≥ flag threshold 50).
Note the per-rule audit trail: every rule explains itself, hit or miss."
BIG_EID="$(uuid)"
publish_txn "{\"eventId\":\"$BIG_EID\",\"transactionId\":\"txn-$RUN-2\",\"customerId\":\"cust-$RUN-alice\",
  \"amount\":25000.00,\"currency\":\"ZAR\",\"category\":\"ONLINE\",\"merchant\":\"ACME Online\",
  \"country\":\"ZA\",\"occurredAt\":\"${TODAY}T14:00:00Z\"}" "$BIG_EID"
printf '%s' "$BODY" | pretty
pause

# ── 3. Idempotent replay ─────────────────────────────────────────────────────

step "3/11  Replay the same eventId → idempotency" \
"Kafka is at-least-once: the same event can be delivered twice. A fresh event is
published, then published again with the same eventId. The decision is stored once
— the customer ends up with exactly one evaluation, not two."
IVAN="cust-$RUN-ivan"
DUP_EID="$(uuid)"
DUP_JSON="{\"eventId\":\"$DUP_EID\",\"transactionId\":\"txn-$RUN-3\",\"customerId\":\"$IVAN\",
  \"amount\":880.00,\"currency\":\"ZAR\",\"category\":\"POS\",\"merchant\":\"Woolworths\",
  \"country\":\"ZA\",\"occurredAt\":\"${TODAY}T15:00:00Z\"}"
publish_txn "$DUP_JSON" "$DUP_EID"
printf '%s' "$BODY" | summary
echo "  ...publishing the identical event again (same eventId)..."
publish_txn "$DUP_JSON" "$DUP_EID"
sleep 1
echo "  GET /api/evaluations?customerId=$IVAN  → expect totalElements=1"
curl -sf "$BASE/api/evaluations?customerId=$IVAN" \
  | { if command -v jq >/dev/null 2>&1; then jq -r '"  → stored evaluations for this customer: \(.page.totalElements)"'; else cat; fi; }
pause

# ── 4. Velocity burst: rules corroborating ───────────────────────────────────

step "4/11  Velocity burst at 2am → rules corroborate" \
"Four POS purchases within 8 minutes, at 02:00 SAST. Each alone stays below the
flag threshold (ODD_HOURS scores 20 < 50) — but the 4th event in the 10-minute
window trips VELOCITY (30), and 30 + 20 = 50 → flagged. No single rule decides;
the score accumulates."
M="cust-$RUN-mallory"
i=0
for spec in "00:00:00 610.00 Spar" "00:02:00 720.00 Sasol" "00:04:00 830.00 KFC"; do
  i=$((i+1))
  read -r at amt merchant <<<"$spec"
  EID="$(uuid)"
  publish_txn "{\"eventId\":\"$EID\",\"transactionId\":\"txn-$RUN-v$i\",\"customerId\":\"$M\",
    \"amount\":$amt,\"currency\":\"ZAR\",\"category\":\"POS\",\"merchant\":\"$merchant\",
    \"country\":\"ZA\",\"occurredAt\":\"${TODAY}T${at}Z\"}" "$EID"
  printf '%s' "$BODY" | summary
done
echo
echo "  ...and the 4th:"
EID="$(uuid)"
publish_txn "{\"eventId\":\"$EID\",\"transactionId\":\"txn-$RUN-v4\",\"customerId\":\"$M\",
  \"amount\":940.00,\"currency\":\"ZAR\",\"category\":\"POS\",\"merchant\":\"Clicks\",
  \"country\":\"ZA\",\"occurredAt\":\"${TODAY}T00:06:00Z\"}" "$EID"
printf '%s' "$BODY" | pretty
pause

# ── 5. ML anomaly scorer (shadow mode) ──────────────────────────────────────

step "5/11  ML anomaly scorer → fires in shadow, decision unaffected" \
"An Isolation Forest, trained at startup on synthetic 'normal' data, runs as an
advisory rule (ADR-0006). Nomsa's usual spend is ~R500. After a few normal
purchases, an R8 500 purchase arrives: below the R10 000 hard threshold, so no
scoring rule fires and it is NOT flagged — but it is wildly out of pattern for
her, so ANOMALY_SCORE hits. Shadow mode = recorded for review, never counted
toward the flag. The model catches what the fixed thresholds miss."
NOMSA="cust-$RUN-nomsa"
for spec in "08:10:00 480.00 Spar" "11:30:00 520.00 Clicks" "13:15:00 450.00 Dischem"; do
  read -r at amt merchant <<<"$spec"
  EID="$(uuid)"
  publish_txn "{\"eventId\":\"$EID\",\"transactionId\":\"txn-$RUN-n-$at\",\"customerId\":\"$NOMSA\",
    \"amount\":$amt,\"currency\":\"ZAR\",\"category\":\"POS\",\"merchant\":\"$merchant\",
    \"country\":\"ZA\",\"occurredAt\":\"${TODAY}T${at}Z\"}" "$EID"
  printf '%s' "$BODY" | summary
done
echo
echo "  ...now an R8 500 purchase — high for Nomsa, but under the hard threshold:"
ANOM_EID="$(uuid)"
publish_txn "{\"eventId\":\"$ANOM_EID\",\"transactionId\":\"txn-$RUN-n-anom\",\"customerId\":\"$NOMSA\",
  \"amount\":8500.00,\"currency\":\"ZAR\",\"category\":\"POS\",\"merchant\":\"Incredible Connection\",
  \"country\":\"ZA\",\"occurredAt\":\"${TODAY}T13:40:00Z\"}" "$ANOM_EID"
printf '%s' "$BODY" | pretty
echo "  → flagged=false (no scoring rule fired), but ANOMALY_SCORE shows hit=true in the"
echo "    audit trail — flagged for review without blocking the customer."
pause

# ── 6. Card-testing burst: rules corroborating ───────────────────────────────

step "6/11  Card-testing burst → CARD_TESTING + VELOCITY corroborate" \
"Stolen card details get validated with rapid bursts of tiny payments before the
real spend. Five sub-R50 online charges land within five minutes — each trivially
below the R10 000 amount threshold. The 4th already trips VELOCITY (30, >3 in
10min); the 5th adds CARD_TESTING (45, five micro-amounts in the window), and
45 + 30 = 75 → flagged. The pattern, not any single amount, gives it away."
TREVOR="cust-$RUN-trevor"
i=0
for spec in "09:00:00 7.00 Spotify" "09:01:00 4.50 Steam" "09:02:00 9.99 Netflix" "09:03:00 3.20 PlayStation"; do
  i=$((i+1))
  read -r at amt merchant <<<"$spec"
  EID="$(uuid)"
  publish_txn "{\"eventId\":\"$EID\",\"transactionId\":\"txn-$RUN-ct$i\",\"customerId\":\"$TREVOR\",
    \"amount\":$amt,\"currency\":\"ZAR\",\"category\":\"ONLINE\",\"merchant\":\"$merchant\",
    \"country\":\"ZA\",\"occurredAt\":\"${TODAY}T${at}Z\"}" "$EID"
  printf '%s' "$BODY" | summary
done
echo
echo "  ...and the 5th micro-charge:"
EID="$(uuid)"
publish_txn "{\"eventId\":\"$EID\",\"transactionId\":\"txn-$RUN-ct5\",\"customerId\":\"$TREVOR\",
  \"amount\":6.40,\"currency\":\"ZAR\",\"category\":\"ONLINE\",\"merchant\":\"Apple\",
  \"country\":\"ZA\",\"occurredAt\":\"${TODAY}T09:04:00Z\"}" "$EID"
printf '%s' "$BODY" | pretty
pause

# ── 7. Geographic corroboration: impossible travel + high-risk country ────────

step "7/11  Card cloned abroad → IMPOSSIBLE_TRAVEL + HIGH_RISK_COUNTRY corroborate" \
"A card is swiped at a Johannesburg POS at 10:00, then used at an ATM in another
country 90 minutes later — no one travels that fast, so the card is cloned. The
foreign withdrawal trips IMPOSSIBLE_TRAVEL (40), and the destination is on the
configured high-risk list, adding HIGH_RISK_COUNTRY (15). Neither flags alone
(both < 50), but together 55 → flagged. Two weak geographic signals make a strong
one. (The high-risk list ships empty in the app; the demo stack populates a sample
— see FRAUD_RULES_HIGH_RISK_COUNTRY_COUNTRIES in docker-compose.yml.)"
SIPHO="cust-$RUN-sipho"
HOME_EID="$(uuid)"
publish_txn "{\"eventId\":\"$HOME_EID\",\"transactionId\":\"txn-$RUN-geo1\",\"customerId\":\"$SIPHO\",
  \"amount\":650.00,\"currency\":\"ZAR\",\"category\":\"POS\",\"merchant\":\"Pick n Pay\",
  \"country\":\"ZA\",\"occurredAt\":\"${TODAY}T10:00:00Z\"}" "$HOME_EID"
printf '%s' "$BODY" | summary
echo
echo "  ...then a cash withdrawal in a high-risk jurisdiction, 90 minutes later:"
GEO_EID="$(uuid)"
publish_txn "{\"eventId\":\"$GEO_EID\",\"transactionId\":\"txn-$RUN-geo2\",\"customerId\":\"$SIPHO\",
  \"amount\":3000.00,\"currency\":\"ZAR\",\"category\":\"ATM\",\"merchant\":\"ATM Withdrawal\",
  \"country\":\"IR\",\"occurredAt\":\"${TODAY}T11:30:00Z\"}" "$GEO_EID"
printf '%s' "$BODY" | pretty
pause

# ── 8. Poison message → dead-letter topic ────────────────────────────────────

step "8/11  Poison message → dead-letter topic" \
"Malformed JSON on the topic. Instead of blocking the partition in a redelivery
loop, it is routed to 'transactions-dlt' on first encounter."
docker compose exec -T kafka /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server localhost:9092 --topic transactions <<EOF
{"poison-$RUN": this is not valid JSON
EOF
echo "  published poison message — reading it back from transactions-dlt..."
sleep 3
assert_dead_lettered "poison-$RUN"
pause

# ── 9. Validation failure → dead-letter topic ────────────────────────────────

step "9/11  Business-invalid event → dead-letter topic" \
"Well-formed JSON, but a negative amount violates the @Positive constraint. It
deserializes cleanly, fails Bean Validation in the consumer, and is dead-lettered
(non-retryable) — never stored. The constraints are enforced once, at the edge."
BAD_EID="$(uuid)"
docker compose exec -T kafka /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server localhost:9092 --topic transactions <<EOF
{"eventId":"$BAD_EID","transactionId":"txn-$RUN-bad","customerId":"cust-$RUN-bad","amount":-5.00,"currency":"ZAR","category":"POS","merchant":"Nowhere","country":"ZA","occurredAt":"${TODAY}T16:00:00Z"}
EOF
echo "  published invalid event $BAD_EID — reading it back from transactions-dlt..."
sleep 3
assert_dead_lettered "$BAD_EID"
echo "  GET /api/evaluations/$BAD_EID  → expect 404 (never stored)"
echo "  HTTP $(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/evaluations/$BAD_EID")"
pause

# ── 10. Query API ────────────────────────────────────────────────────────────

step "10/11  Query API: filters + pagination" \
"Everything stored is retrievable: by customer, time range, and flagged status,
newest-first with a pagination envelope (per-rule detail lives on
GET /api/evaluations/{eventId})."
echo "  GET /api/evaluations?customerId=$M&flagged=true&from=...&to=..."
echo
curl -sf "$BASE/api/evaluations?customerId=$M&flagged=true&from=${TODAY}T00:00:00Z&to=${TODAY}T23:59:59Z" | pretty
pause

# ── 11. Where to look next ───────────────────────────────────────────────────

step "11/11  Where to look next" \
"  Swagger UI (interactive query API):  $BASE/swagger-ui.html
  Kafbat UI (topics, consumer lag, DLT):  http://localhost:8088
  Test suite (Testcontainers, needs Docker):  ./mvnw test
  Design decisions:  docs/adr/"
bold "Demo complete."
