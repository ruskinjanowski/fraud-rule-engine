# Fraud Rule Engine — Capitec Interview Submission

## What this is

A job-application project for Capitec Connect (Software Engineer: Back-End, "Software Engineer III"). Per the recruiter: this submission is treated as **the only evidence used to evaluate skill level**, followed by a 2-hour interview covering the solution, design/architecture, and code. Anticipated due date: **25 June 2026**.

The chosen brief (#2 of 3, verbatim):

> **Fraud Rule Engine Service** — Create a system that processes categorized transaction events and flags potential fraud. Apply a set of fraud rules per transaction based on different criteria and then store them in a data store. Allow the retrieval of this data via an API.

Full brief text and job-description extract: [docs/BRIEF.md](docs/BRIEF.md). That file is the definition of done — validate against it, not against ideas.



## Working agreement

- Agile, iterative
- New features go through the **plan-feature** skill: validate against the brief first, design, then build.
- Don't invent requirements

## Commands

Maven Wrapper is committed (`./mvnw`); no host Maven needed. Host build/test require JDK 25; Docker is the only requirement to run the stack.

- Run full stack: `docker compose up --build` (app + Postgres + Kafka; app waits for both healthy)
- Infra only (run app from IDE): `docker compose up postgres kafka`
- Run app on host: `./mvnw spring-boot:run`
- Build jar: `./mvnw clean package`
- Build image: `docker build -t fraud-rule-engine .`
- Test (needs Docker — Testcontainers): `./mvnw test`
- Health check: `curl http://localhost:8080/actuator/health`
- Tear down: `docker compose down -v`

## Map

- [docs/BRIEF.md](docs/BRIEF.md) — full brief, submission requirements, JD extract (definition of done)
- [docs/PLAN.md](docs/PLAN.md) — current working plan + feature/idea backlog (provisional, revisited each iteration)
- `docs/adr/` — accepted decisions only
