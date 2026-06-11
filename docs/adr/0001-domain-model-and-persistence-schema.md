# ADR-0001: Domain model and persistence schema

- **Status:** accepted
- **Date:** 2026-06-11

## Context

The brief requires us to process categorized transaction events, apply fraud rules,
flag potential fraud, and **store the results** for retrieval via an API. Step 2 lays
the data model everything downstream builds on. The schema is expensive to change once
the rule engine and API depend on it, so the shape is worth deciding deliberately now.

Forces:

- Ingestion will be Kafka-first (ADR pending, step 3) with at-least-once delivery, so we
  need an **idempotency key** to avoid double-storing the same event.
- The "persist per-rule results, not just the final verdict" idea (PLAN.md backlog, core-adjacent)
  is a prerequisite for the later shadow/hindsight differentiators. It is cheap to include
  now and painful to retrofit, so we include it without committing to those differentiators.
- Rules are code beans at this stage; there is no rule-management table yet.

## Decision

Three tables, with `fraud_evaluation` as the aggregate root over `rule_result`.

**`transaction_event`** — the ingested categorized event.
- `id` bigint identity PK; `event_id` UUID **unique** (external idempotency key).
- `transaction_id`, `customer_id` varchar; `amount` numeric(19,4); `currency` char(3) (ISO 4217).
- `category` varchar (the categorization: POS / ATM / ONLINE / TRANSFER / …).
- `merchant` varchar null; `country` char(2) null (ISO 3166-1, for geo rules later).
- `occurred_at` timestamptz (event time); `received_at` timestamptz default now() (ingestion time).
- Indexes: unique(`event_id`); (`customer_id`, `occurred_at`) for velocity rules and customer queries.

**`fraud_evaluation`** — one decision per event.
- `id` bigint identity PK; `transaction_event_id` bigint FK **unique** (one evaluation per event,
  so reprocessing the same event is idempotent).
- `flagged` boolean; `score` int (sum of rule contributions); `evaluated_at` timestamptz default now().

**`rule_result`** — one row per rule that ran (the audit trail).
- `id` bigint identity PK; `fraud_evaluation_id` bigint FK (ON DELETE CASCADE).
- `rule_code` varchar; `rule_version` varchar; `hit` boolean; `score` int; `detail` varchar (reason).

Decisions on representation:

- **Identity:** bigint identity PKs internally; a separate external `event_id` UUID for idempotency.
  Keeps joins and indexes cheap while decoupling from the producer's id scheme.
- **Decision outcome:** `flagged` boolean + numeric `score` only — exactly the brief's "flagging".
  No `decision`/tier column. Tiers (REVIEW/BLOCK) remain a separate proposed idea with its own ADR if accepted.
- **Rules referenced by `rule_code` + `rule_version` strings**, not a FK to a rules table. Rules are
  code beans now; recording the version leaves room for the rule-versioning idea without a table yet.
- **Money** as `BigDecimal` (numeric(19,4)); **timestamps** as `Instant` (timestamptz, UTC).
- Schema owned by **Flyway** (`V1__init.sql`); Hibernate runs `ddl-auto: validate` so entity/schema
  drift fails the build.

## Alternatives considered

- **UUID primary keys throughout.** Tidy for distributed systems, but heavier indexes and noisier
  logs; the external `event_id` UUID already covers cross-system identity. Rejected for internal PKs.
- **Single denormalized table** (event + decision + rule hits as columns/JSON). Simpler to write,
  but loses the per-rule audit grain and makes rule-level analytics impossible. Rejected.
- **`decision` enum / full tiers now.** Beyond the brief's flagging requirement; would pre-accept the
  tiers idea before its design. Rejected in favour of `flagged` + `score`.
- **FK from `rule_result` to a `rule` table.** No rule-management table exists yet; would invent
  requirements. Deferred — `rule_code`/`rule_version` strings suffice.

## Consequences

- Easier: idempotent ingestion (unique `event_id`, unique `transaction_event_id`); per-rule audit
  trail; customer/time-range retrieval (the index supports it); future shadow/hindsight analysis has
  the data it needs.
- Harder / committed: `rule_result` grows one row per rule per transaction (acceptable; prunable later).
  Adding decision tiers later needs a migration (accepted — YAGNI for now). `rule_version` is free text
  until a versioning scheme is designed.
- Entity/schema agreement is enforced by `ddl-auto: validate` + a Testcontainers `@DataJpaTest`.
