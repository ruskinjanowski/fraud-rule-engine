# ADR-0003: Stateful rules read history via a per-evaluation CustomerHistory context

- **Status:** accepted
- **Date:** 2026-06-11

## Context

Step 4 widens the engine from one rule to eight (see the catalog in
[rules.md](../rules.md)). Five of the new rules are **stateful** — velocity, cumulative
amount, card testing, duplicate transaction, impossible travel all ask "what has this
customer done recently?". The question is how rules get that history.

The original sketch (rules.md §4 before this ADR) was "a stateful rule is simply a bean
with a repository injected". Production rule-engine literature pushes a different shape:
the rule-evaluation step is stateless — rules evaluate facts; state and aggregates are
computed *next to* the rules and fed in (Nected's rule-engine design pattern, Sardine's
feature computation). Three forces matter here:

- **Query cost**: five stateful rules with their own repositories means five per-customer
  history queries per event, all hitting the same index for overlapping windows.
- **Testability**: repository-injected rules need mocks; pure rules unit-test with lists.
- **Honesty of the seam**: ADR-0002 declared "the engine is pure" — repository-injected
  rules keep the engine pure while making the rules impure one level down.

A third option surfaced during design: have the **producer send pre-computed aggregates**
(counts, sums, previous-country) in the event body, making every rule stateless.

## Decision

**A `CustomerHistory` context, loaded once per evaluation, passed to every rule.**

- The `Rule` SPI becomes `evaluate(TransactionEvent event, CustomerHistory history)`,
  plus two defaulted methods: `enabled()` (per-rule on/off from config — disabled rules
  are skipped entirely and leave no `rule_result`) and `historyWindow()` (how far back
  the rule looks; `ZERO` = stateless).
- `FraudEvaluationService` loads the history with **one indexed query**
  (`ix_transaction_event_customer_time`) covering `RuleEngine.requiredLookback()` — the
  max `historyWindow()` across enabled rules. Rules filter the shared list in memory.
- **Windows are anchored at the event's `occurredAt`** (event time, not wall clock), so
  evaluation is deterministic and replayable.
- The event under evaluation is **excluded by `eventId`** in the query — it is persisted
  before rules run in the same transaction, so the exclusion convention lives in one
  place instead of in every rule.
- `ImpossibleTravelRule` is scoped to card-present categories (`POS`, `ATM`): online
  purchases from abroad are unremarkable; a physical card changing countries faster than
  travel allows is the signal.

## Alternatives considered

- **Each stateful rule injects a repository.** Simplest per rule, but N queries per
  event, mock-based rule tests, and the purity claim quietly broken. Lost on all three
  forces once the rule count grew past one or two.
- **Producer-supplied aggregates in the event body** (e.g. `transactionCountLast10Min`).
  Rejected on principle: *the producer should send what only the producer can know; the
  engine computes what only the engine can know.* Cross-event features can only be
  computed by the component that sees all events — which is this engine, since the brief
  has it store every event. Wire-supplied aggregates would also couple every window/
  threshold change to an upstream deploy, and the audit trail could no longer
  substantiate its own decisions ("4 events in 10min" would be hearsay). Raw single-
  transaction facts (mcc, device id) remain fine to add to the contract — with a rule
  that consumes them.
- **A named-feature precomputation step** (compute `count_10m`, `sum_1h`, … eagerly,
  rules reference features by name). The full production pattern, but an indirection
  layer with one consumer per feature is over-engineering at eight rules. The
  `CustomerHistory` facade is the same boundary at the right scale.

## Consequences

- One history query per evaluation regardless of rule count; rules are pure functions
  unit-tested with plain lists; the exclusion and anchoring conventions are centralized.
- The loaded window is the max across enabled rules (4h today, for impossible travel) —
  fine at per-customer volumes; revisit if a future rule wants days of history.
- Planned rules that need more than a window (`AMOUNT_DEVIATION` baselines,
  `DORMANT_ACCOUNT`'s "last event ever") will extend `CustomerHistory` with explicit
  accessors — the facade is the designated growth point.
- Disabled rules leave no `rule_result`: absence of a row means the rule wasn't active
  for that evaluation, which the `rule_version` column already disambiguates over time.
