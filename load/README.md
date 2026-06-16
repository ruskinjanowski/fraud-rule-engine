# Load test harness

Reproduces the numbers in [docs/SCALING.md](../docs/SCALING.md). Two workloads:

- **Ingestion** — `ingest.sh`: produces valid events to the `transactions` topic and
  measures end-to-end throughput + per-evaluation latency.
- **Reads** — `read-load.js` (k6): the fraud-ops query mix against `GET /api/evaluations`.

Requirements: Docker, a JDK (the project targets 25), and the usual shell tools (`bash`,
`awk`, `bc`). k6 runs in a container, so no host install.

## Test data

`GenerateEvents.java` emits `<customerId>\t<json>` lines for `kafka-console-producer`'s
`parse.key=true`. It runs via the JDK's single-file source launcher (no build):
`java load/GenerateEvents.java`. Defaults (override via env): **50,000 events**, **2,000 customers**,
`occurredAt` spread over **7 days**, keyed by `customerId`. The why is in
[docs/SCALING.md](../docs/SCALING.md#test-data-and-why) — short version: thousands of keys
spread evenly across partitions, while ~25 events per customer give the stateful rules real
history to read.

## Run the comparison

The headline result is baseline vs. scaled. Run each from a **clean stack** so the topic is
created with the right partition count (partition count is only applied at topic creation).

**Baseline — 1 partition, 1 consumer thread (current default):**

```bash
docker compose down -v
docker compose up --build -d           # defaults: FRAUD_KAFKA_PARTITIONS=1, concurrency=1
./load/ingest.sh
```

**Scaled — 6 partitions, 6 consumer threads:**

```bash
docker compose down -v
FRAUD_KAFKA_PARTITIONS=6 SPRING_KAFKA_LISTENER_CONCURRENCY=6 docker compose up --build -d
FRAUD_KAFKA_PARTITIONS=6 SPRING_KAFKA_LISTENER_CONCURRENCY=6 ./load/ingest.sh
```

(Pass the two env vars to compose so the app picks them up; `ingest.sh` reads them only to
label its output.)

## Read load

With data already ingested and the stack up:

```bash
./load/run-read-load.sh
```

k6 prints `http_req_duration` p50/p90/p95/p99 and request rate, broken down by endpoint tag.

## Caveats

Single laptop, with Postgres, Kafka, and the app all co-resident and competing for the same
cores. These numbers describe the **shape** of the system — where the bottleneck is and how it
moves when you add partitions — not absolute production capacity. See
[docs/SCALING.md](../docs/SCALING.md#caveats).
