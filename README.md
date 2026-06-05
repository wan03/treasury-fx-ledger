# WEX Currency Ledger

> A production-grade service that **stores USD purchase transactions** and **returns them converted**
> into a target currency using the official **U.S. Treasury _Reporting Rates of Exchange_** API.
> **Java 21 · Spring Boot 3.5 · PostgreSQL · hexagonal architecture.**

### ▶ Try it live — **[currency-ledger.onrender.com](https://currency-ledger.onrender.com)**

**The home page _is_ the app — an interactive explorer, nothing to install.** One page switches between the
**Codebase** (an architecture map, a browsable decision log, a code tour, and a **live rate-selection
playground** where you drag a purchase date and watch the Treasury rate get chosen) and the **Live App**
(an API playground that drives the real service same-origin — `POST /v1/purchases` →
`GET …/conversions/EUR`, live). Just open the link and explore.

> **Prefer to run it yourself?** → **[Run it locally](#run-it-locally)** (one command).
>
> **Honest cold-start note:** the free hosting tier sleeps after ~15 min idle, so the first request after a
> pause takes ~1 min while the instance wakes — a free-tier trait, not an app warm-up cost.

_(The explorer is static — [`explore.html`](src/main/resources/static/explore.html) + its same-origin
siblings `explore.css` / `explore.js`, no build step — and also opens standalone from disk.)_

---

## At a glance — what was built

Two operations over one domain (money) and one external dependency (Treasury). **The happy path is
trivial; the engineering is in the parts that aren't** — money handling, rate-selection correctness,
resilience, security, and the test strategy.

| | Capability | Endpoint |
|---|---|---|
| **R1** | Store a purchase — description (≤50), date (not future), positive USD amount; server mints a **UUIDv7** | `POST /v1/purchases` |
| | Read the immutable record back | `GET /v1/purchases/{id}` |
| **R2** | Convert it — returns the original USD, the **Treasury rate used**, its effective date, and the converted amount (2 dp) | `GET /v1/purchases/{id}/conversions/{code}` |

**Eight decisions that carry the signal** (each links to the reasoning):

- 💵 **Money is `BigDecimal`, never float** — full precision, rounded **once**, HALF_UP, scale 2; crosses the wire as a **JSON string**. → [§Engineering signal](#the-engineering-signal-where-the-care-went) · [D-04](docs/DECISION_LOG.md)
- 🎯 **Rates select on `effective_date`, not `record_date`** — so Treasury's **intra-quarter amendments** pick the right rate (the Argentina fixture proves it). → [§Architecture](#architecture) · [D-02](docs/DECISION_LOG.md)
- 🌍 **`XOF ≠ XAF`** — both read "Cfa Franc" but are different rates; resolved through a curated ISO-4217 map. → [`currency-map.csv`](src/main/resources/currency-map.csv) · [D-01](docs/DECISION_LOG.md)
- 🚫 **Reject, never mutate the principal** — an amount with >2 dp is a `400`, not a silent round. → [D-05](docs/DECISION_LOG.md)
- 🔌 **One `ExchangeRateProvider` port, four config-selectable adapters** — acquisition strategy is a flag, not a rewrite. → [§The seam](#the-exchangerateprovider-seam--the-headline-decision-d-03) · [D-03](docs/DECISION_LOG.md)
- 🛡️ **Resilient by construction** — bounded retries + circuit breaker → `502/503/504`, never a hang or a leaking `500`. → [D-03](docs/DECISION_LOG.md)
- 🔒 **Security first** — no amounts/PII in URLs or logs; least-privilege DB roles; RFC 9457 errors. → [§Engineering signal](#the-engineering-signal-where-the-care-went) · [D-09/D-10](docs/DECISION_LOG.md)
- 🧪 **Deterministic tests, real gates** — injected `Clock`, **zero live network in the gate**, no H2; PIT mutation **92%** on the money/rate core. → [§Testing](#testing--quality-gates) · [D-11](docs/DECISION_LOG.md)

---

## 📑 Mini-documentation — jump to what interests you

| If you want to… | Read this section | …or go straight to the source |
|---|---|---|
| Run it on your machine | [Run it locally](#run-it-locally) | [`Makefile`](Makefile) |
| Understand the shape of the system | [Architecture](#architecture) | [`docs/builder/plan.md`](docs/builder/plan.md) |
| Judge the money handling | [Engineering signal](#the-engineering-signal-where-the-care-went) | [`Money.java`](src/main/java/com/wex/fx/domain/money/Money.java) |
| Inspect the **rate-selection rule** (the crux) | [Architecture](#architecture) | [`RateSelector.java`](src/main/java/com/wex/fx/domain/rate/RateSelector.java) · [`rate-selection.md`](docs/builder/rate-selection.md) |
| Check the currency mapping (`XOF`≠`XAF`) | [At a glance](#at-a-glance--what-was-built) | [`currency-map.csv`](src/main/resources/currency-map.csv) · [`currency-mapping.md`](docs/builder/currency-mapping.md) |
| Review the HTTP contract + errors | [Engineering signal](#the-engineering-signal-where-the-care-went) | [`openapi.yaml`](src/main/resources/static/openapi.yaml) · [`ApiExceptionHandler.java`](src/main/java/com/wex/fx/adapter/web/ApiExceptionHandler.java) |
| Evaluate the test strategy | [Testing & quality gates](#testing--quality-gates) | [`test-strategy.md`](docs/builder/test-strategy.md) |
| See the resilience story | [Engineering signal](#the-engineering-signal-where-the-care-went) | [`ResilientRateFetcher.java`](src/main/java/com/wex/fx/adapter/treasury/ResilientRateFetcher.java) |
| Read **why** any decision was made | — | [`docs/DECISION_LOG.md`](docs/DECISION_LOG.md) (ADRs `D-01…D-13`, Treasury facts `F1…F9`) |
| Know what I assumed & would ask | [Assumptions](#assumptions-committed-defaults--overridable-if-the-brief-intends-otherwise) | [`HIRING_MANAGER_QUESTIONS.md`](docs/HIRING_MANAGER_QUESTIONS.md) |

> **Two lenses on the same work:** this README is the *narrative*; the **live app's home page**
> ([currency-ledger.onrender.com](https://currency-ledger.onrender.com), source
> [`explore.html`](src/main/resources/static/explore.html)) is the *interactive* version (and it links
> back here). Use whichever suits you.

---

## Run it locally

> The live app above needs nothing installed. This section is **only** if you'd rather run it yourself.

**Prerequisites:** JDK 21 (Gradle auto-provisions a toolchain if missing) and a container runtime
(Docker Desktop, or rootless Podman with a compose provider). The Gradle wrapper is committed — nothing else.

```bash
make dev        # starts Postgres (docker-compose), applies migrations, runs the app on :8080
```

Open **http://localhost:8080/** for the explorer or **/swagger-ui.html** for raw Swagger (dev only) — or
drive it from the shell:

```bash
# R1 — store a purchase (UUIDv7 returned in the body + Location header)
curl -sS -X POST http://localhost:8080/v1/purchases \
  -H 'Content-Type: application/json' \
  -d '{"description":"Office supplies","transactionDate":"2025-04-15","amount":"100.00"}'

# R2 — convert it to EUR (money & rate are JSON STRINGS)
curl -sS http://localhost:8080/v1/purchases/<id>/conversions/EUR
```

### Make targets

| Target | What it does |
|---|---|
| `make dev` | Run locally (dev profile); auto-starts Postgres; Swagger UI up |
| `make test` | Fast unit + slice tests — **no network, no containers** (the PR gate) |
| `make integration` | Testcontainers + WireMock integration/E2E (needs the container socket) |
| `make canary` | The live Treasury probe **only** (`@Tag("live")`, real network; non-gating) |
| `make mutation` | PIT mutation testing on the money + rate-selection core |
| `make build` | Production jar + all fast gates |
| `make db-migrate` | Apply Flyway migrations (env / `.env`) |
| `make clean` | Remove build outputs |

---

## Architecture

**Hexagonal (ports & adapters).** The domain is small, but the **boundaries are the point**: dependencies
point inward only, enforced by ArchUnit. The framework-free core (`domain` + `application`) has zero
Spring/web/JDBC imports, so the business rules are unit-testable without a container.

```
   HTTP ─▶  adapter/web        controllers · RFC 9457 ProblemDetail · DTOs · OpenAPI
                  │
                  ▼
            application         StorePurchase · GetPurchase · ConvertPurchase
                  │             (orchestrates domain + ports; owns the @Transactional boundary)
        ┌─────────┴─────────┐
        ▼                   ▼
   domain (pure)        ports (interfaces)
   Money · RateSelector  PurchaseRepository · ExchangeRateProvider · Clock
   CurrencyMap · rules         │
                               ▼
                       outbound adapters
                       persistence/ (Spring Data JDBC + Flyway · Postgres)
                       treasury/    (ExchangeRateProvider impls + resilience)
```

**The crux — rate selection** ([`RateSelector.java`](src/main/java/com/wex/fx/domain/rate/RateSelector.java),
a pure function): choose the rate with the **greatest `effective_date ≤ purchaseDate`**, within a **6
calendar-month** window (inclusive, leap-day-aware). No rate in the window ⇒ `422 NO_RATE_AVAILABLE`.
Selecting on `effective_date` (not `record_date`) is what makes **intra-quarter amendments** correct — the
[explorer's playground](src/main/resources/static/explore.html) (the app's home page) demonstrates this live.

### The `ExchangeRateProvider` seam — the headline decision (D-03)

One port, **four config-selectable adapters**, chosen by `fx.rates.provider`:

| `fx.rates.provider` | Adapter | Strength | When |
|---|---|---|---|
| `passthrough` | A0 — one Treasury call per request, no cache | simplest | dev / diagnostic |
| **`ondemand`** _(default)_ | A — filtered call + quarter-aware Caffeine cache | high hit rate, few moving parts | low/moderate load |
| `ingest` | B — sync into a local `exchange_rates` table, query locally | fully decoupled, fast, offline-capable | high RPS / own-history |
| `hybrid` | C — local-first with lazy fill | best of both | evolution of B at scale |

A0/A share one HTTP fetcher (A is a **cache decorator** over it); B/C share the table. Shipping the port
seam means the acquisition strategy is a config flag, not a rewrite. _(See [`plan.md`](docs/builder/plan.md).)_

> **Scaling out (>1 instance):** A's cache is **per-instance**, so multiple replicas multiply Treasury
> load. Set `FX_RATES_PROVIDER=ingest` (or `hybrid`) so every replica reads the shared `exchange_rates`
> table (the database is the shared store — no Redis). `ondemand` stays the single-instance default. _(D-03)_

---

## The engineering signal (where the care went)

- **Money is `BigDecimal`, never `float`/`double`.** Full precision throughout; **round once at the end**,
  `HALF_UP`, scale 2. Compared with `compareTo`, never `equals`. Money & rates cross the wire as **JSON
  strings** so no client re-parses them into a lossy binary float. _(D-04,
  [`constitution.md`](docs/builder/constitution.md) · [`Money.java`](src/main/java/com/wex/fx/domain/money/Money.java))_
- **The principal is never mutated.** An amount with >2 dp is **rejected (`400`), not rounded** — the only
  rounding is the derived conversion output. _(D-05)_
- **Rate selection keys on `effective_date`, not `record_date` — and ships _both_ readings.** Treasury's
  own guidance (F8, source-quoted) says an **intra-quarter amendment** is a new row with a later
  `effective_date`, valid for the rest of the quarter; selecting on `record_date` silently mis-rates a
  post-amendment purchase. The two readings are **identical for every non-amended currency** and diverge
  only across an amendment — so the literal-brief reading is a one-line flip
  (`fx.rates.rate-date-basis=record_date`), default `effective_date`. A test pins both:
  agree off-amendment, diverge on the Argentina Q2→Q3 amendment. _(D-02, F4/F8,
  [`rate-selection.md`](docs/builder/rate-selection.md))_
- **Currency is ISO-4217 in, resolved through a curated, version-controlled map** to Treasury's
  `country_currency_desc`. `XOF ≠ XAF` (both read "Cfa Franc", different rates). `USD` is an in-app
  **identity** (rate `1.00`, no upstream call). _(D-01/D-07, F5/F9,
  [`currency-mapping.md`](docs/builder/currency-mapping.md))_
- **Resilience:** bounded timeouts, bounded retries (5xx/timeout only — never a 4xx), and a circuit breaker;
  failures map to `502/503/504` with a `Retry-After` on the open circuit — **never a hang, never a `500`
  leaking internals.** _(D-03, [`ResilientRateFetcher.java`](src/main/java/com/wex/fx/adapter/treasury/ResilientRateFetcher.java))_
- **Security:** TLS/HSTS assumed at the edge; **no amounts or PII (the `description`) in URLs or logs** —
  ids + `traceId` only; least-privilege DB roles (a `migration` DDL role separate from the `app` DML role);
  no secrets in the repo. _(D-09/D-10, [`constitution.md`](docs/builder/constitution.md) §5/§9)_
- **Errors are RFC 9457** `application/problem+json` with a machine `code` + `traceId`; `422` for
  well-formed-but-unfulfillable, `400` for malformed. _(D-09,
  [`ApiExceptionHandler.java`](src/main/java/com/wex/fx/adapter/web/ApiExceptionHandler.java))_

---

## Testing & quality gates

A real test pyramid with **deterministic gates** — injected `Clock`, **zero real network in the gating
suite** (WireMock + Testcontainers), **no H2** (prod-parity Postgres everywhere).

```bash
make test          # fast: pure unit + @WebMvcTest slices + ArchUnit + the JaCoCo core floor
make integration   # Testcontainers (Postgres) + WireMock: persistence, resilience, full-stack E2E
make mutation      # PIT mutation score on the money + rate-selection core
make canary        # the live Treasury probe (opt-in; never gates)
```

| Gate | Enforced |
|---|---|
| **ArchUnit** | `domain`/`application` import no framework; adapters depend inward only |
| **JaCoCo** | floor on the framework-free core (`domain.*`+`application.*`) ≥ **85%** instruction (a guardrail) |
| **PIT mutation** | ≥ **85** on `domain.*` — assertion _strength_, not just line execution (measured **92%**) |
| **OpenAPI** | the authored [`openapi.yaml`](src/main/resources/static/openapi.yaml) is the contract source of truth, served via Swagger UI |

Every acceptance criterion in [`spec.md`](docs/builder/spec.md) maps to a concrete test in the **R↔test
matrix** ([`test-strategy.md`](docs/builder/test-strategy.md)). CI is split:
[`ci.yml`](.github/workflows/ci.yml) runs the fast gate on every PR;
[`nightly.yml`](.github/workflows/nightly.yml) runs integration + mutation + the live canary.

### Requirement → implementation → test (traceability at a glance)

| Brief requirement | Where it lives | Proven by |
|---|---|---|
| **R1** store: `description` ≤ 50, valid date, positive USD, unique id | [`PurchaseValidator`](src/main/java/com/wex/fx/domain/validation/PurchaseValidator.java) · [`StorePurchaseService`](src/main/java/com/wex/fx/application/StorePurchaseService.java) | `PurchaseValidatorTest`, `PurchaseControllerTest`, `PurchaseConversionE2EIT` |
| amount **rounded to cent** (we **reject** >2 dp, never mutate) | [`Money`](src/main/java/com/wex/fx/domain/money/Money.java) (D-05) | `MoneyTest` (jqwik), `PurchaseValidatorTest` |
| server-assigned **unique id** (UUIDv7) | `IdGenerator` port (D-08) | `StorePurchaseServiceTest`, persistence IT |
| **R2** convert via Treasury; response carries id, desc, date, USD, **rate used**, converted | [`ConvertPurchaseService`](src/main/java/com/wex/fx/application/ConvertPurchaseService.java) | `ConvertPurchaseServiceTest`, `PurchaseConversionE2EIT` |
| rate **≤ purchase date, within prior 6 months**, latest wins | [`RateSelector`](src/main/java/com/wex/fx/domain/rate/RateSelector.java) (D-02) | `RateSelectorTest` (both bounds, leap-day, amendment, basis readings) |
| selection date basis (`effective_date` default / `record_date`) | `RateDateBasis` + push-down [`TreasuryRateFetcher`](src/main/java/com/wex/fx/adapter/treasury/TreasuryRateFetcher.java) | `RateSelectorTest$RateDateBasisReadings`, `TreasuryRateFetcherTest` (asserts outbound query) |
| **no rate in window → error** "cannot be converted" | `NoRateAvailableException` → **422** | `ConvertPurchaseServiceTest`, web slice |
| converted amount **rounded to 2 dp** (rate kept full precision) | [`Money`](src/main/java/com/wex/fx/domain/money/Money.java) (D-04) | `MoneyTest`, `ConvertPurchaseServiceTest` |
| **production-grade** (migrations, resilience, observability, tests) | Flyway · Resilience4j · actuator · split test suites | `make integration` + the gates above |

---

## Configuration

Twelve-factor: config is environment, not code. Copy [`.env.example`](.env.example) → `.env` (git-ignored)
and fill in. Profiles: `dev` (local compose) · `test` · `prod`.

| Variable | Purpose |
|---|---|
| `DATABASE_URL` / `DATABASE_USERNAME` / `DATABASE_PASSWORD` | the **`app`** DML role (runtime) |
| `DB_MIGRATION_USERNAME` / `DB_MIGRATION_PASSWORD` | the **`migration`** DDL role (Flyway only) |
| `FX_RATES_TREASURY_BASE_URL` | Treasury base URL (public, unauthenticated; overridden by WireMock in tests) |
| `FX_RATES_PROVIDER` | `passthrough` \| `ondemand` (default) \| `ingest` \| `hybrid` |
| `FX_RATES_RATE_DATE_BASIS` | `effective_date` (default) \| `record_date` — which Treasury date governs selection (D-02) |

All rate tunables (the 6-month window, timeouts, retry/breaker thresholds, cache TTLs) are bound config,
not magic numbers — see `fx.rates.*` in [`application.yml`](src/main/resources/application.yml).

### Keeping the demo warm

The free hosting tier sleeps after ~15 min idle (~1-min cold start). To avoid that during review hours,
an **external uptime pinger** hits `GET /actuator/health` on a schedule — this is the primary keep-warm.
An external service (e.g. **cron-job.org** / UptimeRobot) is used rather than a GitHub Actions `schedule`,
because GitHub cron is best-effort and heavily throttled (in practice it never fired reliably here).

Recommended pinger config:

| Setting | Value |
|---|---|
| URL | `https://currency-ledger.onrender.com/actuator/health` (GET, expect `200`) |
| Schedule (cron) | `*/10 13-23 * * 1-5` — every 10 min, **13:00–23:00 UTC**, Mon–Fri |

The window is deliberate, not 24/7: keeping the instance always-on would burn ~730 of Render's
**750 free instance-hours per month**, so the ping is scoped to likely review hours (≈ 06:00–16:00
America/Los_Angeles, Render's `oregon` region) — set the pinger's timezone to use local business hours
instead. The first request after a fresh **deploy** still cold-starts; the pinger keeps it warm thereafter,
and the explorer shows a friendly “waking…” state for that case regardless.

[`keep-warm.yml`](.github/workflows/keep-warm.yml) remains as a **manual one-click fallback** (Actions →
*keep-warm* → *Run workflow*) to warm the instance on demand — its unreliable `schedule` trigger was removed.

---

## Assumptions (committed defaults — overridable if the brief intends otherwise)

The brief leaves several choices open; each is resolved by a documented default and remains a one-line
config/flag change if steered otherwise.

1. **`effective_date`, not `record_date`**, governs rate selection — intra-quarter amendments are real;
   the literal-brief `record_date` reading is shipped behind `fx.rates.rate-date-basis` (identical except across an amendment). _(D-02)_
2. **Reject amounts with >2 dp (`400`), never round** the principal; the only rounding is the conversion output. _(D-05)_
3. **Future-dated purchases are rejected;** too-old ones are stored but fail conversion with `422 NO_RATE_AVAILABLE`. _(D-06)_
4. **`USD` target is an in-app identity** (rate `1.00`, no Treasury call) and is never in the currency map. _(D-07)_
5. **Currency in = ISO-4217**, resolved via a curated map; **`XOF ≠ XAF`**. _(D-01)_
6. **The 6-month window is inclusive** and uses calendar-month (leap-day-aware) arithmetic. _(D-02)_
7. **Identifiers are server-generated UUIDv7;** purchases are **append-only** (no `PUT`/`PATCH`/`DELETE`). _(D-08/D-09)_
8. **Money & rates are JSON strings;** errors are RFC 9457 `problem+json`. _(D-09)_
9. **Provider A (`ondemand`) is the production default;** B/C (ingest/hybrid) ship behind config. _(D-03)_
10. **AuthN/AuthZ & multi-tenancy are out of scope** — assumed at an upstream gateway; the filter seam is left in place. _(D-09)_

A single 1000-row window page is assumed sufficient per currency (Treasury's per-currency history is
quarterly and small); full pagination is a documented future extension.

### Questions I would ask the hiring manager

These are the seven I'd raise; I committed to a default for each so the build stayed unblocked. Full draft
in [`docs/HIRING_MANAGER_QUESTIONS.md`](docs/HIRING_MANAGER_QUESTIONS.md).

1. **Currency input contract** — ISO-4217 + we own the map, or raw `country_currency_desc`? → _ISO-4217 + curated map._
2. **Authoritative rate date** — `record_date` or `effective_date`? → _`effective_date`._
3. **Expected scale / read pattern** — to right-size the rates strategy. → _Built all four adapters; default A._
4. **Amount precision** — reject >2 dp or round? → _Reject; never mutate the principal._
5. **Future-dated transactions** — allowed at store time? → _Reject future; accept old._
6. **`USD` as target** — identity or unsupported? → _Identity._
7. **AuthN/AuthZ & multi-tenancy** — in scope, or upstream gateway? → _Assume gateway; leave the seam._

---

## Project layout

```
src/main/java/com/wex/fx/
  domain/            money · purchase · rate · currency · validation   (pure — no framework)
  application/       StorePurchase · GetPurchase · ConvertPurchase + ports/
  adapter/web/       controllers · dto · RFC 9457 error mapping
  adapter/persistence/  Spring Data JDBC + Money↔NUMERIC converter
  adapter/treasury/  the four ExchangeRateProvider variants + resilience
  config/            profiles · Clock · HTTP client · Jackson · OpenAPI
  resources/static/  explore.{html,css,js} + favicon.svg (interactive home page, served at /) + openapi.yaml
src/test/            fast unit + @WebMvcTest slices (no network)
src/integrationTest/ Testcontainers + WireMock: persistence, resilience, E2E, live canary
docs/                DECISION_LOG.md (the why) + builder/ (spec, plan, contract, …)
```

| Doc | Read it for |
|---|---|
| [`/` → `explore.html`](src/main/resources/static/explore.html) | the **interactive** home page (codebase ⟷ live app, rate-selection playground) — served by the app |
| [`docs/DECISION_LOG.md`](docs/DECISION_LOG.md) | the _why_ — every decision (`D-01…D-13`) + verified Treasury facts (`F1…F9`) |
| [`docs/builder/spec.md`](docs/builder/spec.md) | _what_ to build — acceptance criteria (R1/R2) |
| [`docs/builder/plan.md`](docs/builder/plan.md) | architecture, the port/adapter seam |
| [`docs/builder/rate-selection.md`](docs/builder/rate-selection.md) | the 6-month / effective-date selection rule |
| [`docs/builder/currency-mapping.md`](docs/builder/currency-mapping.md) | ISO → Treasury descriptor resolution |
| [`docs/builder/api-contract.md`](docs/builder/api-contract.md) | the HTTP surface + error catalog |
| [`docs/builder/test-strategy.md`](docs/builder/test-strategy.md) | the pyramid + the R↔test matrix |

---

## Notes

- **Data source:** U.S. Treasury Fiscal Data — _Reporting Rates of Exchange_ (public domain, no key required).
- **Take-home exercise** for WEX Corporate Payments; proprietary to the author for evaluation purposes.
