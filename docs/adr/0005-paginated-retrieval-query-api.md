# ADR-0005: Paginated retrieval query API

- **Status:** accepted
- **Date:** 2026-06-12

## Context

The brief requires "retrieval of this data via an API". Until now that was a single
lookup, `GET /api/evaluations/{eventId}` — enough to demonstrate the pipeline end-to-end,
but not how a fraud system is actually consumed: an analyst or downstream service lists a
customer's evaluations, narrows to a time window, and filters to what was flagged
(PLAN step 5; backlog item "Query API: flags by transaction / customer / time range,
paginated"). The schema anticipated this: `ix_transaction_event_customer_time`
(customer_id, occurred_at) exists since V1.

Forces: filters must be freely combinable (customer alone, time range alone, both,
each with or without a flagged filter), result sets are unbounded so pagination is
mandatory, and the list response must not drag the per-rule audit trail along —
paginating a JPA collection fetch degenerates to in-memory pagination.

## Decision

One list endpoint, `GET /api/evaluations`, with all filters optional and combinable:

- `customerId` — exact match.
- `from` / `to` — ISO-8601 instants forming a **half-open interval `[from, to)`** over
  the transaction's **`occurredAt`** (business time, when the transaction happened),
  not `evaluatedAt` (processing time). Business time is what an investigator means by
  "that night"; it is also what the existing index covers. `from > to` is a 400.
- `flagged` — tri-state: absent (all), `true`, `false`.
- `page` (default 0) / `size` (default 20, max 100) — **offset pagination**, fixed sort
  `occurredAt desc, id desc` (deterministic tiebreak; no client-supplied sort).

The list returns **summaries** (event identifiers, amount/currency/category, decision,
timestamps) without rule results; the existing `GET /api/evaluations/{eventId}` remains
the detail view with the full per-rule trail. Response envelope is Spring Data's
`PagedModel` JSON shape: `content` + `page {size, number, totalElements, totalPages}`.

Implementation: dynamic filters are composed as **JPA Specifications**
(`JpaSpecificationExecutor`), with the filter object (`EvaluationQuery`) owning its own
validation. A V2 migration adds an index on `transaction_event (occurred_at)` for
time-range queries that don't name a customer.

## Alternatives considered

- **Derived query methods** — one method per filter combination; 4 optional filters
  means a combinatorial explosion of `findBy…` permutations. Lost on maintainability.
- **Single JPQL query with `(:param is null or …)` guards** — compact, but null-valued
  typed parameters (`timestamptz`) hit PostgreSQL/Hibernate parameter type-inference
  problems, and every added filter re-complicates one growing query. Specifications
  keep each predicate independent.
- **Keyset (cursor) pagination** — the right answer at real scale (offset degrades as
  `OFFSET` grows and pages drift under concurrent inserts), but it complicates the API
  contract (opaque cursors, no jump-to-page, no totals) for no benefit at this data
  volume. Offset chosen; the fixed `(occurredAt, id)` sort is deliberately a valid
  keyset, so migrating later is additive.
- **Filtering on `evaluatedAt`** — processing time only matters for ops questions
  ("what did the engine do yesterday"); the fraud questions are about business time.
- **Full `EvaluationResponse` (with rule results) in the list** — forces either a
  paginated collection fetch (Hibernate falls back to in-memory pagination) or an N+1
  per row; and a list view doesn't need the audit trail. Summary + detail split instead.

## Consequences

- The brief's retrieval requirement is now met in the shape a fraud workflow needs;
  by-customer and time-window questions are one GET away.
- Two response shapes (summary vs detail) to keep in sync with the domain — accepted
  cost of not over-fetching.
- Offset pagination is a known scale ceiling, recorded here deliberately; the sort
  order already doubles as a keyset, so the upgrade path is additive, not breaking.
- The repository gains `JpaSpecificationExecutor`; future filters (e.g. category,
  score range) are one predicate each, no API redesign.
