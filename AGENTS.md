# AGENTS.md — Builder Harness (entry point)

> **You are implementing a production-grade service for a USD purchase ledger with on-demand
> currency conversion via the U.S. Treasury *Reporting Rates of Exchange* API.**
> This file is the always-loaded root. Keep it in context; load the detail units **on demand**.
> Authoring convention: this is the canonical instruction file (open `AGENTS.md` standard).
> `CLAUDE.md` is a one-line pointer to it so Claude Code auto-discovery still works.

## What this system does (orientation)

Two operations over one domain (money) and one external dependency (Treasury):

- **R1 — Store a purchase:** `description` (≤50 chars), `transactionDate` (valid date), `amount`
  (positive USD, cent precision); assign a unique `id`.
- **R2 — Retrieve it converted** to a target currency using Treasury rates: return the id,
  description, date, original USD amount, the **exchange rate used**, and the **converted amount**
  (2 dp). The rate must be the one **active on/before the purchase date, within the prior 6 months**;
  if none exists, return an error that the purchase cannot be converted.

The happy path is trivial. The engineering signal is in **money handling, rate-selection
correctness, resilience to the external dependency, security, and the test strategy.**

## How to work here (the workflow + the gates)

This harness uses a **Spec-Driven** flow. Respect the phase order and the gates:

1. **Spec** → `docs/builder/spec.md` — *what* to build and the acceptance criteria. Read first.
2. **Plan** → `docs/builder/plan.md` + the detail units — *how*: architecture, schema, contract.
3. **Tasks** → `docs/builder/tasks.md` — the ordered, dependency-aware build sequence. Work top-down.
4. **Implement** → only after a slice's spec/plan/tasks are understood. **Validate at each gate**
   before moving on (tests green, contract honored) — do not run ahead of the spec.

**Where rationale lives.** This harness tells you *what to do*. The **why** — every decision, the
alternatives weighed, the trade-offs, and the evidence — lives in **`docs/DECISION_LOG.md`** (ADR
style, decisions `D-01`..`D-11`, verified API facts `F1`..`F9`). When a rule here cites `D-0x`/`F-x`,
that's where to read the reasoning. **Do not duplicate rationale into the builder docs** — link to it.

## Golden rules (non-negotiable — full treatment in `constitution.md`)

1. **Money is `BigDecimal`, never `float`/`double`.** Compute at full precision, round **once at the
   end**, `RoundingMode.HALF_UP`, scale 2. Compare with `compareTo`, never `equals`. *(D-04)*
2. **Never mutate the principal.** Reject amounts with >2 decimals (`400`); the *only* rounding is the
   derived conversion output. *(D-05)*
3. **Select rates by `effective_date`, not `record_date`** — `max(effective_date) ≤ purchaseDate`
   within a 6-calendar-month window. Intra-quarter amendments make this load-bearing. *(D-02, F4, F8)*
4. **Currency contract is ISO-4217 in, resolved through a curated, version-controlled map** to
   Treasury `country_currency_desc`. **`XOF ≠ XAF`** (same words "Cfa Franc", different rates). `USD`
   is an **in-app identity** (rate `1.00`, no upstream call). *(D-01, D-07, F5, F6, F9)*
5. **Reject future-dated purchases** (`400`); store too-old ones but fail conversion with
   `422 NO_RATE_AVAILABLE`. Parse dates **strictly**, ISO local date. *(D-06)*
6. **Identifiers are server-generated UUIDv7.** Stored as native `UUID`. *(D-08)*
7. **Purchases are append-only.** No `PUT`/`PATCH`/`DELETE`; corrections are new reversing records. *(D-09)*
8. **Errors are RFC 9457** (`application/problem+json`) with a machine `code` + `traceId`. `422` for
   well-formed-but-unfulfillable; `400` for malformed/validation. *(D-09)*
9. **Security:** TLS only; **no amounts or PII (description) in URLs or logs** — log ids/`traceId`
   only. Least-privilege DB users. No secrets in the repo. *(D-09, D-10)*
10. **Tests are deterministic:** injected `Clock`, **zero real network** in the gating suite
    (WireMock + Testcontainers), **no H2** (prod-parity Postgres everywhere). *(D-06, D-10, D-11)*

## Document map (load on demand)

| Unit | Read it when you are… | Anchors |
|---|---|---|
| `docs/builder/constitution.md` | …about to write any code (cross-cutting principles) | all |
| `docs/builder/spec.md` | …establishing *what* a slice must do (acceptance criteria) | R1, R2 |
| `docs/builder/plan.md` | …deciding architecture, packages, the port/adapter seam | D-03, D-10 |
| `docs/builder/data-model.md` | …writing Flyway migrations / persistence | D-08, D-10 |
| `docs/builder/api-contract.md` | …building controllers, DTOs, errors, OpenAPI | D-09 |
| `docs/builder/rate-selection.md` | …implementing the 6-month / effective-date selection | D-02, F4, F7, F8 |
| `docs/builder/currency-mapping.md` | …implementing ISO→descriptor resolution | D-01, F5, F6, F9 |
| `docs/builder/test-strategy.md` | …writing tests (pyramid, fixtures, gates) | D-11 |
| `docs/builder/tasks.md` | …choosing what to build next (ordered slices) | all |
| `docs/DECISION_LOG.md` | …you need the *why* behind any rule above | D/F refs |

## Target stack & DX (the builder must deliver this)

- **Java 21 LTS, Spring Boot 3.x**, virtual threads for blocking Treasury IO. *(D-10)*
- **PostgreSQL + Flyway (plain SQL)**, Spring Data JDBC. Testcontainers for tests & dev. *(D-10)*
- **One-command local dev** (`spring-boot-docker-compose` / `@ServiceConnection`); profiles
  `dev` / `test` / `prod`; `.env.example`; no secrets committed. *(D-10)*
- **OpenAPI 3.1** is the contract source of truth (materialize it as the first artifact). *(D-09)*

**Target commands (you implement these):**
```
make dev          # run locally, DB auto-started, Swagger UI up
make test         # fast unit + slice tests (no live network)
make integration  # Testcontainers + WireMock integration/E2E
make build        # production jar
make db-migrate   # apply Flyway migrations
```

## Out of scope (do not build)

List/search endpoints; `PUT`/`PATCH`/`DELETE`; multi-tenancy; auth implementation (assume an
upstream gateway, but leave the seam); webhooks/streams; non-functional/perf testing. If a slice
seems to need one of these, stop and flag it — it's a scope question, not a build task.

## Open questions pending the hiring manager

Seven clarifications (see `DECISION_LOG.md` §3) can shift `PROPOSED` decisions. Until answered,
**build to the documented defaults** but keep the seams that make the alternative cheap (e.g. the
`ExchangeRateProvider` port, the validation policy in one place). Flag, don't silently re-decide.
