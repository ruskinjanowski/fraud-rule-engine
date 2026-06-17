# Fraud Rule Engine Service

Processes categorized transaction events, applies a set of fraud rules to each one,
flags potential fraud, persists the results, and exposes them via an API.

> Events are ingested via Kafka (with a
> dead-letter topic), evaluated against 8 deterministic fraud rulesplus an advisory
> ML anomaly scorer, stored with a per-rule
> audit trail, and retrieved by id or via a filtered, paginated query API.

## Tech stack

- Java 25, Spring Boot 4.1
- PostgreSQL 16 (schema managed by Flyway)
- Apache Kafka 3.9 (KRaft, single node)
- Docker / Docker Compose
- Testcontainers for integration tests

## Prerequisites

- Docker (with Compose v2) — the only hard requirement for running the stack.
- For building or testing on the host without Docker: JDK 25

## Run the whole stack (one command)

```bash
docker compose up --build
```

This builds the application image and starts Postgres, Kafka, and the app, in the
right order (the app waits for Postgres and Kafka to report healthy).

Tear down (and drop the Postgres volume):

```bash
docker compose down -v
```

## Demo

A guided walkthrough script covers the whole system: Kafka ingestion,
the per-rule audit trail, idempotent replay, a poison message landing in the
dead-letter topic, rules corroborating to cross the flag threshold and the
query API.

```bash
docker compose --profile demo up --build   # stack + Kafbat UI (Kafka browser)
./demo.sh                                  # press Enter to advance; --no-pause to run through
```

The `demo` profile adds [Kafbat UI](https://github.com/kafbat/kafka-ui) at
**http://localhost:8088** — browse the `transactions` topic, the consumer
group's lag, and the `transactions-dlt` dead-letter topic live while the
demo runs. The core stack doesn't need it; plain `docker compose up` skips it.

## Ingestion

Ingestion is event-driven and **Kafka-only**: producers publish to the `transactions` topic. The event is persisted, run through the rule set, and the decision stored. `eventId` is the
producer-supplied idempotency key, so a replayed event (Kafka delivers at-least-once) returns
the existing decision instead of re-processing.

### Publish an event to the `transactions` topic

The message is a JSON document of the shape below. With the stack up:

```bash
docker compose exec -T kafka /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server localhost:9092 --topic transactions <<'EOF'
{"eventId":"22222222-2222-2222-2222-222222222222","transactionId":"txn-2","customerId":"cust-2","amount":25000.00,"currency":"ZAR","category":"ONLINE","merchant":"ACME Online","country":"ZA","occurredAt":"2026-06-11T02:30:00Z"}
EOF
```

The event is consumed, evaluated, and stored asynchronously — retrieve the decision via the
`GET` endpoint below. A message that can't be processed (malformed JSON, or one that fails Bean
Validation — e.g. a negative amount) is routed to the **`transactions-dlt`** dead-letter topic
rather than blocking the partition.

## API

The API is the retrieval side of the brief — a single resource:

- **`/api/evaluations`** — retrieve stored decisions by event ID, or list them filtered by customer, time range, and flagged status (paginated, newest-first).

Errors are returned as RFC 7807 `application/problem+json`.

Full request/response schemas, parameters, and example payloads are in the interactive docs:

**[http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html)**

(OpenAPI JSON: `http://localhost:8080/v3/api-docs`)

## Develop locally (app from your IDE)

Run only the infrastructure in Docker, then start the app from your IDE or the wrapper.
The app defaults to `localhost` for both Postgres and Kafka, so no extra config is needed.

```bash
docker compose up postgres kafka      # infrastructure only
./mvnw spring-boot:run                # app on the host
```

| Service  | Host address     |
|----------|------------------|
| App      | `localhost:8080` |
| Postgres | `localhost:5432` |
| Kafka    | `localhost:9094` |

## Build

```bash
./mvnw clean package                  # produces target/fraud-0.0.1-SNAPSHOT.jar
docker build -t fraud-rule-engine .   # or build the image directly
```

## Test

Integration tests use [Testcontainers](https://testcontainers.com/), which start
throwaway Postgres and Kafka containers — **a running Docker daemon is required.**

```bash
./mvnw test
```

The same `./mvnw verify` plus a Docker image build run on every push and pull request via
GitHub Actions ([`.github/workflows/ci.yml`](.github/workflows/ci.yml)) — Testcontainers
runs unchanged on the Docker-equipped runners.

## Configuration

Connection details are read from environment variables with localhost defaults
(see [`application.yaml`](src/main/resources/application.yaml)):

| Variable                          | Default                                       |
|-----------------------------------|-----------------------------------------------|
| `SPRING_DATASOURCE_URL`           | `jdbc:postgresql://localhost:5432/fraud_rule_engine` |
| `SPRING_DATASOURCE_USERNAME`      | `fraud_app`                                   |
| `SPRING_DATASOURCE_PASSWORD`      | `fraud`                                        |
| `SPRING_KAFKA_BOOTSTRAP_SERVERS`  | `localhost:9094`                              |

Docker Compose overrides these to point at the in-network service names.

The app connects as the least-privilege `fraud_app` role against a dedicated
`fraud_rule_engine` database (created by [`docker/initdb`](docker/initdb)). The
`postgres` superuser (password `postgres`) is reserved for admin/maintenance —
e.g. browsing the data in pgAdmin.

## Observability

Structured logs, trace correlation, and metrics — instrumented once at the service seam so
the single ingestion path and the query API are both covered.

- **Health** — `GET /actuator/health` (liveness/readiness probes enabled).
- **Metrics** — `GET /actuator/prometheus` exposes JVM/HTTP/Kafka metrics plus the domain
  meters: `fraud_evaluations_total{outcome}`, `fraud_rule_hits_total{rule}`,
  `fraud_evaluation_duration_seconds`, and `fraud_dlt_messages_total`.
- **Structured logging** — Micrometer Tracing puts a `traceId`/`spanId` on every request and
  consumed Kafka record, so logs correlate across components. In the container the logs are
  emitted as one ECS JSON object per line (`LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`, set in
  Compose); running from the IDE stays human-readable. No tracing backend is bundled — the
  ids live in the logs.

## Scaling & operations

Throughput characteristics and a local load
test are in **[docs/SCALING.md](docs/SCALING.md)** . In short:
ingestion is synchronous on the consumer thread, so it scales with `min(partitions, listener
concurrency)`; keying events by `customerId` lets the consumer fan out across partitions while
keeping each customer's events ordered for the stateful rules. On a laptop, going from 1→6
partitions/threads took ingestion from ~900 to ~2,440 events/s (~3×). Both knobs are
env-configurable (`FRAUD_KAFKA_PARTITIONS`, `SPRING_KAFKA_LISTENER_CONCURRENCY`), defaulting to 1.
