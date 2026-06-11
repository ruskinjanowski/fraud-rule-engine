# ADR-0004: Kafka consumer as ingestion adapter

- **Status:** accepted
- **Date:** 2026-06-12
- **Update (2026-06-16):** the original decision kept REST alongside Kafka ("primary, not only"). That was **reversed** — the `POST /api/transactions` write endpoint was removed and **Kafka is the sole ingestion path**; the REST API is retrieval-only. In effect the "Drop REST, Kafka-only" alternative below was adopted. Rationale: a single event-driven ingress means one schema, one set of edge validation, and one surface to secure/rate-limit, and the brief asks only for *retrieval* via an API. The wire-contract record was also renamed `TransactionEventMessage` and moved to the `messaging` package (it is no longer shared with an HTTP path). Everything else here stands.

## Context

The brief is to *process* categorized transaction events; the job description names
**Kafka** and **event-driven architecture** as core stack signals. The REST-first slice
(ADR-0002) deliberately landed ingestion behind `FraudEvaluationService.ingestAndEvaluate(...)`
so that "the Kafka consumer becomes a *second* adapter onto the same service method". This
ADR cashes that in — PLAN.md step 3's primary path.

What needs deciding is the contract and the failure semantics, not the processing logic
(which already exists and is reused unchanged):

1. **Wire contract** — what shape do messages take, and how is the same validation the
   REST path enforces applied to a Kafka payload?
2. **Delivery semantics** — Kafka is at-least-once; what stops redelivery from
   double-processing?
3. **Un-processable messages** — a malformed or invalid payload must not wedge the
   partition (infinite retry on a poison record blocks every event behind it).
4. **Does REST stay?** — "Kafka primary" could mean "Kafka only".

## Decision

**A `@KafkaListener` adapter, not new processing logic.** A `TransactionEventConsumer`
deserializes the message, validates it, maps it to a domain `TransactionEvent`, and calls
the existing `FraudEvaluationService.ingestAndEvaluate(...)`. The rule engine, persistence,
transaction boundary, and idempotency are untouched.

**One validated wire-contract record.** The Kafka message is a JSON record (then named
`IngestTransactionRequest`, shared with the REST body; since renamed `TransactionEventMessage`
— see the Update note) carrying Bean Validation constraints. The consumer validates the
deserialized record programmatically via an injected `jakarta.validation.Validator`. A
validation failure is treated as a non-retryable poison message (below).

**At-least-once is absorbed by existing idempotency.** No new dedup machinery: the service
is already idempotent on `eventId` (ADR-0001/0002), so a redelivered record returns the
stored evaluation instead of re-evaluating or double-storing. Container-managed offset
commit (Spring's default, after the listener returns) is sufficient — at-least-once plus an
idempotent consumer is effectively exactly-once *effect*.

**Poison messages go to a dead-letter topic.** An `ErrorHandlingDeserializer` wraps Spring
Kafka's Jackson 3 `JacksonJsonDeserializer` (the legacy `JsonDeserializer` is Jackson 2 and
deprecated under Boot 4) so a bad payload surfaces as a recoverable record rather than
killing the container. A `DefaultErrorHandler` with a `DeadLetterPublishingRecoverer` retries
**transient** failures (e.g. a momentary DB outage) a bounded number of times, then routes
**non-retryable** failures — deserialization errors and validation failures — to
`<topic>-dlt` (Spring Kafka's default dead-letter name, kept rather than fought).
Validation failures are classified as not-to-be-retried so they are dead-lettered on first
encounter rather than retried pointlessly. The DLT preserves the original payload for
inspection and replay — raw bytes for records that never deserialized, re-serialized JSON
for validation failures — and the partition keeps moving.

**REST stays.** *(Superseded 2026-06-16 — see the Update note: the REST write endpoint was
removed and Kafka is now the sole ingestion path.)* As originally decided: the two adapters
coexist over one service, Kafka being the path the system is built around while
`POST /api/transactions` remained for demo/`curl`.

**Configuration.** The topic is `fraud.kafka.transactions-topic` (DLT name derived as
`<topic>-dlt`), env-overridable like the datasource and rule tuning. Consumer group,
deserializers, and bootstrap servers live under the standard `spring.kafka.*` properties.

## Alternatives considered

- **A separate Kafka message DTO** distinct from `IngestTransactionRequest`. Decouples the
  HTTP contract from the event contract, but here they are the same event with the same
  constraints; a parallel type would duplicate validation and drift. Rejected — one
  contract, validated one way.
- **Ingest-then-evaluate-asynchronously** (consume → persist raw → re-publish → worker
  evaluates). Closer to a large-scale pipeline and better for load-shedding, but it
  reintroduces the eventual-consistency/notify story ADR-0002 deliberately deferred, for no
  payoff at this scale. The service seam means we can adopt it later without touching rules.
  Rejected for this slice.
- **Log-and-skip on poison messages** (commit the offset, drop the record). Simpler — no
  producer or DLT topic — but discards bad messages with no audit or replay path, which is
  the wrong default for a fraud system where a dropped event is a missed decision. Rejected
  in favour of the DLT.
- **Drop REST, Kafka-only.** Fewer entry points; the cost at the time was losing the
  one-`curl` demo. Rejected here, but **later adopted** (2026-06-16, see the Update note):
  a single event-driven ingress is the right production shape, and the demo moved to
  publishing on the topic. One ingress, one schema, one validation surface.
- **Manual offset acknowledgement** in the listener. Unnecessary given idempotent
  processing; container-managed commit plus the error handler covers redelivery and poison
  records without hand-rolled ack logic. Rejected as needless surface.
- **Consume `String` and parse manually with the app's `ObjectMapper`.** One visible Jackson
  contract shared with REST and a single DLT template. The first cut shipped this way (as a
  workaround: the Jackson 2 `JsonDeserializer` can't read `Instant` under Boot 4), then was
  replaced by the deserializer chain above once `JacksonJsonDeserializer` proved to be the
  idiomatic Spring Kafka 4 answer — a typed listener keeps the adapter free of parsing
  concerns, and malformed payloads are handled by infrastructure instead of application code.

## Consequences

- Easier: the JD's headline signal (event-driven, Kafka) is now demonstrable end-to-end;
  ingestion scales off the request thread; producers fire-and-forget. Adding a third entry
  point later is again just an adapter.
- Committed: a dead-letter topic is now part of the contract — operating the service means
  watching `<topic>-dlt`. Evaluation still runs on the consumer thread inside one
  transaction (same trade-off as ADR-0002); a slow/IO-bound rule would back up consumption,
  which is exactly why the LLM-advisory idea is scoped async/shadow.
- Enforced by tests: a Testcontainers `KafkaContainer` (`@ServiceConnection`) integration
  test produces a real event and asserts it is evaluated, stored, and retrievable; a
  poison-message test asserts a bad payload lands on the DLT and the consumer keeps
  processing the next valid record.
