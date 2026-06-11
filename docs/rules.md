# Rule Catalog — Event Data & Rule Logic

What data the engine assumes arrives in a transaction event, the set of rules applied to
it, and why each rule realistically catches fraud. Grounded in how production fraud
engines work (sources at the bottom); statuses match [PLAN.md](PLAN.md) — planned rules
still go through the plan-feature loop (and an ADR where a real decision arises) when
picked up.

## 1. The event contract — what we assume arrives

The brief says the engine "processes categorized transaction events": an upstream system
(core banking, payments engine, card switch) has already authorized/posted the transaction
and **categorized** it, then publishes it for downstream consumers like this engine. It
arrives as the value of a Kafka message on the `transactions` topic — the sole ingestion
path; the API is retrieval-only.

```json
{
  "eventId": "9f6a2d0e-3b1c-4e0a-9d2f-7c5b8a1e4f60",
  "transactionId": "TXN-2026-000042317",
  "customerId": "CUST-184223",
  "amount": 12500.00,
  "currency": "ZAR",
  "category": "ONLINE",
  "merchant": "Takealot",
  "country": "ZA",
  "occurredAt": "2026-06-11T20:14:05Z"
}
```

| Field | Type / constraint | Why the engine needs it |
|---|---|---|
| `eventId` | UUID, unique, producer-supplied | Identity of the *event* — the idempotency key that absorbs Kafka at-least-once redelivery |
| `transactionId` | string ≤ 64 | Identity of the underlying *transaction*; distinguishes a redelivered event from a genuinely duplicated charge |
| `customerId` | string ≤ 64 | The pivot for every behavioral rule — velocity, duplicates, and travel are all per-customer questions |
| `amount` | decimal > 0 | Magnitude signal (threshold rule) and duplicate matching |
| `currency` | ISO 4217 (3 letters) | Amounts are meaningless without it; ZAR-centric for now |
| `category` | enum: `POS`, `ATM`, `ONLINE`, `TRANSFER`, `DEBIT_ORDER`, `OTHER` | The upstream categorization the brief names; rules can differ per channel (e.g. an ATM withdrawal at 02:00 is more suspicious than a debit order) |
| `merchant` | string ≤ 140, optional | Duplicate matching; readable audit detail |
| `country` | ISO 3166-1 alpha-2, optional | Coarse geolocation for the impossible-travel rule |
| `occurredAt` | ISO-8601 instant (UTC) | When it happened (vs `receivedAt`, which the engine stamps) — time-of-day and velocity rules key off this |

### What real engines receive that we deliberately don't

Production card-fraud systems see far richer data — terminal IDs, merchant category codes
(MCC), full merchant address, authentication type ([fraud.net](https://www.fraud.net/glossary/credit-card-fraud-detection)),
plus hundreds of device/network signals: device fingerprint, IP intelligence, VPN/Tor
detection ([SEON](https://seon.io/resources/credit-card-fraud-detection/)). Our contract is
the realistic *core* of that — the fields every flavour of transaction shares and that the
chosen rule set actually consumes. Candidate extensions, to be added **only when a rule
needs them** (don't invent requirements):

- `channelMetadata` (device ID / IP) — would enable device-change and IP-velocity rules
- `mcc` — would enable high-risk-merchant-category rules
- `latitude`/`longitude` or city — would sharpen impossible-travel beyond country level

## 2. The scoring model

Every rule returns a `RuleOutcome` — `hit?`, `score`, human-readable `detail` — and the
engine records one `RuleResult` per rule *whether it fired or not* (the audit trail). Scores
are summed and:

```
flagged = totalScore >= flagThreshold        (default flagThreshold: 50)
```

The additive model is deliberate: **one strong signal alone, or several weaker corroborating
signals together, crosses the line.** A single odd-hours grocery purchase is unremarkable;
odd-hours *plus* a velocity burst is the classic stolen-credential pattern. Real engines
work the same way — cumulative scoring across layered signals, with thresholds deciding the
action ([Signzy](https://www.signzy.com/general-glossary/rule-engine),
[Vespia](https://vespia.io/blog/fraud-detection-rules)). All scores and windows are
env-overridable config (`fraud.rules.*`), and every rule has an `enabled` flag (a disabled
rule is skipped and leaves no `RuleResult`), so tuning — including switching a rule off —
is operations, not a deploy.

One rule is **advisory** (shadow mode): `ANOMALY_SCORE` (§3, [ADR-0006](adr/0006-ml-anomaly-scorer-in-shadow-mode.md)).
Its `RuleResult` is recorded for the audit trail, but its score is **excluded from the
total**, so it is observed without ever changing `flagged`. The rest are scoring rules.

## 3. The rule set

| Code | Criteria (one line) | Score* | Status |
|---|---|---|---|
| `AMOUNT_THRESHOLD` | amount > limit (default R10 000) | 50 | **implemented** |
| `VELOCITY` | > 3 events for the customer within 10 min | 30 | **implemented** |
| `CUMULATIVE_AMOUNT` | summed amounts > R50 000 within 1 h | 35 | **implemented** |
| `CARD_TESTING` | ≥ 5 micro-amounts (≤ R50) within 10 min | 45 | **implemented** |
| `DUPLICATE_TRANSACTION` | same customer+amount+merchant, different transactionId, within 5 min | 40 | **implemented** |
| `ODD_HOURS` | occurredAt falls in the night window 00:00–04:30 SAST | 20 | **implemented** |
| `IMPOSSIBLE_TRAVEL` | card-present country change < 4 h after the previous located event | 40 | **implemented** |
| `HIGH_RISK_COUNTRY` | country on a configured risk list (ships empty) | 15 | **implemented** |
| `ANOMALY_SCORE` | Isolation Forest anomaly score > threshold — **advisory / shadow** | 25‡ | **implemented** |
| `AMOUNT_DEVIATION` | amount ≫ the customer's own trailing baseline | 30 | planned |
| `DORMANT_ACCOUNT` | first event after ≥ N days of inactivity | 25 | planned |
| `HIGH_RISK_MCC` | merchant category on a risk list — needs an `mcc` field | 15 | proposed (contract widening) |
| `NEW_DEVICE` | device never seen for this customer — needs a `deviceId` field | 25 | proposed (contract widening) |

\* Implemented scores are the configured defaults; planned scores are proposed starting
points to be settled when each rule lands.
‡ `ANOMALY_SCORE` is **advisory**: its score is recorded but excluded from the flag total
(shadow mode, [ADR-0006](adr/0006-ml-anomaly-scorer-in-shadow-mode.md)).

### AMOUNT_THRESHOLD — implemented

Fires when `amount` exceeds a configured limit. The simplest and oldest fraud control:
fraudsters monetizing a compromised account try to extract maximum value before the card is
killed, so unusually large transactions are disproportionately fraudulent. Production
systems use per-customer baselines ("large *for this customer*"); a fixed limit is the
honest static approximation. Known limitation (recorded in the rule): currency-naive.

### VELOCITY — implemented

Fires when the customer's event count in a sliding window — the evaluated event included —
exceeds a limit (default: more than 3 in 10 minutes). The canonical fraud rule ("if X
occurs within Y timeframe, then do Z" — [Chargebacks911](https://chargebacks911.com/velocity-checks/)).
It catches:

- **Card testing**: fraudsters validate stolen card details with rapid bursts of small
  payments before a big spend ([Checkout.com](https://www.checkout.com/blog/card-testing-fraud)) —
  frequency is the signal, not amount, which is exactly what the threshold rule misses.
- **Account takeover drain**: once in, attackers move money out fast, producing a burst no
  human shopping pattern resembles.

Counts events with `occurredAt` in `[occurredAt − window, occurredAt]` via the shared
`CustomerHistory` (§4).

### CUMULATIVE_AMOUNT — implemented

Fires when the customer's **summed** amounts in a sliding window — the evaluated event
included — exceed a limit (default: R50 000 in an hour). The value-based twin of
`VELOCITY`: production velocity limits come in count *and* value flavours
([NORBr](https://norbr.com/library/paydecoding/velocity-limits-for-dummies/)). It catches
the account-takeover drain split into transactions that each stay under the
`AMOUNT_THRESHOLD` limit — eight R7 000 transfers in an hour pass every per-transaction
check and still empty an account.

### CARD_TESTING — implemented

Fires when the evaluated event is itself a micro-amount (default ≤ R50) and the customer's
micro-amount count in the window reaches a minimum (default 5 in 10 minutes). The
most-cited automated-fraud pattern: bots validate stolen card details with clusters of
near-identical tiny charges before a real spend
([Chargeflow](https://www.chargeflow.io/chargebacks-101/card-testing),
[Checkout.com](https://www.checkout.com/blog/card-testing-fraud)). A specialised,
higher-confidence variant of `VELOCITY`: a generic burst might be a shopping spree; a
burst of sub-R50 charges is almost never one — hence the higher score (45).

### ODD_HOURS — implemented

Fires when `occurredAt`, converted to a configured zone (`Africa/Johannesburg`), falls
inside a night window (default 00:00–04:30, start-inclusive/end-exclusive, may wrap past
midnight). Stolen credentials are heavily used while the
victim sleeps and can't see SMS notifications or answer a fraud call; "a customer who
typically transacts during business hours suddenly conducting a transaction in the middle
of the night" is a standard trigger ([SEON](https://seon.io/resources/credit-card-fraud-detection/)).
Deliberately low-scoring: plenty of legitimate night activity exists, so it corroborates
rather than convicts — its job is to push a borderline burst over the threshold.

### DUPLICATE_TRANSACTION — implemented

Fires when an event matches a recent stored event on `customerId` + `amount` + `merchant`
but carries a **different `transactionId`**, within a short window (default 5 minutes). The
`transactionId` distinction matters: a replayed *event* (same `eventId`) is absorbed
silently by idempotency before rules even run; this rule targets genuinely distinct
transactions that look like double-submission, replay attacks, or a fraudster re-running a
captured payment.

### IMPOSSIBLE_TRAVEL — implemented

Fires when a **card-present** event's (`POS`/`ATM`) `country` differs from the customer's
most recent located event and the gap between `occurredAt` timestamps is shorter than
plausible travel time (default < 4 hours for a country change). A card swiped in
Johannesburg cannot appear at a POS in London 15 minutes later — the physical card has
been cloned or the credentials stolen
([Ping Identity](https://www.pingidentity.com/en/resources/cybersecurity-fundamentals/detect-risk/impossible-travel-101.html)).
The card-present filter is deliberate (decided in ADR-0003): an `ONLINE` purchase from
abroad is unremarkable. Country-level granularity stays coarse — no distance matrix, no
flight times, a single minimum-gap config.

### HIGH_RISK_COUNTRY — implemented

Fires when the event's `country` is on a configured list of high-risk jurisdictions.
Geographic risk lists are a standard transaction-monitoring control
([IBM](https://www.ibm.com/think/topics/transaction-monitoring)); the obvious source is
the FATF high-risk list. Deliberately low-scoring (15) — geography alone convicts nobody —
and the list **ships empty**: which jurisdictions a bank treats as high-risk is a
compliance decision, not a code default. Populate via
`FRAUD_RULES_HIGH_RISK_COUNTRY_COUNTRIES` (comma-separated ISO codes).

### ANOMALY_SCORE — implemented (advisory / shadow)

The one **machine-learning** rule, and the one **advisory** rule. An unsupervised
**Isolation Forest** (Smile) learns the shape of normal transactions and fires on
statistical outliers — unusual *combinations* no fixed threshold describes. It scores a
five-feature vector: `log1p(amount)`, a cyclic time-of-day encoding (`sin`/`cos`), the
recent-transaction count, and **amount ÷ the customer's own baseline mean** — the last
catching an amount that is unremarkable globally but abnormal *for this customer* (the
production refinement `AMOUNT_THRESHOLD` and the planned `AMOUNT_DEVIATION` both gesture at,
here learned rather than hand-tuned). Features reuse the shared `CustomerHistory` (§4).

It runs in **shadow mode** ([ADR-0006](adr/0006-ml-anomaly-scorer-in-shadow-mode.md)): the
`Rule.advisory()` seam means its `RuleResult` is persisted for the audit trail and counted in
the per-rule hit metric, but its score never affects `flagged`. This is how a model trained
on synthetic data is observed in production — its hit rate and overlap with the deterministic
rules measurable from stored evaluations — without being trusted to block a customer.
Promotion to a scoring rule is a one-line config change. The model is fitted at startup on
synthetic *normal* data generated independently of the rule thresholds (so it can genuinely
surprise the rules rather than relearn them); a fixed seed makes it reproducible. It is a
**demonstration of the architecture**, not a model validated against real fraud — which is
precisely why it is advisory.

### AMOUNT_DEVIATION — planned

Would fire when the amount is far above the customer's **own** trailing baseline (e.g.
> 5× their average over the lookback). This is the production refinement the
`AMOUNT_THRESHOLD` section already names: "large *for this customer*" instead of one limit
for everyone — R8 000 is normal for one account and wildly anomalous for another. Needs a
minimum history count to avoid convicting new customers, which is the design question to
settle when it lands; `CustomerHistory` (§4) is the designated extension point.

### DORMANT_ACCOUNT — planned

Would fire on the first event after a long idle period (e.g. ≥ 30 days), optionally
amount-qualified. A previously dormant account suddenly active is a classic takeover
signal ([IBM](https://www.ibm.com/think/topics/transaction-monitoring)) — stale accounts
have stale credentials and inattentive owners. Needs "last event ever", not a bounded
window, so it extends `CustomerHistory` beyond the windowed query (§4).

### HIGH_RISK_MCC / NEW_DEVICE — proposed (contract widening)

Both need a new event field (`mcc`; `channelMetadata.deviceId`) and follow the §1 rule:
field and consuming rule are added together, or not at all. `NEW_DEVICE` (a device id
never seen for this customer) is the classic account-takeover signal in device-aware
engines ([SEON](https://seon.io/resources/credit-card-fraud-detection/)); `HIGH_RISK_MCC`
is the merchant-side analogue of `HIGH_RISK_COUNTRY`.

## 4. Stateless vs. stateful rules — the design implication

`AMOUNT_THRESHOLD`, `ODD_HOURS`, and `HIGH_RISK_COUNTRY` are **stateless**: the event
alone answers them. `VELOCITY`, `CUMULATIVE_AMOUNT`, `CARD_TESTING`,
`DUPLICATE_TRANSACTION`, and `IMPOSSIBLE_TRAVEL` are **stateful**: they ask "what has
this customer done recently?", answered from the engine's own `transaction_event` history
(which exists precisely because every event is persisted on ingest).

How stateful rules get that history is [ADR-0003](adr/0003-customer-history-context-for-stateful-rules.md):
the service loads a **`CustomerHistory` context once per evaluation** — a single query on
`ix_transaction_event_customer_time` covering the largest `historyWindow()` any enabled
rule declares, anchored at the event's `occurredAt` and excluding the event itself — and
every rule evaluates as a pure function of `(event, history)`. This mirrors how production
platforms separate feature computation from rule evaluation (the engine stays stateless;
state lives beside it — [Nected](https://www.nected.ai/blog/rules-engine-design-pattern),
[Sardine](https://www.sardine.ai/blog/the-sardine-rules-engine-rules)), keeps it to one
history query per event instead of one per stateful rule, and lets rules unit-test with
plain lists. The deliberately rejected alternative — upstream sending pre-computed
aggregates in the event body — is documented in the ADR: the producer sends what only the
producer can know; the engine computes what only the engine can know.

## 5. Worked example — how signals combine

Stolen-card scenario: at 02:10 SAST a fraudster runs a fourth online payment in eight
minutes — R950 to an unfamiliar merchant.

| Rule | Hit? | Score | Detail |
|---|---|---|---|
| `AMOUNT_THRESHOLD` | no | 0 | amount 950 within limit 10000 |
| `VELOCITY` | **yes** | 30 | 4 events in 10min exceeds limit 3 |
| `CUMULATIVE_AMOUNT` | no | 0 | ~R3 700 in 60min within limit 50000 |
| `CARD_TESTING` | no | 0 | amount 950 above micro-amount 50 |
| `DUPLICATE_TRANSACTION` | no | 0 | no same-amount, same-merchant transaction within 5min |
| `ODD_HOURS` | **yes** | 20 | 02:10 in Africa/Johannesburg within night window 00:00–04:30 |
| `IMPOSSIBLE_TRAVEL` | no | 0 | category ONLINE is not card-present |
| `HIGH_RISK_COUNTRY` | no | 0 | country ZA not on the high-risk list |

Total **50 ≥ 50 → flagged**. No single rule fired strongly enough alone — the amount is
modest, night activity happens, bursts happen — but together they form the textbook
stolen-credential pattern. Conversely, one legitimate 02:00 purchase scores 20 and passes.
Every row above is persisted as a `RuleResult`, so a fraud analyst retrieving the
evaluation sees *why* it flagged — and why a non-flagged transaction didn't. This exact
scenario runs end-to-end in `FraudEvaluationFlowTest.corroboratingWeakSignalsFlagTogether`.

## 6. How this compares to production fraud engines

Worth being able to articulate in the interview:

- **Real engines are layered**: a deterministic rule tier (what we build) for explainable,
  instantly-tunable controls, *plus* ML models scoring subtler patterns, with rules and
  model outputs combined ([IBM](https://www.ibm.com/think/topics/fraud-detection),
  [Signzy](https://www.signzy.com/general-glossary/rule-engine)). The rule tier never goes
  away — regulators and analysts need decisions a human can read, which is exactly what
  per-rule `RuleResult` rows provide. `ANOMALY_SCORE` is the minimal version of that ML
  layer: an unsupervised model alongside the rules, sharing the same audit trail, kept
  advisory (ADR-0006) so the explainable rule tier still owns every decision.
- **They watch more dimensions**: device fingerprints, IP reputation, behavioral biometrics
  — hundreds of signals ([SEON](https://seon.io/resources/credit-card-fraud-detection/)).
  Our event contract is the transactional core of that; the richer signals are upstream
  enrichment this engine could consume without structural change (more fields, more rules).
- **They act inline in milliseconds** at authorization (e.g.
  [Mastercard's decisioning](https://www.mastercard.com/us/en/business/cybersecurity-fraud-prevention/risk-decisioning/transaction-fraud-monitoring.html)),
  approving/declining in the hot path. This engine is deliberately out-of-band
  detection-and-audit per the brief — see
  [architecture-system-context.md](architecture-system-context.md); the proposed
  tiers/shadow-mode backlog ideas are the bridge toward decisioning.
- **They trial rules before enforcing them**: changes go through observation/shadow mode
  and backtesting before they can affect customers
  ([Rapyd](https://www.rapyd.net/blog/fraud-rules-engine/),
  [Sardine](https://www.sardine.ai/blog/fraud-rules)) — realized here by the `Rule.advisory()`
  shadow seam (ADR-0006): `ANOMALY_SCORE` is evaluated and persisted in production but cannot
  affect a decision until deliberately promoted. The per-rule `enabled` flag was its minimal
  ancestor.
- **Rules we deliberately leave out** (candidates, not commitments): structuring/smurfing
  (an AML reporting-threshold control, not fraud per the brief), round-amount heuristics
  (too weak alone), IP/VPN intelligence (needs a vendor feed)
  ([SEON's rule catalog](https://seon.io/resources/fraud-rules-for-payments-companies/)).
  The former "leave out" list — card-testing bursts, dormant accounts, per-customer
  baselines, MCC lists — has graduated into the catalog above as implemented, planned, or
  proposed entries.

## Sources

- [SEON — Credit card fraud detection](https://seon.io/resources/credit-card-fraud-detection/) and [fraud rules for payments companies](https://seon.io/resources/fraud-rules-for-payments-companies/)
- [Chargebacks911 — Velocity checks](https://chargebacks911.com/velocity-checks/)
- [Checkout.com — Card testing fraud](https://www.checkout.com/blog/card-testing-fraud) and [velocity checks](https://www.checkout.com/blog/velocity-check)
- [NORBr — Velocity limits (count and value windows)](https://norbr.com/library/paydecoding/velocity-limits-for-dummies/)
- [Chargeflow — Card testing fraud](https://www.chargeflow.io/chargebacks-101/card-testing)
- [Ping Identity — Impossible travel](https://www.pingidentity.com/en/resources/cybersecurity-fundamentals/detect-risk/impossible-travel-101.html)
- [fraud.net — Credit card fraud detection (data fields)](https://www.fraud.net/glossary/credit-card-fraud-detection)
- [IBM — What is fraud detection?](https://www.ibm.com/think/topics/fraud-detection) and [transaction monitoring](https://www.ibm.com/think/topics/transaction-monitoring)
- [Signzy — Rule engines for AML/fraud](https://www.signzy.com/general-glossary/rule-engine)
- [Vespia — Fraud detection rules (cumulative scoring)](https://vespia.io/blog/fraud-detection-rules)
- [Rapyd — Fraud rules engine blueprint (observation mode, config-not-code tuning)](https://www.rapyd.net/blog/fraud-rules-engine/)
- [Nected — Rule engine design pattern (stateless engine, state beside it)](https://www.nected.ai/blog/rules-engine-design-pattern)
- [Sardine — Rules engine and feature computation](https://www.sardine.ai/blog/the-sardine-rules-engine-rules) and [releasing rules safely](https://www.sardine.ai/blog/fraud-rules)
- [Mastercard — Transaction fraud monitoring](https://www.mastercard.com/us/en/business/cybersecurity-fraud-prevention/risk-decisioning/transaction-fraud-monitoring.html)
