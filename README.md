# WEX Currency Ledger

> A production-grade service that **stores USD purchase transactions** and **returns them converted**
> into a target currency using the official **U.S. Treasury _Reporting Rates of Exchange_** API.
> **Java 21 · Spring Boot 3.5 · PostgreSQL · hexagonal architecture.**

<table>
<tr>
<td width="50%">

### ▶ Explore interactively
**The app's home page _is_ an interactive explorer** — run `make dev` and open
**[`http://localhost:8080/`](http://localhost:8080/)**. One page lets you switch between
the **Codebase** and the **Live App**: an architecture map, a browsable decision log, a code tour, and a
**live rate-selection playground** where you drag a purchase date and watch the Treasury rate get chosen.
Served by the app, its **Live App** tab calls the real API **same-origin** (no CORS).
_(Source: [`explore.html`](src/main/resources/static/explore.html) + its same-origin siblings
`explore.css` / `explore.js` — static, no build step; also opens standalone from disk, no install.)_

</td>
<td width="50%">

### 🌐 Live demo
**`_<set after deploy — see [Deployment](#deployment)>_`**
The deployed root serves the explorer; its **Live App** tab drives the real API live. Swagger UI at
`/swagger-ui.html` (dev) and a raw `POST /v1/purchases` → `GET …/conversions/EUR` also work.

</td>
</tr>
</table>

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
| See it run in 30 seconds | [Quickstart](#quickstart) | [`Makefile`](Makefile) |
| Understand the shape of the system | [Architecture](#architecture) | [`docs/builder/plan.md`](docs/builder/plan.md) |
| Judge the money handling | [Engineering signal](#the-engineering-signal-where-the-care-went) | [`Money.java`](src/main/java/com/wex/fx/domain/money/Money.java) |
| Inspect the **rate-selection rule** (the crux) | [Architecture](#architecture) | [`RateSelector.java`](src/main/java/com/wex/fx/domain/rate/RateSelector.java) · [`rate-selection.md`](docs/builder/rate-selection.md) |
| Check the currency mapping (`XOF`≠`XAF`) | [At a glance](#at-a-glance--what-was-built) | [`currency-map.csv`](src/main/resources/currency-map.csv) · [`currency-mapping.md`](docs/builder/currency-mapping.md) |
| Review the HTTP contract + errors | [Engineering signal](#the-engineering-signal-where-the-care-went) | [`openapi.yaml`](src/main/resources/static/openapi.yaml) · [`ApiExceptionHandler.java`](src/main/java/com/wex/fx/adapter/web/ApiExceptionHandler.java) |
| Evaluate the test strategy | [Testing & quality gates](#testing--quality-gates) | [`test-strategy.md`](docs/builder/test-strategy.md) |
| See the resilience story | [Engineering signal](#the-engineering-signal-where-the-care-went) | [`ResilientRateFetcher.java`](src/main/java/com/wex/fx/adapter/treasury/ResilientRateFetcher.java) |
| Read **why** any decision was made | — | [`docs/DECISION_LOG.md`](docs/DECISION_LOG.md) (ADRs `D-01…D-13`, Treasury facts `F1…F9`) |
| Know what I assumed & would ask | [Assumptions](#assumptions-committed-defaults--overridable-if-the-brief-intends-otherwise) | [`HIRING_MANAGER_QUESTIONS.md`](docs/HIRING_MANAGER_QUESTIONS.md) |
| Deploy it | [Deployment](#deployment) | [`render.yaml`](render.yaml) · [`Dockerfile`](Dockerfile) |

> **Two lenses on the same work:** this README is the *narrative*; the **app's home page**
> ([`http://localhost:8080/`](http://localhost:8080/), source
> [`explore.html`](src/main/resources/static/explore.html)) is the *interactive* version (and it links
> back here). Use whichever suits you.

---

## Quickstart

**Prerequisites:** JDK 21 (Gradle auto-provisions a toolchain if missing) and a container runtime.
The Gradle wrapper is committed; nothing else to install.

```bash
make dev        # starts Postgres (docker-compose), applies migrations, runs the app on :8080
```

Then open **http://localhost:8080/** for the interactive explorer (the recommended way in), or
**http://localhost:8080/swagger-ui.html** for raw Swagger — or drive it from the shell:

```bash
# R1 — store a purchase (UUIDv7 returned in the body + Location header)
curl -sS -X POST http://localhost:8080/v1/purchases \
  -H 'Content-Type: application/json' \
  -d '{"description":"Office supplies","transactionDate":"2025-04-15","amount":"100.00"}'
# → 201 Created · {"id":"019e93ff-…","amount":"100.00","currency":"USD","createdAt":"…Z", …}

# R2 — convert it to EUR (money & rate are JSON STRINGS)
curl -sS http://localhost:8080/v1/purchases/<id>/conversions/EUR
# → 200 OK · {"originalAmount":"100.00","exchangeRate":"0.924","rateEffectiveDate":"2025-03-31",
#             "convertedAmount":"92.40","rateSource":"U.S. Treasury Reporting Rates of Exchange", …}
```

> **Container runtime, precisely:** `make dev` uses Spring Boot's Docker Compose support, which shells out
> to a **Compose CLI** — **Docker Desktop / `docker compose` bundles it out of the box.** On rootless
> **Podman**, install a compose provider (`podman compose`) so `make dev` can auto-start the DB. The
> **test/integration** targets are lighter — they need only the container **API socket** (Testcontainers
> talks to it directly), which a bare rootless Podman already exposes (`make podman-up`). _Verified
> end-to-end on both paths._

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

---

## The engineering signal (where the care went)

- **Money is `BigDecimal`, never `float`/`double`.** Full precision throughout; **round once at the end**,
  `HALF_UP`, scale 2. Compared with `compareTo`, never `equals`. Money & rates cross the wire as **JSON
  strings** so no client re-parses them into a lossy binary float. _(D-04,
  [`constitution.md`](docs/builder/constitution.md) · [`Money.java`](src/main/java/com/wex/fx/domain/money/Money.java))_
- **The principal is never mutated.** An amount with >2 dp is **rejected (`400`), not rounded** — the only
  rounding is the derived conversion output. _(D-05)_
- **Rate selection keys on `effective_date`, not `record_date`.** Treasury issues **intra-quarter
  amendments** as new rows with a later `effective_date` but the same `record_date`; selecting on
  `record_date` would silently pick the wrong rate. The Argentina fixture locks this. _(D-02, F4/F8,
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

All rate tunables (the 6-month window, timeouts, retry/breaker thresholds, cache TTLs) are bound config,
not magic numbers — see `fx.rates.*` in [`application.yml`](src/main/resources/application.yml).

---

## Deployment

Infra-as-code via [`render.yaml`](render.yaml) (Render Blueprint) + a multi-stage
[`Dockerfile`](Dockerfile) (build a layered jar → run on a slim JRE as non-root). **Compute = Render**
(free Docker web service); **database = Neon** (external managed Postgres — decoupled so it survives
Render free-tier's Postgres deletion).

1. **Neon:** create a project + database; create the two least-privilege roles (`migration` for DDL,
   `app` for DML) per [`db/init`](db/init). Grab the two connection strings.
2. **Render:** _New → Blueprint_, point at this repo. `render.yaml` provisions the web service; set the
   five DB secrets (`DATABASE_*`, `DB_MIGRATION_*`) and `SPRING_PROFILES_ACTIVE=prod` in the dashboard
   (they're `sync: false` — never committed).
3. Render builds the Dockerfile, Flyway migrates on boot, and the health check at `/actuator/health`
   gates the rollout. Open the live root `/` for the explorer (its **Live App** tab auto-targets the
   deployed origin same-origin), verify a `POST → GET …/conversions/EUR` round-trip, then paste the
   HTTPS URL into the [Live demo](#-live-demo) line above.

> **Cold-start caveat (honest):** the free Render instance spins down after ~15 min idle, so the first
> request after a pause takes ~1 minute while it wakes. This is a free-tier trait, not an app warm-up cost.

---

## Assumptions (committed defaults — overridable if the brief intends otherwise)

The brief leaves several choices open; each is resolved by a documented default and remains a one-line
config/flag change if steered otherwise.

1. **`effective_date`, not `record_date`**, governs rate selection — intra-quarter amendments are real. _(D-02)_
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
