# Scaling & Operations

How the engine behaves under load, where the bottleneck is, and the lever that moves it —
backed by a reproducible local load test ([`load/`](../load)). This is a back-of-the-envelope
characterisation on a laptop, not a capacity statement; see [Caveats](#caveats).

## The pipeline and its bottleneck

Ingestion is Kafka-only and **synchronous on the consumer thread**: for each event the
service runs one DB transaction — idempotency check on `eventId`, persist the event, one
indexed history query, run the nine rules, persist the evaluation
([architecture-rule-engine.md](architecture-rule-engine.md)).

Out of the box the `transactions` topic has **one partition** and the listener runs **one
thread**, so events are processed strictly serially. That makes the single consumer the
throughput ceiling — the first thing to address under load.

## The lever: partitions × consumer concurrency

Kafka parallelism is bounded by partitions: a partition is consumed by at most one thread in
a group, so effective parallelism is `min(listener.concurrency, partitions)`. Two knobs, both
now config-driven (default 1, so dev behaviour is unchanged):

| Knob | Env var | What it does |
|---|---|---|
| Topic partitions | `FRAUD_KAFKA_PARTITIONS` | Raises the concurrency ceiling (applied at topic creation) |
| Listener threads | `SPRING_KAFKA_LISTENER_CONCURRENCY` | Consumer threads in the group |

**Keying by `customerId` is what makes this safe.** The stateful rules (velocity, cumulative
amount, impossible-travel, card-testing, duplicate, and the anomaly baseline) all reason about
one customer's recent history. Kafka guarantees ordering *within* a partition, so as long as a
customer's events all hash to the same partition, they are still processed in order by a single
thread even while different customers run in parallel. Producers therefore key on `customerId`;
fan-out gives throughput without breaking per-customer ordering. (Idempotency on `eventId`
separately absorbs at-least-once redelivery.)

## Test data and why

The generator ([`load/GenerateEvents.java`](../load/GenerateEvents.java)) defaults to **50,000
events across 2,000 customers, with `occurredAt` spread over 7 days**, keyed by `customerId`.
Those numbers are a deliberate balance:

- **2,000 distinct keys ≫ partitions**, so events spread evenly across partitions and no
  consumer thread starves. With only a handful of customers the load would clump onto a few
  partitions and the extra threads would idle.
- **~25 events per customer** over the window means each evaluation's history query returns
  real rows — so we exercise the actual stateful workload (and the anomaly baseline) rather
  than hitting an empty-history shortcut that would flatter the numbers.
- **A 7-day spread** matches the longest rule lookback (the anomaly baseline window), so
  histories are realistically sized rather than every event landing inside every short window.

`customerId` is the bank's customer (the account holder), not the merchant — which is why it
is the right partition key and the right anchor for per-customer history. In production this is
very high cardinality (millions of customers), so partition distribution is even and there is
no hot-key risk barring a single pathologically busy account.

## Results

Machine: Apple M3 Pro (12 cores), 18 GB, Docker 29.5.3 — Postgres, Kafka, and the app all
running together on the one host. Each ingestion run is 50,000 events from a clean stack; reads
are the k6 mix held at 25 VUs for 70 s.

### Ingestion throughput

| Configuration | Partitions × concurrency | Throughput | Drain time | Per-event latency (mean / p95 / p99) |
|---|---|---|---|---|
| Baseline | 1 × 1 | **911 events/s** | 54.9 s | 0.68 / 1.00 / 2.10 ms |
| Scaled   | 6 × 6 | **2,443 events/s** | 20.5 s | 1.37 / 2.45 / 4.19 ms |

Going from 1→6 partitions and consumer threads gave a **~2.7× throughput increase** (911 →
2,443 events/s). Per-event evaluate-and-store latency stayed low throughout — sub-millisecond
mean, p99 ≤4.2 ms — rising only modestly under the 6× contention (p99 2.1 → 4.2 ms): the extra
threads compete a little for the DB and CPU, but no single evaluation gets slow. (Across runs
the 6×6 ceiling was stable near 2,440 events/s while the single-thread baseline varied with
ambient laptop load — see [Caveats](#caveats).)

### Read API (`GET /api/evaluations`, k6)

| Metric | Value |
|---|---|
| Requests | 13,689 over 70 s, **0 errors** |
| Latency p50 / p95 / p99 | 1.5 / 17.1 / 21.9 ms |
| Requests/s | ~195 — note this is **client-throttled** by the script's 0.1 s think-time per VU, not a server ceiling |

Indexed by-customer lookups sit at the p50 (~1.5 ms); the tail (p95 ~17 ms) is the heavier
filtered/paginated queries (flagged-with-pagination, time-range) over the 50k-row table.

## Interpretation & where to go next

The ~2.7× from a 6× concurrency bump is the headline — and the fact that it's **sub-linear** is
the more interesting half. Per-event latency stayed low (p99 ≤4.2 ms), so the consumer is no
longer the sole bottleneck; the gap between 2.7× and 6× is contention on the **shared
resources** — a single Postgres instance and 12 cores split three ways between the app, Kafka,
and the database on one laptop. That the 6×6 throughput holds steady near 2,440 events/s across
runs (while the single-thread baseline drifts with ambient load) is the tell: the scaled path is
resource-bound, not consumer-bound. In other words, partitioning did its job and handed the
bottleneck to the write path.

When partitioning stops paying off, the next constraints — in the order they'd bite — are:

1. **Database write path.** Every evaluation is two inserts plus the history read. Batching
   inserts, a connection pool sized to the consumer concurrency, and partitioning
   `transaction_event` by time would push this further.
2. **History query cost.** Already one indexed query per evaluation (ADR-0003); under heavy
   load a per-customer rolling aggregate (maintained incrementally) avoids re-scanning.
3. **Synchronous evaluation.** Fine while rules are pure CPU. A rule that did real I/O (e.g. a
   proposed LLM advisory rule) would have to move off the consumer thread — run it shadow/async
   so it never adds latency to the ingestion path (noted in ADR-0002's consequences).
4. **Read scaling** is independent of the write path: the retrieval API is read-only and would
   scale out behind read replicas.

This is also why the service is **detection, out-of-band** rather than inline pre-auth
blocking ([architecture-system-context.md](architecture-system-context.md)): it can absorb
bursts via Kafka backpressure without ever being in a transaction's critical path.

## Bank-scale context

To sanity-check whether this shape is in the right ballpark, it helps to have a rough target to
hold it against. The figures below are **illustrative estimates**, not Capitec's actual numbers
(those are in their published reports) — and this is a take-home artifact, not the production
system. The point is only to show the design was reasoned about against a realistic scale, and
that it extends to meet it.

A large SA retail bank's *financial* transaction flow (card auths, EFTs, debit orders — what a
fraud engine scores, distinct from broader "digital interactions") plausibly lands in the
**10–30 billion/year** band. Converting that to a rate, with a peak factor for the spiky SA
salary cycle (month-end) and events like Black Friday:

| Annual volume | Per day | Per minute (avg) | Per second (avg) | ≈ Peak (~6× avg) |
|---|---|---|---|---|
| 1 B/yr  | 2.7 M | ~1,900   | ~32 tps  | ~190 tps   |
| 10 B/yr | 27 M  | ~19,000  | ~317 tps | ~1,900 tps |
| 30 B/yr | 82 M  | ~156,000 | ~950 tps | ~5,700 tps |

Against that, the **2,400 events/s** measured on a single laptop node already sits at or above
the *average* rate and around the *peak* of a ~10 B/year flow. Two things carry it the rest of
the way to bank scale:

- **Horizontal scale-out.** The consumer is a stateless member of a Kafka consumer group, so
  capacity grows by adding partitions and consumer *instances* — not by making one node faster.
  A handful of properly-resourced instances (rather than three services sharing one laptop's 12
  cores) clears multi-thousand-tps peaks with headroom and HA.
- **Out-of-band processing.** Because the engine scores *after* authorization, it is never in
  the payment's critical path. A month-end spike can be absorbed as Kafka lag and drained within
  a detection-latency SLA (seconds-to-minutes for *detection*, vs. sub-second for inline
  *blocking*). The requirement is "keep up with sustained average + drain bursts within SLA,"
  not "serve instantaneous peak synchronously."

At that scale the binding constraint shifts away from raw consumer throughput. ~20–30 M
evaluations/day is **~7–10 billion rows/year** in the data store (an evaluation plus its
per-rule results), so the harder problems become **storage** (time-partitioned tables,
retention/archival) and **detection latency under burst** — which is exactly where the
"where to go next" list above points (the DB write path first).

## Caveats

Single laptop with Postgres, Kafka, and the app co-resident and contending for the same cores,
single-node infra, no JVM warmup beyond the run itself. The numbers describe the **shape** of
the system — where the bottleneck sits and how it moves when you add partitions — not absolute
production capacity. They are meant to be reproduced and reasoned about, not quoted as a SLA.
Run-to-run variance is real: the single-thread baseline ranged ~660–910 events/s across runs as
background load shifted, while the 6×6 ceiling stayed within a few percent of 2,440 — so read
the speed-up as "roughly 3×," not a precise multiple.

## Reproduce

See [`load/README.md`](../load/README.md) for the exact commands (baseline vs. scaled
ingestion, and the k6 read load).
