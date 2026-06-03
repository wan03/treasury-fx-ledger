# Tasks — the ordered build plan

> The slice-by-slice sequence to implement the system, dependency-aware. `[P]` = parallelizable with
> its siblings. Each task names the unit(s) to read and the acceptance criteria (AC) it satisfies.
> **Gate discipline:** finish and validate a phase (its tests green, its contract honored) before the
> next. Walk the spec/plan units before writing code — don't run ahead of them.
>
> **Implementation is green-lit** (all D-01..D-12 DECIDED). Build top-down from Phase 0, honoring each gate.

## Phase 0 — Scaffold & contract-first

- [x] **T0.1** Gradle (Kotlin DSL) project; Java 21; Spring Boot 3.5.14; split source sets `test` /
      `integrationTest` (JVM Test Suite plugin). Virtual threads enabled. *(plan.md)*
- [x] **T0.2** Quality plugins wired but lenient to start: JaCoCo, PIT, ArchUnit, jqwik, springdoc. *(test-strategy.md)*
- [x] **T0.3** **Authored `openapi.yaml` (OpenAPI 3.1)** — the contract source of truth: the 3 endpoints,
      DTO schemas, the RFC 9457 error schema, operations tagged R1/R2. *(api-contract.md, CC-4)*
- [x] **T0.4** Profiles (`dev`/`test`/`prod`), `.env.example`, `Clock` bean, ECS structured-JSON logging,
      Actuator health. **No secrets in repo.** *(plan.md, constitution §5/§9)*
- [x] **T0.5** **Deployment scaffold (D-12 — Render + Neon):** multi-stage `Dockerfile` (build → slim
      JRE runtime, non-root, layered via `jarmode=tools --launcher`); `render.yaml` blueprint (web
      service + `/actuator/health` check + env wiring); `prod` profile points at **Neon** via
      `DATABASE_URL`/discrete creds; graceful shutdown on. **Secrets via Render env only.** *(plan.md Deployment, constitution §5)*

**Gate:** ✅ **MET** (commit `f883daf`). Verified: `./gradlew clean build` green + `integrationTest`
compiles; app boots vs. real Postgres 16; Swagger UI renders the authored `/openapi.yaml`; prod profile
gates Swagger/api-docs off and emits ECS JSON; `podman build` image runs **non-root** with health UP.

## Phase 1 — Persistence foundation

- [x] **T1.1** Flyway `V1__purchases.sql`, `V2__idempotency_keys.sql`, `V3__exchange_rates.sql` (in scope
      for providers B/C, D-03). Full `migration` (DDL owner) vs `app` (DML-only) role split via
      `db/init/00-roles.sql`; each migration GRANTs least-privilege DML. *(data-model.md)*
- [x] **T1.2** `spring-boot-docker-compose` for `dev` (compose runs `db/init`; service labelled `ignore`
      so the app connects as `app`, not the superuser); Testcontainers Postgres 16 for `test` with the
      same init script + role split via `@DynamicPropertySource`. *(plan.md, D-10)*
- [x] **T1.3** Persistence-slice IT (`PurchasePersistenceIT`): migrations apply; `NUMERIC(19,2)` scale
      preserved (`12.30` ≠ `12.3`); DB `CHECK`s reject bad rows; **+ proves `app` is denied
      DDL/UPDATE/DELETE** on the append-only ledger. *(test-strategy.md §2)*

**Gate:** ✅ **MET** — migration slice green (`integrationTest`: 6/6 via Testcontainers→Podman). Verified
end-to-end against real Postgres 16: roles created, Flyway runs as `migration`, app connects as `app`,
all CHECK + privilege boundaries enforced. *(Live `make dev` needs a Docker/Compose CLI; the equivalent
boot path — app-as-`app` + Flyway-as-`migration` against real Postgres — is verified.)*

## Phase 2 — Domain core (pure, no framework)  — the heart

- [ ] **T2.1 [P]** `Money` value object + rounding policy (BigDecimal, HALF_UP, round once, scale 2,
      `compareTo`). *(constitution §1, D-04)* → AC-2.1
- [ ] **T2.2 [P]** `RateSelector` pure function (effective_date, 6-month inclusive, calendar-month,
      tiebreak). *(rate-selection.md, D-02)* → AC-2.2, AC-2.3, AC-2.4
- [ ] **T2.3 [P]** Validation rules: description (code points), amount (`^\d{1,17}(\.\d{1,2})?$`, >0,
      reject >2dp), date (strict ISO, reject future via `Clock`). *(api-contract.md, D-05/06)* → AC-1.3/1.4/1.5
- [ ] **T2.4 [P]** `CurrencyMap` loader + resolution policy (ISO→descriptor; USD identity;
      XOF≠XAF; unsupported/malformed). Map artifact under `resources/`. *(currency-mapping.md, D-01)* → AC-2.5, AC-2.6
- [ ] **T2.5** Unit tests for T2.1–T2.4 incl. the **Argentina amendment** + **leap-year boundary** +
      **tie-rounding** + **XOF≠XAF** fixtures; jqwik invariants on Money. *(test-strategy.md §1)*

**Gate:** domain unit suite green; **PIT mutation threshold met on `money` + `rate-selection`**;
ArchUnit confirms `domain` has no framework imports.

## Phase 3 — Application services & ports

- [ ] **T3.1** Ports: `PurchaseRepository`, `ExchangeRateProvider`. *(plan.md)*
- [ ] **T3.2** `StorePurchaseService` (validate → assign UUIDv7 → persist; idempotency in one tx).
      *(D-08, data-model.md)* → AC-1.1, AC-1.2, AC-1.7
- [ ] **T3.3** `ConvertPurchaseService` (load purchase → USD identity short-circuit → resolve currency
      → provider.findRate → Money multiply/round → assemble response). *(rate-selection.md, D-07)*
      → AC-2.1, AC-2.5, AC-2.7
- [ ] **T3.4** Persistence adapter (Spring Data JDBC) + `Money`↔`NUMERIC` converter; UUIDv7 generator.

**Gate:** service-level tests green against the persistence slice; idempotency atomicity proven.

## Phase 4 — Treasury adapters (A0/A/B/C) + resilience

> **All four providers, config-selectable behind the port (D-03); default = A.** Implement in order
> **A0 → A → B → C** — the system is fully functional after A; B/C are additive (don't let them block).

- [ ] **T4.1** Shared HTTP **fetcher** (`RestClient`) issuing the F7 push-down query; tolerant DTO +
      string→BigDecimal parsing. *(rate-selection.md, plan.md)*
- [ ] **T4.2** Provider **A0 passthrough** (bare fetcher) **and A on-demand + cache** (Caffeine
      **decorator** over the fetcher; key `(currency, quarter)`; quarter-aware TTL; brief negative cache).
      **A is the default** (`fx.rates.provider=ondemand`). *(D-03)*
- [ ] **T4.3** Resilience around the fetcher: timeouts, bounded retries (5xx/timeout only), circuit
      breaker, mapped `502/503/504`. *(constitution §7)* → AC-2.8
- [ ] **T4.4** Provider **B ingest** (`provider=ingest`): `exchange_rates` table (data-model.md `V3`);
      a **triggered + scheduled sync** that backfills and **reconciles amendments**; local indexed
      selection query (`= desc AND effective_date <= d ORDER BY effective_date DESC LIMIT 1`). *(D-03, data-model.md)*
- [ ] **T4.5** Provider **C hybrid** (`provider=hybrid`): local-first over B with **lazy fill on miss** +
      **current-quarter background refresh**. *(D-03)*
- [ ] **T4.6** Adapter slices vs **WireMock** / Testcontainers: outgoing-query assertion; captured-payload
      parse; empty `data[]` → no-rate; resilience behaviors; schema tolerance; B sync + amendment
      reconciliation. **Provider-parity test:** all four return the same rate for a fixture date. *(test-strategy.md §2, §4)*

**Gate:** all four providers selectable and slice-green incl. resilience; **provider-parity test passes**;
contract test against the captured payload passes. (Shippable after T4.2–T4.3; B/C may land in a later PR.)

## Phase 5 — Web layer & error contract

- [ ] **T5.1** Controllers: `POST /v1/purchases`, `GET /v1/purchases/{id}`,
      `GET /v1/purchases/{id}/conversions/{currencyCode}`; DTOs as records; money/rates as strings.
- [ ] **T5.2** `@ControllerAdvice` → RFC 9457 `application/problem+json` with `code`, `detail`,
      `traceId`, `errors[]`; the full status discipline (400/404/409/422/429/502-504). *(api-contract.md)*
      → CC-1, AC-1.3/1.4/1.5, AC-2.4/2.6/2.7
- [ ] **T5.3** Security/hygiene: TLS config notes, request-size limits, CORS deny-by-default, **no
      amounts/PII in logs**, caching headers (`ETag`, moderate `max-age`). *(constitution §5, api-contract.md)*
      → CC-2
- [ ] **T5.4** `@WebMvcTest` slice for both endpoints (success + every error code). *(test-strategy.md §2)*

**Gate:** web slice green; OpenAPI matches implemented shapes (contract test).

## Phase 6 — Integration / E2E & final gates

- [ ] **T6.1** `@SpringBootTest` E2E (Testcontainers + WireMock): golden path; no-rate `422`;
      **amendment selects 1230**; idempotency replay. *(test-strategy.md §3)*
- [ ] **T6.2** Non-gating `@Tag("live")` canary: live fields present; **every map entry resolves**;
      `XOF`≠`XAF`. *(currency-mapping.md, test-strategy.md §4)*
- [ ] **T6.3** Tighten gates: JaCoCo floor, PIT threshold, ArchUnit rules all enforced in CI; CI split
      PR (fast) vs nightly (mutation + canary).
- [ ] **T6.4** R↔test traceability matrix filled; spec.md acceptance checklist all ticked.

**Gate (definition of done):** every AC in `spec.md` has a passing test; the acceptance checklist is
complete; `make dev` runs from a clean checkout; assumptions list compiled for the submission.

## Phase 7 — Submission polish

- [ ] **T7.1** README: quickstart (JDK + Docker → `make dev`), the architecture sketch, **the explicit
      assumptions** (effective_date vs record_date, reject-vs-round, future dates, USD identity, union
      primaries, 2-dp caveat), and the HM open-questions list.
- [ ] **T7.2** Makefile targets `dev / test / integration / build / db-migrate / clean` all work.
- [ ] **T7.3** Final pass: no secrets committed; logs clean of PII; licenses/attribution; tag the repo.
- [ ] **T7.4** **Deploy to Render + Neon (D-12):** create the Neon DB (two roles), connect the repo via
      `render.yaml`, set env secrets in Render, verify the **live HTTPS URL** (health + a POST→GET EUR
      round-trip), and link it in the README. Note the cold-start caveat honestly.

---

### Parallelization summary

Phase 2 tasks T2.1–T2.4 are independent (`[P]`). Phases are sequential (each gate depends on the
prior). Within 4–5, adapter and web work can overlap once ports (T3.1) exist. Keep each PR to one
phase or one `[P]` task for reviewable slices.
