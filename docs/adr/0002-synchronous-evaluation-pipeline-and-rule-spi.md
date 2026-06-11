# ADR-0002: Evaluation pipeline and rule SPI

- **Status:** accepted
- **Date:** 2026-06-11
- **Update (2026-06-16):** the REST write endpoint described below (`POST /api/transactions`) was later **removed** — ingestion is now Kafka-only (ADR-0004) and the API is retrieval-only. The decisions this ADR fixes all still stand: the `FraudEvaluationService` seam, single-transaction evaluate-and-store, the rule SPI, idempotency on `eventId`, and config-driven tuning. Notably, removing the endpoint was a one-class change *because* the processing logic lives behind the service seam rather than in the controller — which is exactly what this ADR set out to guarantee. Evaluation is still synchronous, now on the consumer thread instead of the request thread.

## Context

The brief's core is one path: process a categorized transaction event, apply a set of
fraud rules, flag and store the outcome, and allow retrieval via an API. ADR-0001 laid
the schema; this ADR decides how an event flows through the engine and how rules plug in.

We are building this as a **thin REST-first vertical slice** (PLAN.md, "build core as a
thin vertical slice first, then widen"): one ingest endpoint, one rule, one retrieval
endpoint — enough to demonstrate all four functional requirements end-to-end — before
widening with more rules (step 4), Kafka ingestion (step 3's primary path), and richer
queries (step 5).

Two questions need deciding now because everything downstream sits on them:

1. **Processing model** — is evaluation synchronous with ingestion, or decoupled?
2. **Rule extensibility** — how do rules plug in, given there is no rule-management table
   (ADR-0001 keeps rules as code beans)?

## Decision

**Synchronous, request-scoped evaluation.** `POST /api/transactions` persists the event,
runs the rule engine, and persists the `FraudEvaluation` inside a single transaction,
returning the decision in the response. There is no queue or async worker in this slice.

**Ingestion and evaluation are separated behind a service**, not the controller.
`FraudEvaluationService.ingestAndEvaluate(...)` owns the transaction boundary and the
idempotency check. The HTTP controller is a thin adapter over it. This is deliberate: the
Kafka consumer (step 3) becomes a *second* adapter onto the same service method, so the
processing logic is written once and the entry point varies.

**Rules are a code SPI.** A `Rule` interface:

```java
interface Rule {
    String code();
    String version();
    RuleOutcome evaluate(TransactionEvent event);   // hit?, score, detail
}
```

Every `Rule` bean on the classpath is discovered by Spring and run by a `RuleEngine`
against each event. The engine records **one `RuleResult` per rule, hit or not** (the
audit trail ADR-0001 committed to), sums the scores, and sets
`flagged = totalScore >= flagThreshold`. This slice ships exactly one rule,
`AmountThresholdRule`; adding rules later means adding a bean, nothing else.

**Idempotency.** The service checks `existsByEventId` first. A replayed `eventId` returns
the already-stored evaluation rather than re-evaluating or double-storing — honouring the
unique constraints from ADR-0001 and preparing for Kafka's at-least-once delivery.

**Configuration.** Rule parameters and the flag threshold are externalized via
`@ConfigurationProperties` (`fraud.rules.*`), env-overridable, so tuning a threshold is
config, not code.

## Alternatives considered

- **Ingest now, evaluate asynchronously** (persist event → publish/queue → worker
  evaluates). Closer to where the Kafka path lands and better for load-shedding, but it
  adds a queue, eventual-consistency on the read side, and a polling/notify story — all
  cost with no payoff for a slice whose job is to demonstrate the core. Deferred; the
  service seam means adopting it later does not rewrite the rules.
- **Rules as data in a table, interpreted at runtime.** Powerful (rule management without
  deploys) but it invents the rule-management requirement ADR-0001 explicitly declined,
  and an interpreter is a lot of surface for one threshold rule. Deferred to the rule-
  lifecycle differentiator if accepted.
- **Evaluate in the controller.** Fewer classes, but the Kafka entry point would then
  duplicate the logic or call into the controller. Rejected — the service seam is cheap
  and is the whole reason the later ingestion path stays simple.
- **Persist only rules that fired.** Smaller tables, but loses the "why didn't this fire"
  audit grain and breaks the shadow/hindsight analytics the schema was shaped for.
  Rejected — store every result.

## Consequences

- Easier: one place (`FraudEvaluationService`) owns ingest+evaluate, so Kafka (step 3) is
  a thin adapter; adding rules (step 4) is adding a bean; thresholds tune via config; the
  whole core is demoable with two `curl`s.
- Committed: evaluation latency is on the request path (fine at this scale; revisit if a
  rule ever does I/O — e.g. the LLM advisory idea, which is why that idea is scoped as
  async/shadow). The flag decision is a simple score-threshold sum; weighting or tiers
  remain separate proposed ideas with their own ADRs.
- Enforced by tests: rule and engine unit tests, a web-slice validation test, and a
  Testcontainers round-trip + idempotent-replay integration test.
