# Plan — architecture & the *how*

> Technical strategy: structure, the port/adapter seam, resilience, profiles, stack. Decisions here
> implement the constitution; rationale is in `DECISION_LOG.md` D-03/D-09/D-10. Keep this aligned with
> `data-model.md`, `api-contract.md`, and the two domain units.

## Architecture: hexagonal (ports & adapters)

The domain is tiny but the **boundaries are the point**. Three rings, dependencies point inward only
(enforced by ArchUnit — see `test-strategy.md`).

```
                ┌───────────────────────── inbound adapters ─────────────────────────┐
   HTTP  ─────▶ │  web/      PurchaseController, ConversionController, error mapping  │
                │            DTOs (records), RFC 9457 ProblemDetail, OpenAPI          │
                └───────────────────────────────┬────────────────────────────────────┘
                                                 ▼
                ┌──────────────────────── application (use cases) ────────────────────┐
                │  StorePurchase, GetPurchase, ConvertPurchase                        │
                │  orchestrates domain + ports; owns the @Transactional boundary      │
                └───────────┬──────────────────────────────────┬─────────────────────┘
                            ▼                                    ▼
        ┌──────────── domain (pure) ───────────┐     ┌──────────── ports (interfaces) ─────┐
        │ Money, Currency, Purchase            │     │ PurchaseRepository                  │
        │ RateSelector (pure fn), CurrencyMap  │     │ ExchangeRateProvider                │
        │ validation rules, value objects      │     │ Clock (injected)                    │
        │ NO Spring / web / JDBC / HTTP here   │     └──────────────┬──────────────────────┘
        └──────────────────────────────────────┘                    ▼
                                          ┌──────────────── outbound adapters ───────────────┐
                                          │ persistence/  Spring Data JDBC + Flyway (Postgres)│
                                          │ treasury/     ExchangeRateProvider impls (A0/A/B)  │
                                          └───────────────────────────────────────────────────┘
```

**Suggested package layout** (`com.wex.fx` or similar):

```
domain/            money/  purchase/  rate/  currency/      ← pure, no framework imports
application/       StorePurchaseService, ConvertPurchaseService, ports/
adapter/web/       controllers, dto/, error/ (RFC 9457 mapping)
adapter/persistence/  jdbc repositories, Money↔NUMERIC converter
adapter/treasury/  client, dto, the ExchangeRateProvider variants, resilience config
config/            profiles, beans, Clock, HTTP client, resilience, OpenAPI
```

**Rules.** `domain` imports nothing framework. `application` depends on `domain` + `ports` only.
Adapters implement ports and may use Spring. Controllers never touch repositories directly — always
through an application service.

## The `ExchangeRateProvider` seam  *(D-03 — the headline architectural decision)*

One port, **four config-selectable adapters — all built**. Selection by a property:
`fx.rates.provider = passthrough | ondemand | ingest | hybrid` (**default `ondemand` = A**). A0 and A
share one HTTP fetcher (A is a cache **decorator** over it); B/C share the `exchange_rates` table.
Implement in order **A0 → A → B → C** so the system is shippable after A and B/C are additive.

```java
public interface ExchangeRateProvider {
    /** The active rate for an ISO currency on/before purchaseDate within the 6-month window. */
    Optional<ExchangeRate> findRate(CurrencyCode target, LocalDate purchaseDate);
}
```

| Variant | What it does | Strength | Cost | Built? |
|---|---|---|---|---|
| **A0 passthrough** | one Treasury call per request, no cache | simplest | availability/latency coupling | yes |
| **A on-demand + cache** | one filtered call (F7), cache `(currency, quarter)→rate` | high hit rate; few moving parts | cache invalidation thought | **yes — default** |
| **B ingest** | sync into local `exchange_rates`, query locally | fully decoupled, fast | sync job, staleness, amendments | yes (scale path) |
| **C hybrid** | local + lazy fill / background refresh | best of both | most moving parts | yes (evolution of B) |

Historical rates are immutable, which **nullifies A's main weakness** → **A is the default** (D-03). All
four are built behind the port and selected by config, so switching is a one-property change and the
choice is *demonstrably* reversible. **Flip the default to B/C if** HM Q7 indicates a high-RPS/hot read
path, a strict offline-runtime SLA, a need for our own audited rate history, or Treasury rate limits.

**When to use each (by scale / regime).** *(answers HM Q7 — pick the adapter to the operating envelope)*
- **A0 passthrough** — dev, diagnostics, or negligible volume where you want zero cache and always the
  latest Treasury value; no resilience requirement. *(≈ <1 rps, internal tooling / baseline.)*
- **A on-demand + cache (default)** — low/moderate production, read-heavy with locality; the cache
  absorbs load and historical immutability keeps it correct; Treasury is an acceptable dependency.
  *(≈ up to hundreds of rps per instance at a high cache-hit rate.)*
- **B ingest** — high-RPS / hot read path that can't tolerate per-request external latency or Treasury
  coupling; a strict offline / availability SLA; Treasury rate limits; or a need for our own audited,
  queryable rate history. Reads are local index seeks, fully decoupled from Treasury. *(≈ thousands of
  rps, multi-instance, SLA-bound.)*
- **C hybrid** — the same high-scale regime as B, but wanting self-healing lazy fill on miss +
  current-quarter background refresh instead of a full upfront backfill; the long-term production
  posture, at the cost of the most moving parts.

**Cache (provider A).** In-memory **Caffeine**, key `(currency, resolved-quarter)`. **Quarter-aware
TTL:** settled/past quarters are effectively immutable → cache long; the **current quarter** uses a
short TTL so a late amendment (F8) is picked up. Negative ("no rate") results cached briefly. *Single
instance assumed* — a multi-instance deployment moves this to a shared cache (Redis) or runs `provider=B/C`.

**Server-side selection (F7).** The fetcher (shared by A0/A) issues exactly one request expressing the whole rule:
```
GET …/v1/accounting/od/rates_of_exchange
  ?fields=country_currency_desc,exchange_rate,effective_date,record_date
  &filter=country_currency_desc:eq:<desc>,effective_date:lte:<purchaseDate>,effective_date:gte:<floor>
  &sort=-effective_date&page[size]=1&format=json
```
Empty `data[]` ⇒ `Optional.empty()` ⇒ `422 NO_RATE_AVAILABLE`. Detail in `rate-selection.md`.

## Resilience around Treasury  *(constitution §7)*

- **Timeouts:** explicit connect + read (e.g. 2s/5s, configurable). Never unbounded.
- **Retries:** bounded (e.g. 2), **only** on 5xx / timeout / connection failure — **never on 4xx**.
  Exponential backoff + jitter.
- **Circuit breaker:** open on sustained failure → fail fast with `503 + Retry-After`; half-open probe.
- **Tolerant parsing:** ignore unknown JSON fields; a missing **critical** field is a clear mapped
  error, not an NPE. Map upstream failures to `502/503/504`, never leak a `500` with internals.
- Use Spring's `RestClient`/`WebClient` + Resilience4j (or equivalent). Virtual threads make the
  blocking client cheap (D-10) — no reactive complexity needed.

## Configuration & profiles  *(D-10)*

- **`dev`** — `spring-boot-docker-compose` auto-starts Postgres on `bootRun`; Swagger UI on; verbose
  logs; seed data via dev-profile seeder / repeatable migration.
- **`test`** — Testcontainers Postgres (`@ServiceConnection`); WireMock Treasury; fixed `Clock`.
- **`prod`** — **Neon** managed Postgres (see Deployment), secrets from env (Render env vars), Swagger
  gated, JSON logs, Actuator health, metrics, graceful shutdown.
- 12-factor config; `.env.example` committed, real secrets never. `Clock` is a bean (real in prod,
  fixed in tests). All tunables (timeouts, retries, window months, skew) are properties with defaults.

## Deployment  *(D-12 — Render + Neon)*

The service ships to a **free, durable, card-free** target so the build *demonstrably reaches
production*: **Render** (Docker web service) for compute + **Neon** (managed Postgres) for the DB,
**decoupled** so Render's 30-day free-PG deletion never applies. Artifacts (Phase 0):
- **Multi-stage `Dockerfile`** — Gradle/JDK build stage → slim JRE runtime stage (small image, fast
  pull). Render builds it on its side, so no local Docker/JDK is needed to deploy.
- **`render.yaml`** blueprint — declares the web service, health check (`/actuator/health`), and env
  wiring; infra-as-code so the deploy is reproducible and reviewable.
- **`prod` profile → Neon** via `DATABASE_URL` + discrete creds; **two roles** (`migration` = DDL,
  `app` = DML) for least-privilege (§5/§6). Secrets live in Render env, never the repo.
- **Graceful shutdown** + Actuator readiness so Render's 15-min spin-down/restart is clean.

Known trade-off (honest, not hidden): the free web **spins down after 15 min idle** → a **~1-min JVM
cold start** on the next hit. Acceptable for bursty reviewer traffic; removable later via a keep-warm
ping or a **GraalVM native image** (~100 ms boot) — neither changes the topology. Flip to **Cloud Run +
native image** or an **Oracle always-warm VM** if a hot/always-on path or a cold-start SLA appears.

## Stack specifics

- **Java 21**, **Spring Boot 3.x**, `spring.threads.virtual.enabled=true`.
- **Spring Data JDBC** (not JPA) + a `Money` ↔ `NUMERIC(19,2)` converter; **Flyway** plain SQL.
- **springdoc-openapi** generates the OpenAPI 3.1 doc (`/v3/api-docs` + Swagger UI).
- Build with Gradle (or Maven); split source sets `test` (fast) vs `integrationTest` (heavy).

## Build order (full detail in `tasks.md`)

OpenAPI contract → Flyway schema → domain core (Money, RateSelector, validation, CurrencyMap) →
application services → persistence adapter → Treasury adapters A0/A/B/C + resilience → web layer + error
mapping → wiring/E2E → quality gates (PIT, ArchUnit, canary).
