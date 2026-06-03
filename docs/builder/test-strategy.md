# Test strategy — the pyramid, made concrete

> Implements D-11. The JD names the **test pyramid** explicitly, so this is a scored deliverable. Build
> a real pyramid plus orthogonal quality gates. Determinism is non-negotiable. Read alongside
> `rate-selection.md` and `currency-mapping.md` for the fixtures that lock the hard logic.

## Shape & guardrails

- **~70% unit · ~20% component/slice · ~10% integration/E2E**, plus contract/mutation/architecture
  gates. Avoid the **ice-cream-cone** (few unit, many slow E2E). Never test pure logic *through* the
  DB or a live API.
- **Determinism:** inject a fixed `Clock` (no `LocalDate.now()` in code); **zero real network** in the
  gating suite (WireMock + Testcontainers only); named test-data builders / object-mothers.
- **Source sets:** fast `test` (unit + slice) vs heavier `integrationTest` (Testcontainers + WireMock).
- **Stack:** JUnit 5 + AssertJ · WireMock · Testcontainers Postgres · jqwik (property-based) · PIT
  (mutation) · ArchUnit (boundaries) · JaCoCo (coverage floor).

## (1) Unit — the base (pure, no Spring, µs-fast)

**Money / rounding (D-04)**
- `12.34 × 0.853 = 10.52602 → 10.53`.
- **Tie case `0.10 × 0.05 = 0.005 → 0.01`** — locks **HALF_UP** vs HALF_EVEN.
- "Round **once**" — a high-precision rate is not truncated mid-calc.
- Huge amounts (no overflow); `USD` identity (`× 1.00`).
- *jqwik invariants:* output scale always 2; `convert(a, 1) == a.setScale(2)`; monotonic in amount.

**Rate selection (crown jewel — pure fn over candidate rows)**
- Exact match; no-exact → latest `effective_date ≤ purchaseDate`; **6-month boundary inclusive** vs
  floor−1 day (out → empty); empty window → `Optional.empty()`; deterministic tiebreak.
- **Argentina amendment fixture:** `{2025-03-31:1093, 2025-04-15:1230, 2025-06-30:1205}`; purchase
  `2025-05-01` ⇒ **1230**; purchase `2025-07-15` ⇒ **1205** (excludes a future `2025-08-31:1345`).
- **Calendar-month edge:** `2024-08-31 −6mo = 2024-02-29` (leap) vs `2023-08-31 −6mo = 2023-02-28`.

**Validation (D-05/06)**
- description 49 / 50 / 51 **code points** + emoji / control / blank.
- amount `12.34` ok; `12.345` / `0` / `-1` / `1,000` / `1e3` / max+1 reject.
- date `2026-02-30` / `2026-13-01` / future reject (fixed Clock); valid past accepted.

**Currency mapping (D-01)**
- `EUR → Euro Zone-Euro`, `CAD → Canada-Dollar`; **`XOF` / `XAF` → different descriptors** (the guard);
  `USD` → identity; `ZZZ` / lowercase → unsupported / malformed.

## (2) Component / slice — the middle

- **`@WebMvcTest`** (services mocked): POST `400` problem+json with `errors[]` / `201` + `Location`;
  GET conversion `200` shape, `404`, **`422 NO_RATE_AVAILABLE`**, **`422 CURRENCY_UNSUPPORTED`**, `400`
  malformed code. Assert `application/problem+json` + `code` + `traceId`.
- **Persistence slice** on **Testcontainers Postgres**: `NUMERIC(19,2)` **scale preserved**
  (`12.30 ≠ 12.3`); DB `CHECK`s reject `amount ≤ 0` / `description > 50` (defense in depth);
  **idempotency atomicity** (purchase + key one tx; duplicate key → unique violation; concurrent insert
  → one wins); Flyway migrations apply clean.
- **Treasury adapter vs WireMock:** assert the **outgoing** query (`country_currency_desc:eq`,
  `effective_date:lte/gte`, `sort=-effective_date`, `page[size]=1`); parse a **captured real-shaped**
  JSON fixture; empty `data[]` → "no rate" (not exception); **resilience:** 500 → bounded retries →
  circuit opens; fixed-delay → read-timeout mapping; circuit-open → fast `503`; **schema tolerance**
  (unknown field ignored; missing critical field → clear error).

## (3) Integration / E2E — the top, few

`@SpringBootTest`, Testcontainers Postgres + WireMock Treasury wired via `@ServiceConnection` / dynamic
properties, real HTTP. Cover **wiring**, not edge cases:
- golden path (POST → GET EUR incl. rate metadata);
- no-rate → `422`;
- **amendment path** (Argentina) selects `1230`;
- idempotency (same key twice → one record, identical body).

## (4) Contract / drift protection

- A test asserting our parse assumptions against a **captured real Treasury payload** (locks F1/F2
  field names, rate-as-string, date formats) — fails in CI if Treasury's shape drifts.
- A **tagged, non-gating live canary** (`@Tag("live")`, nightly/manual, **never gates PRs**): the live
  endpoint still returns expected fields, **and every entry in the ISO→descriptor map still resolves**
  (catches descriptor retirements — operationalizes D-01).

## (5) Quality-of-tests / architecture (the differentiators)

- **Mutation testing (PIT)** on `money` + `rate-selection` with a score threshold — proves assertions
  *catch* bugs (coverage measures execution; mutation measures assertion strength).
- **ArchUnit:** `domain` must not depend on Spring/web/persistence; adapters depend inward only — keeps
  the `ExchangeRateProvider` seam honest.
- **JaCoCo** thresholds as a **floor/guardrail**, explicitly *not* the quality target (mutation is).

## Requirement traceability (spec-driven)

Maintain an R↔test matrix:
- **R1 store** → validation unit + persistence slice + POST E2E.
- **R2 convert** → rate-selection unit + money unit + adapter slice + conversion E2E.
- **"no rate in 6 months"** → boundary unit + `422` slice + E2E.
- **amendment correctness** → Argentina fixture (unit + E2E).

## CI mapping

- **PR:** `test` (unit + slice) + `integrationTest` (Testcontainers) + ArchUnit, parallelized, fast.
- **Nightly:** mutation (PIT) + live canary.
- Fixtures captured from the live API are committed as **golden files**; `Clock` and the
  `ExchangeRateProvider` port are injectable for testability.
