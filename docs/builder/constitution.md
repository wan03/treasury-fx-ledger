# Constitution — engineering principles

> The non-negotiable, cross-cutting rules for **how** this system is built. Imperative on purpose.
> Each principle cites the decision that justifies it; the **reasoning, alternatives, and evidence
> live in `docs/DECISION_LOG.md`** — read it there, don't restate it here. If a detail unit
> (`plan.md`, `api-contract.md`, …) ever contradicts this file, this file wins; fix the unit.

This is the *constitution* in the Spec-Driven sense: it governs specification, planning, and
implementation alike. It is stable. Slice-specific detail belongs in the units, not here.

---

## 1. Money & arithmetic  *(D-04)*

- Represent all monetary values and rates as **`BigDecimal`**. **Never** `float`/`double` anywhere in
  the money path — not in fields, DTOs, JSON parsing, or test assertions.
- Wrap amounts in a small **`Money` value object** (amount + currency). It centralizes rounding,
  forbids cross-currency arithmetic, and documents intent. Do not pull in a heavy money library.
- **Round exactly once, at the very end.** Compute `usd.multiply(rate)` at full precision, then
  `setScale(2, RoundingMode.HALF_UP)`. Never pre-round the rate or any intermediate.
- Output money at **scale 2, always**. Compare with `compareTo`, never `equals` (scale-sensitive).
- Parse Treasury's `exchange_rate` from its **string** form into `BigDecimal`. **Never assume a
  scale** — observed precision varies (2–4 dp). *(F2)*
- Serialize money/rates as **JSON strings**, not numbers (no client-side float coercion). Configure
  Jackson `WRITE_BIGDECIMAL_AS_PLAIN` (no scientific notation).
- **Known simplification:** the brief mandates 2 dp for *all* targets — wrong for zero-decimal (JPY)
  and three-decimal (BHD) currencies. Follow the brief, but keep the minor-unit lookup a one-line
  change and note it. Do not silently "fix" the brief.

## 2. Correctness of rate selection  *(D-02, F4, F7, F8 — see `rate-selection.md`)*

- Select the rate by **`effective_date`**, never `record_date`. Pick `max(effective_date) ≤
  purchaseDate` within `[purchaseDate − 6 months, purchaseDate]`. The window is measured on
  `effective_date` too. Calendar-month arithmetic (not 180 days).
- This is the domain's crown jewel. It is a **pure function** over candidate rows — keep it free of
  HTTP, Spring, and the clock so it is exhaustively unit-testable.
- An empty result is a **normal business outcome** (`422 NO_RATE_AVAILABLE`), not an exception.

## 3. Input validation & the contract  *(D-05, D-06 — see `api-contract.md`)*

- **Validate at the edge, fail fast, be specific.** Every rejection returns a field-level RFC 9457
  error with a stable machine `code`.
- **Never mutate the principal.** Reject amounts with >2 decimals (`400 AMOUNT_PRECISION`); reject
  `≤ 0` (`AMOUNT_NOT_POSITIVE`); no separators, symbols, signs, or scientific notation; enforce a
  sane upper bound within `NUMERIC(19,2)`.
- Dates: ISO-8601 **local date**, **strict** parsing (`ResolverStyle.STRICT`). **Reject future
  dates** (`400 DATE_IN_FUTURE`) against an injected `Clock` in a configured zone (default UTC, small
  skew tolerance). Do **not** reject old dates at store time — they fail at conversion instead.
- `description`: 1–50 **Unicode code points** (define and document the counting rule); trim; reject
  control characters.
- Keep the validation policy in **one place** so the accept-vs-reject posture (HM Q3) flips cheaply.

## 4. API & error semantics  *(D-09 — see `api-contract.md`)*

- REST/JSON, URI-versioned `/v1`. Success `application/json`; errors `application/problem+json`
  (**RFC 9457**) carrying a machine `code`, human `detail`, and a `traceId`.
- HTTP status discipline: `200/201` success · `400` malformed/validation · `404` not found · **`422`
  well-formed-but-unfulfillable** (no rate in 6 months; ISO-valid but unsupported currency) · `409`
  idempotency conflict · `429` rate-limited · `502/503/504` Treasury upstream.
- **Purchases are append-only.** No `PUT`/`PATCH`/`DELETE`. Corrections are new reversing records.
- **OpenAPI 3.1 is the source of truth** for schemas; generate it, serve it, contract-test against it.

## 5. Security & privacy  *(D-09, D-10)*

- TLS/HSTS only. **No amounts or PII (the `description`) in URLs, query strings, or logs** — log
  identifiers and `traceId` only.
- Validate and bound every input (size limits, `currencyCode` regex). Deny-by-default CORS.
- Secrets come from env / secret-manager, **never the repo**; ship `.env.example` only.
- **Least-privilege DB users:** a `migration` user (DDL) distinct from the `app` user (DML only).
- AuthN/AuthZ is assumed at an upstream gateway (out of scope) — leave the seam, don't build it.
- Defense in depth: DB `CHECK` constraints **mirror** application validation.

## 6. Persistence & data integrity  *(D-08, D-10 — see `data-model.md`)*

- **PostgreSQL**, schema owned by **Flyway** (plain, versioned SQL — reviewers read the DDL). Same
  dialect everywhere; **no H2** (dialect drift hides `NUMERIC` bugs).
- Store amounts as `NUMERIC(19,2)`, ids as native `UUID`, timestamps as `TIMESTAMPTZ`.
- The **POST create is a single transaction** (purchase row + idempotency row commit atomically).
- Data access via **Spring Data JDBC** (explicit SQL, fast startup) — not JPA/Hibernate/JOOQ here.

## 7. Architecture & boundaries  *(D-03 — see `plan.md`)*

- **Hexagonal / ports-and-adapters.** The domain (money, rate selection, validation) must not depend
  on Spring, web, or persistence. Adapters depend **inward** only. Enforce with ArchUnit.
- Rate acquisition sits behind an **`ExchangeRateProvider` port** so the strategy is a one-adapter
  swap. **All four variants** (passthrough A0 / on-demand+cache A / ingest B / hybrid C) are built and
  config-selectable; **A is the default** (D-03). Build order A0→A→B→C; the system is shippable after A.
- Resilience around the Treasury HTTP adapter is mandatory: connect/read **timeouts**, **bounded
  retries** (5xx/timeout only — never on `4xx`), a **circuit breaker**, and **tolerant parsing**
  (ignore unknown fields; fail clearly on a missing critical field).

## 8. Testing philosophy  *(D-11 — see `test-strategy.md`)*

- A real **pyramid** (~70% unit / ~20% slice / ~10% E2E). Never test pure logic *through* the DB or a
  live API. Avoid the ice-cream-cone.
- **Determinism is mandatory:** injected `Clock`, **zero real network** in the gating suite (WireMock
  + Testcontainers). The live endpoint may only be touched by a **non-gating** `@Tag("live")` canary.
- Coverage (JaCoCo) is a **floor, not the goal**. Assertion strength is proven by **mutation testing
  (PIT)** on the `money` and `rate-selection` packages.
- Every requirement traces to tests (R↔test map). New behavior ships with the test that locks it.

## 9. Observability & operations  *(D-10)*

- Actuator health (liveness/readiness), Micrometer/Prometheus metrics, **structured JSON logs**,
  graceful shutdown. Sized HikariCP pool. A `traceId` propagates into every error body.

## 10. Coding conventions

- Java 21, modern idioms (records for DTOs/value carriers, sealed types where they clarify, pattern
  matching). Constructor injection only; no field injection.
- Immutability by default; fail fast with precise exceptions mapped to the error contract.
- Small, named, intention-revealing units. No premature abstraction — the only seam we commit to up
  front is the `ExchangeRateProvider` port.
- Comments explain **why**, not what. Keep the *why* in code minimal; the deep why is the DECISION_LOG.
