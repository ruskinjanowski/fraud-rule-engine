# ADR-0006: ML anomaly scorer (Isolation Forest) as an advisory shadow rule

- **Status:** accepted
- **Date:** 2026-06-16

## Context

The eight deterministic rules (ADR-0003) each encode a *known* fraud pattern as a fixed
threshold — amount over R10 000, four events in ten minutes, a country change faster than a
flight. They are precise, explainable, and auditable, but they only catch what someone
thought to write down. Fraud research consistently pairs such rules with a **machine-learning
layer** that learns the shape of normal behaviour and flags statistical outliers — unusual
*combinations* no single threshold describes (e.g. an amount that is normal in absolute terms
but abnormal *for this customer*).

This is a differentiator, not a brief requirement: the brief's core (ingest → apply rules →
persist → retrieve) is complete. Three forces shaped the design:

- **No labelled data.** We have no tagged fraud examples, so supervised models (XGBoost,
  logistic regression) are out — they would need labels we would have to invent, making any
  evaluation circular. This points to **unsupervised anomaly detection**.
- **The decision path must stay trustworthy.** A model trained on synthetic data must never
  be allowed to block a real customer. Whatever we add has to be observable *without* being
  in the blocking path.
- **It must run inside this JVM service**, in the existing Docker image, with no Python
  toolchain, no native BLAS/LAPACK, and no model-serving sidecar.

## Decision

**An Isolation Forest, fitted at startup, wired in as an `advisory` (shadow) rule.**

- **Algorithm & library — Isolation Forest via Smile 6.2.0** (`smile.anomaly`). Isolation
  Forest is the canonical real-time unsupervised detector for transaction fraud: tree-based,
  linear-time scoring, no feature scaling. Smile's implementation is **pure-JVM** and
  operates on `double[][]` — verified to pull **no** BLAS/LAPACK/MKL. `smile-base` drags in
  `duckdb_jdbc` + `commons-csv` for DataFrame file I/O we never touch; both are **excluded**
  in the POM to keep the runtime image lean.
- **Shadow seam — a new `Rule.advisory()` default.** The `Rule` SPI gains
  `default boolean advisory() { return false; }`. The `RuleEngine` records an advisory rule's
  `rule_result` (its score, hit flag, and detail — full audit trail) but **excludes its score
  from the evaluation total**, so it can never change `flagged`. This is the minimal shadow
  mechanism: the model is observed in production (how often would it fire? what does it
  overlap with?) before it is ever trusted to affect a customer. Promoting it later is a
  one-line change (`advisory()` → `false`), not a redesign.
- **Feature vector — intrinsic + contextual** (`AnomalyFeatureExtractor`, five features):
  `log1p(amount)`; a cyclic time-of-day encoding (`sin`, `cos`, so 23:59 and 00:01 are
  neighbours); the recent-transaction count in a short window; and **amount ÷ the customer's
  own baseline mean**. The last is the point of the model — it catches an amount that is
  unremarkable globally but abnormal for *this* customer, which no fixed threshold can. The
  contextual features reuse the `CustomerHistory` (ADR-0003) the stateful rules already load.
- **Training — synthetic, at startup, deterministic.** `SyntheticTrainingData` generates a
  stream of *normal* per-customer behaviour and fits the forest on `ApplicationReadyEvent`
  (~250 ms for 8 000 rows). The generator draws from its **own** per-customer distributions
  with **no reference to any rule threshold** — if the synthetic data were shaped by the same
  limits the rules use, the model would merely relearn the rules and any comparison would be
  circular. A fixed RNG seed makes the model byte-identical on every boot, so there is no
  model artefact to ship or version.
- **No schema change.** The advisory result rides the existing `rule_result` table; features
  derive from existing event fields plus history. No new `TransactionEvent` columns, no
  migration.

### Licence note

Smile is **GPL-3.0** (dual-licensed; a commercial licence is sold separately). This is
acceptable **for this evaluation artifact** — a personal submission, not distributed
commercial software, so the copyleft obligations are not triggered. In a real Capitec
deployment this dependency would require either a commercial Smile licence or a permissively
licensed replacement (e.g. a self-contained Isolation Forest implementation, or Tribuo's
Apache-2.0 one-class SVM). Recording the trade-off here is the point: it is a conscious,
documented decision, not an oversight.

## Alternatives considered

- **Supervised model (XGBoost / logistic regression).** The production workhorses, but they
  need labelled fraud. With only synthetic labels the model would learn our own rules and
  evaluation would be circular. Rejected for lack of data, not capability.
- **Smile with a self-implemented Isolation Forest.** A from-scratch forest (~150 lines)
  would dodge the GPL question entirely and show the algorithm directly. Rejected for this
  iteration: a library with tests behind it is lower correctness risk than hand-rolled ML,
  and the interesting design work here is the *shadow seam and feature engineering*, not
  re-deriving the paper. Noted as the clean path to remove the GPL dependency later.
- **Train offline in Python → export to ONNX → run via ONNX Runtime.** Gets a best-in-class
  model, but adds a Python toolchain, a binary model artefact to version, and a native
  runtime — a lot of surface for a back-end Java service. Rejected as disproportionate.
- **A full rule lifecycle (`ACTIVE` / `SHADOW` / `DISABLED`).** Tempting, but only the
  shadow distinction is needed today. A single `advisory()` flag is the minimal seam;
  `enabled()` (ADR-0003) already covers disabling. The richer lifecycle stays deferred.
- **Make the anomaly score affect the flag (a weighted rule).** Rejected on principle: a
  model trained on synthetic data has not earned the authority to block a customer. Advisory
  first; promotion is a deliberate, measured step.

## Consequences

- The engine now distinguishes **scoring** rules from **advisory** ones; `flagged` is the sum
  of scoring rules only. Every evaluation carries the ML verdict in its audit trail, so we can
  measure the shadow rule's hit rate and overlap with the deterministic rules from stored data
  alone — the payoff of shadow mode. The per-rule hit metric (`fraud.rule.hits{rule=…}`,
  ADR-0002's instrumentation) counts `ANOMALY_SCORE` for free.
- **`requiredLookback` grows to the baseline window (7d).** ADR-0003 explicitly anticipated
  this ("revisit if a future rule wants days of history"): the engine loads one history per
  evaluation covering the max window across rules, so every evaluation now loads up to seven
  days of the customer's events. Fine at per-customer volumes and served by the existing
  `(customer_id, occurred_at)` index; revisit if lookback grows further.
- **The model is a demonstration, not a validated detector.** Trained on synthetic data, its
  accuracy is unmeasured against real fraud — which is *exactly* why it is advisory. The value
  delivered is the architecture (pluggable scorer, shadow isolation, persisted score for
  hindsight), into which a model trained on real data drops without further change.
- A new third-party dependency (Smile) with a GPL-3.0 licence enters the build — consciously,
  with the exit path documented above.
- Startup does ~250 ms of training work, unconditionally (even when the rule is disabled via
  config). Acceptable for this service; the trade was simplicity over a conditional-training
  branch.
