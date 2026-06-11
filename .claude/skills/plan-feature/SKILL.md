---
name: plan-feature
description: Plan a feature or iteration step before building it. Use when picking up an item from docs/PLAN.md, or when the user proposes new functionality. Validates against the brief, records architectural decisions as ADRs, then builds.
---

# Plan Feature

A lightweight gate before building: **validate against the brief → decide → build.** The point is to not invent requirements and to record real architectural forks — not to run a checklist.

## Loop

1. **Validate against the brief.** Does this serve the brief's core ([docs/BRIEF.md](../../../docs/BRIEF.md)), or is it a differentiator? If the core isn't done and this is a differentiator, say so and recommend deferring — don't silently proceed. (For a full audit, use **evaluate-against-brief**.)
2. **Design at the right altitude.** What changes (API, schema, components), what's out of scope, how it's tested, what "done" means for this slice. Surface trade-offs only where a real choice exists; recommend one. Get agreement before implementing unless already approved.
3. **Record an ADR only for a genuine design fork** — one with alternatives worth recording (schema shape, processing model, an external dependency, a lifecycle/semantic rule). Follow the format of an existing ADR (Context / Decision / Alternatives considered / Consequences), next number — gaps are fine. Standard hardening and small implementation choices don't get an ADR; if a later decision reverses an old one, reconcile it in place with a dated Update note rather than leaving it stale.
4. **Build** a vertical slice that leaves the project runnable, with tests for the new behaviour.
5. **Keep docs honest** — only when something actually changed: adjust `docs/PLAN.md` (sequence + backlog). Don't tick boxes for the sake of it; stale statuses are worse than none.

## Anti-goals

- Don't invent requirements or constraints that aren't in the brief or an ADR.
- Don't batch-accept multiple ideas because they're "obviously coming anyway" — one slice at a time.
