#!/usr/bin/env bash
# Ingestion throughput benchmark.
#
# Produces a batch of valid transaction events to the `transactions` topic (keyed by
# customerId), waits for the consumer to drain the backlog, and reports end-to-end
# throughput plus the app-side per-evaluation latency read off /actuator/prometheus.
#
# Run the two configurations from a clean stack to compare (see load/README.md):
#   baseline:  FRAUD_KAFKA_PARTITIONS=1 SPRING_KAFKA_LISTENER_CONCURRENCY=1
#   scaled:    FRAUD_KAFKA_PARTITIONS=6 SPRING_KAFKA_LISTENER_CONCURRENCY=6
#
# Usage:  ./load/ingest.sh        (uses EVENTS/CUSTOMERS/DAYS env, defaults 50000/2000/7)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

EVENTS="${EVENTS:-50000}"
CUSTOMERS="${CUSTOMERS:-2000}"
DAYS="${DAYS:-7}"
TOPIC="${TOPIC:-transactions}"
GROUP="${GROUP:-fraud-rule-engine}"
PROM_URL="${PROM_URL:-http://localhost:8080/actuator/prometheus}"
EVENTS_FILE="${EVENTS_FILE:-load/events.tsv}"

kafka() { docker compose exec -T kafka "$@"; }
prom() { curl -s "$PROM_URL"; }

# Sum of fraud_evaluations_total across all outcome labels.
eval_count() { prom | awk '/^fraud_evaluations_total\{/ {s+=$2} END {print s+0}'; }

# Per-event latency (mean p50 p95 p99, milliseconds) from the evaluation timer's histogram.
# The timer publishes a percentile histogram (FraudMetrics), so we read the percentiles off the
# cumulative buckets (smallest le whose cumulative count crosses the target). Assumes a clean
# stack per run (the documented procedure), so the cumulative histogram reflects exactly this
# run. See load/README.md.
latency_ms() {
  prom | awk '
    /^fraud_evaluation_duration_seconds_bucket/ {
      split($0, parts, "\""); n++; le[n] = parts[2]; cum[n] = $2
    }
    /^fraud_evaluation_duration_seconds_count/ { total = $2 }
    /^fraud_evaluation_duration_seconds_sum/   { tsum = $2 }
    function ms(p,   t, i) {
      t = p * total
      for (i = 1; i <= n; i++)
        if (cum[i] + 0 >= t)
          return (le[i] == "+Inf") ? "Inf" : sprintf("%.2f", le[i] * 1000)
      return "Inf"
    }
    END {
      if (total + 0 == 0) print "n/a n/a n/a n/a"
      else printf "%.2f %s %s %s\n", tsum / total * 1000, ms(0.5), ms(0.95), ms(0.99)
    }
  '
}

total_lag() {
  kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
    --describe --group "$GROUP" 2>/dev/null \
    | awk 'NR>1 && $6 ~ /^[0-9]+$/ {lag+=$6} END {print lag+0}'
}

echo "==> Generating $EVENTS events across $CUSTOMERS customers over $DAYS days"
EVENTS="$EVENTS" CUSTOMERS="$CUSTOMERS" DAYS="$DAYS" java load/GenerateEvents.java > "$EVENTS_FILE"
echo "    wrote $(wc -l < "$EVENTS_FILE" | tr -d ' ') lines to $EVENTS_FILE"

echo "==> Checking app is up"
curl -fs "http://localhost:8080/actuator/health" >/dev/null || { echo "app not healthy on :8080"; exit 1; }

before_count="$(eval_count)"
echo "==> Producing to '$TOPIC' (keyed by customerId)"
start="$(date +%s.%N)"
kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 \
  --topic "$TOPIC" --property parse.key=true < "$EVENTS_FILE"

echo "==> Waiting for consumer to drain..."
while :; do
  lag="$(total_lag)"
  printf '\r    lag=%s   ' "$lag"
  [ "$lag" = "0" ] && break
  sleep 1
done
end="$(date +%s.%N)"
echo

after_count="$(eval_count)"

processed="$(echo "$after_count - $before_count" | bc)"
elapsed="$(echo "$end - $start" | bc)"
throughput="$(echo "scale=1; $processed / $elapsed" | bc)"
read -r mean p50 p95 p99 <<< "$(latency_ms)"

echo
echo "================ ingestion result ================"
printf '  partitions          : %s\n' "${FRAUD_KAFKA_PARTITIONS:-1 (default)}"
printf '  listener concurrency: %s\n' "${SPRING_KAFKA_LISTENER_CONCURRENCY:-1 (default)}"
printf '  events processed    : %s\n' "$processed"
printf '  wall-clock elapsed  : %s s (produce → fully drained)\n' "$elapsed"
printf '  throughput          : %s events/s\n' "$throughput"
printf '  per-event latency   : mean %s ms | p50 %s ms | p95 %s ms | p99 %s ms\n' \
  "$mean" "$p50" "$p95" "$p99"
echo "=================================================="
