---
name: evaluate-against-brief
description: Audit the project against the original Capitec brief and submission requirements. Use before declaring a milestone or the project done, when scope feels uncertain, or when deciding whether an idea is worth building. Reports gaps, scope creep, and a verdict.
---

# Evaluate Against Brief

The brief is the definition of done, not the plan or the ideas backlog. This skill produces an honest gap report.

## Steps

1. Read `docs/BRIEF.md` in full. Do not work from memory of it.
2. Audit the **functional requirements** against the actual code (read the code / run the app — don't trust docs or commit messages):
   - Does the system process categorized transaction events end-to-end?
   - Are multiple fraud rules with different criteria applied per transaction?
   - Are fraud flags/results stored in a data store?
   - Can the stored data be retrieved via an API?
3. Audit the **submission requirements**:
   - Production-grade: tests exist and pass (run them), errors are handled, input is validated, no secrets in the repo, no obvious half-finished code paths.
   - Dockerfile: actually build and run it (`docker build` + run, or docker-compose). "It should work" doesn't count.
   - README: a newcomer can build, run, and test from it alone. Check the commands in it actually work.
4. Audit **stack alignment** with the JD extract (Java, Spring Boot, PostgreSQL, Kafka/event-driven where adopted).
5. Check for **scope creep**: anything substantial in the code that isn't in the brief and isn't an `accepted`/`done` item in the `docs/PLAN.md` backlog. Differentiators are welcome only if the core is solid.
6. Cross-check `docs/PLAN.md` (sequence + backlog) statuses against reality; flag drift (done things marked proposed, claimed things not actually done).

## Output format

- **Verdict**: submission-ready / core complete but unpolished / core incomplete
- **Gaps**: each unmet requirement, with what's missing and where
- **Risks/creep**: anything endangering the core or unexplained by brief+ideas
- **Recommended next actions**, ordered by impact on the verdict

Be blunt. An optimistic audit is worthless — the reviewers won't be optimistic.
