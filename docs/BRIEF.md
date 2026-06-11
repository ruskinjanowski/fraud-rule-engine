# The Brief (definition of done)

Source: recruiter email + attached job description ("Capitec connect - Software Engineer III.pdf").
This document is the reference for scope and completion. Anything not in here is optional polish or an idea — see the backlog in [PLAN.md](PLAN.md).

## Recruiter email (requirements, near-verbatim)

> As part of the selection process, we would like you to complete one of the available project briefs. Please choose one project to work on and approach it as you would a real-world problem.
>
> Once completed, kindly share the GitHub link or links to your solution.
>
> In addition to the chosen project requirements, please ensure that your solution is:
>
> - **Production-grade** (reflecting the quality and standards you would apply in a real-world environment).
> - Includes a **runnable Dockerfile** to build and run the project.
> - Accompanied by a **README** that explains how to **build, run, and test** the project.
>
> You are encouraged to use any tools, frameworks, or resources you typically rely on.
>
> After we review your submission, successful candidates will be invited to a **2-hour interview** to discuss your solution and approach. The interview will also include questions related to **design and architecture and code**.
>
> Please treat this as **the only submission and evidence we will use to evaluate your skill level**. If you are applying for a job specifically for Java, or .Net, or any other language, make the submission as close as possible to what you are actually applying for. This is an opportunity to flex your skills and experience so don't waste the opportunity.
>
> **Anticipated Due Date: 25 June 2026**

## Chosen project (#2 of 3)

> **Fraud Rule Engine Service**
>
> Create a system that processes categorized transaction events and flags potential fraud. Apply a set of fraud rules per transaction based on different criteria and then store them in a data store. Allow the retrieval of this data via an API.

Functional requirements extracted from the brief:

1. Process **categorized transaction events**
2. Apply **a set of fraud rules per transaction** based on different criteria
3. **Flag potential fraud** and **store** results in a data store
4. Allow **retrieval of this data via an API**

The other two briefs (not chosen): Transaction Aggregation API; Secure File Statement Delivery.

## Job description extract (stack & role signals)

Role: Software Engineer: Back-End (10083), Capitec Connect — senior back-end engineer, cloud-native systems at national scale, owning the full SDLC including operating systems in production.

Core tech stack listed in the JD:

- Java
- Spring Boot
- AWS
- Kafka
- PostgreSQL

Other JD signals worth reflecting in the submission: RESTful API design, SQL database design and query optimisation, microservices and **event-driven architectures**, containerisation (Kubernetes mentioned), design patterns and testing practices, AWS Well-Architected principles.
