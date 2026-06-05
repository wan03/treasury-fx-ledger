# WEX Take-Home — Decision Log & Discovery Spec

> **Purpose.** This is the single source of truth for *why* this system is built the way it is.
> It captures decisions, the options weighed, the trade-offs, and — most importantly — the
> **assumptions** underpinning each choice, plus the evidence we gathered during discovery.
> It is intended to be read by whoever implements ("the builder") and by reviewers, and to be
> defensible in conversation. It is a living document: decisions move from `PROPOSED` → `DECIDED`
> as we confirm them, and `OPEN` items are tracked until resolved.

**Project:** Store a USD purchase transaction; retrieve it converted to a target currency using the
U.S. Treasury *Reporting Rates of Exchange* API. Production-grade, Java. Discovery in progress —
**no implementation code yet.**

---

## How to read this

**Status legend**

| Status | Meaning |
|---|---|
| `DECIDED` | Confirmed; the builder should follow this. |
| `PROPOSED` | A recommendation with a stated lean, pending confirmation. Not yet final. |
| `RESEARCHING` | Actively gathering evidence; options still open. |
| `OPEN` | Identified but not yet analyzed. |
| `QUESTION → HM` | Needs a decision/clarification from the hiring manager. |

Each decision records: **Context → Options → Lean/Decision → Rationale → Assumptions → Consequences → Revisit-if.**

---

## 1. Problem summary

Two operations over one domain (money) and one external dependency (Treasury):

- **R1 — Store** a purchase: `description` (≤50 chars), `transactionDate` (valid date), `amount`
  (positive USD, cent precision), assign a unique `id`.
- **R2 — Retrieve converted**: given a stored purchase + target currency, return `id`,
  `description`, `transactionDate`, original USD `amount`, the **exchange rate used**, and the
  **converted amount** (2 dp). Rate must be the one *active on/before the purchase date, within the
  prior 6 months*; if none exists, return an error that the purchase cannot be converted.

The happy path is trivial; the engineering signal is in money handling, rate selection correctness,
resilience to the external dependency, security, and the test strategy.

---

## 2. Verified facts about the Treasury API (evidence base)

Endpoint: `GET https://api.fiscaldata.treasury.gov/services/api/fiscal_service/v1/accounting/od/rates_of_exchange`
(public, **no authentication / no API key**). All findings below were verified against the **live API in June 2026.**

**F1 — Fields.** Each record has: `country`, `currency`, `country_currency_desc`,
`exchange_rate`, `record_date`, `effective_date`.

**F2 — Rate direction & precision.** `exchange_rate` is *foreign-currency units per 1 USD*
(e.g. `Canada-Dollar = 1.393` ⇒ 1 USD → 1.393 CAD). Therefore **`converted = usd_amount ×
exchange_rate`**. Precision is **variable** (observed 2–4 dp: `1.393`, `0.924`, `0.87`,
`1052.5`). ⇒ parse the string into `BigDecimal`; never assume a scale; never use float/double.

**F3 — Cadence.** Rates are **quarterly**; base `record_date`s land on `03-31 / 06-30 / 09-30 /
12-31`. Because quarters are ~3 months apart, a 6-month look-back window will almost always
contain a rate — the "no rate" error path is real but uncommon (very old dates, discontinued or
newly-added currencies).

**F4 — `record_date` ≠ `effective_date` on amendments. ⚠ Load-bearing finding.** Treasury issues
**intra-quarter amended rates** when a currency moves sharply. Evidence (`Argentina-Peso`):

| record_date | effective_date | exchange_rate |
|---|---|---|
| 2025-03-31 | 2025-03-31 | 1093.0 |
| 2025-03-31 | **2025-04-15** | **1230.0** |
| 2025-06-30 | 2025-06-30 | 1205.0 |
| 2025-06-30 | **2025-08-31** | **1345.0** |

So a single `record_date` (quarter) can carry **multiple rows** distinguished by `effective_date`,
and an amendment's `effective_date` can fall **inside a later quarter**. ⇒ The field that means
"the rate is active from this date" is **`effective_date`, not `record_date`.** Selecting purely by
`record_date` would apply a stale/wrong rate to transactions dated after an amendment. (Most stable
currencies never amend, so `record_date == effective_date` for them — but a correct implementation
must handle the amended case.) See **D-02**.

**F5 — Currency keying is country-coupled, not ISO.** The only reliable key is
`country_currency_desc` (e.g. `"Canada-Dollar"`). The bare `currency` field is **not** a usable key
— `"Dollar"` collides across Canada/Australia/Hong Kong/etc. There are **no ISO-4217 codes** in the
dataset. See **D-01**.

**F6 — Multi-country currencies fan out, and the canonical entry can change over time.** `EUR`
appears as **~13+ distinct `country_currency_desc` values** (1,476 rows): `Euro Zone-Euro`,
`Germany-Euro`, `France-Euro`, `Cyprus-Euro`, `Cross Border-Euro`, … Evidence:
- `Germany-Euro`: most-recent `record_date` = **2025-03-31** (68 rows) → **discontinued** after Q1 2025.
- `Euro Zone-Euro`: earliest `record_date` = **2001-03-31**, continuous to present (101 rows).

⇒ `Euro Zone-Euro` is the **complete, continuous, canonical** EUR series; per-country euro rows are
partial historical duplicates (same rate when both exist) that Treasury has been retiring. See **D-01**.

**F7 — The selection rule is fully pushable server-side.** Supported query params let us express the
entire business rule in one request:
`?filter=country_currency_desc:eq:<desc>,effective_date:lte:<purchaseDate>,effective_date:gte:<purchaseDate−6mo>&sort=-effective_date&page[size]=1`
returns **exactly the one active rate** (or an empty set ⇒ our "cannot convert" error). This makes a
fetch-on-demand approach cheap (≈1 row over the wire). See **D-03**.

**F8 — Amendment rule (source-quoted).** Per Treasury / Bureau of the Fiscal Service guidance, quoted
verbatim: *"An amendment to a currency exchange rate for the quarter will appear on the report as a
**separate line with a new effective date**. Amendments made at the end of a month can be used for
reporting purposes for transactions occurring during the **remaining month(s) in the quarter**."* The
published worked example: *a currency amended **April 30** appears on two lines — the original **March 31**
published rate and the amended rate **effective April 30**, the latter valid for reporting **May and
June** transactions.* Amendments are present in the dataset from **March 2021** onward. (The quarter-end
report also reflects rates observed ~1 month prior — e.g. the Dec 31 report reflects rates as of Nov 30.)
⇒ **`effective_date` is the date that governs which rate applies to a transaction** — the authoritative
basis for D-02.

> **Why this beats the literal-brief `record_date` reading.** The two readings return **identical**
> results for every **non-amended** currency (where `record_date == effective_date`). They diverge
> **only** across an amendment — and there `record_date` is *demonstrably wrong*: it can apply a Q1 base
> rate to a post-amendment purchase, or (because an amendment is booked on the quarter it lands in) rank
> a **not-yet-effective** amendment as the latest row. So selecting on `effective_date` is not a
> deviation from correctness; it is a deviation from a *naïve transcription* of the brief's wording.
> Locked by `RateSelectorTest$RateDateBasisReadings` (agree off-amendment; diverge across the Argentina
> Q2→Q3 amendment).
*(Verified via fiscaldata.treasury.gov / fiscal.treasury.gov, June 2026.)*

**F9 — Full currency landscape (latest quarter 2026-03-31: 165 descriptors, 89 currency words).**
Grouping the live dataset shows three distinct classes — which is exactly why the input contract matters:
- **Homonyms** — one `currency` *word* spans many **distinct** ISO currencies: `Dollar` (22 countries),
  `Dinar` (8), `Peso` (8), `Franc` (6), `Rupee` (6), `Pound` (5), `Shilling` (4), `Rial` (3), plus
  2-country `Dirham / Krona / Krone / Riyal / Som`. A *disambiguation* problem, solved cleanly by keying
  on ISO-4217 → a specific descriptor (e.g. `CAD → Canada-Dollar`).
- **Currency unions** — one ISO currency spans many descriptors (the *canonicalization* problem):
  `EUR` → single consolidated `Euro Zone-Euro` (0.87); `XCD` → 3 per-country rows (all 2.7).
  **⚠ Critical trap:** the word `Cfa Franc` spans **two different ISO currencies with different rates** —
  `XOF` (BCEAO: Benin, Burkina Faso, Côte d'Ivoire, Guinea-Bissau, Mali, Niger, Senegal, Togo) = **567.0**,
  and `XAF` (BEAC: Cameroon, CAR, Chad, Congo, Eq. Guinea, Gabon) = **570.79**. Within each union every
  member shares the same rate (pick is value-neutral) but **XOF ≠ XAF**.
- **USD & dollarized economies** — **USD has no self-row.** Dollarized economies appear as proxies, all
  `= 1.0`: `Ecuador-Dolares`, `El Salvador-Dollar`, `Marshall Islands-U.S. Dollar`,
  `Micronesia-U.S. Dollar`, `Palau-Dollar`, `Panama-Dolares`. ⇒ `USD` target must be in-app identity (D-07).
- **Instability over time:** the descriptor set drifts (per-country euros retired after 2025-03-31;
  East Caribbean shrank to 3 members). ⇒ the mapping must be curated data, **verified against the live
  API by an automated test**, and prefer descriptors with continuous history for back-dated purchases.

---

## 3. Open questions for the hiring manager  `RESOLVED — by committed default, pending HM`

The brief invites questions; asking precise ones is part of the deliverable. The draft email lives in
`docs/HIRING_MANAGER_QUESTIONS.md`. **We have committed to a default answer for each so the build is
unblocked**; every default is restated in the submission's *Assumptions* section and remains overridable
if the HM steers otherwise.

1. **Currency input contract** — ISO-4217 + us owning the map, or raw `country_currency_desc`? Euro
   zone-wide rate? → **Resolved: ISO-4217 in + curated map; `EUR → Euro Zone-Euro`.** *(D-01.)*
2. **Which rate date is authoritative** — `record_date` or `effective_date`? Real amendments differ.
   → **Resolved: `effective_date`** (6-month window measured on it too). *(D-02.)*
3. **Expected scale / read pattern** — to right-size the rates strategy. → **Resolved: no single answer —
   build all four adapters and map each to the scale regime it suits** (A0 dev/diagnostic · A
   low/moderate, default · B high-RPS / offline-SLA / own-history · C hybrid at scale). *(D-03, `plan.md`.)*
4. **Amount precision** — reject >2 dp (`400`) or silently round? → **Resolved: reject; never mutate the
   principal.** *(D-05.)*
5. **Future-dated transactions** — allowed at store time? → **Resolved: reject future; accept old (they
   fail at conversion).** *(D-06.)*
6. **`USD` as target currency** — identity or unsupported? → **Resolved: identity (rate `1.00`, no
   upstream call).** *(D-07.)*
7. **AuthN/AuthZ & multi-tenancy** — in scope, or assume an upstream gateway? → **Resolved: assume an
   upstream gateway; leave the seam, don't implement.** *(D-09 assumption.)*

---

## 4. Decisions

### D-01 — Target-currency input contract & multi-country mapping  `DECIDED`
**Context.** Treasury keys on country-coupled `country_currency_desc` (F5); the full landscape (F9) has
**homonyms, unions, and USD-proxies**, and the descriptor set drifts over time.
**Options.** (a) raw Treasury descriptor in — zero mapping, poor country-coupled contract; (b) **ISO-4217
in + curated mapping** — standard, we own the map; (c) hybrid — ISO-4217 over a verified curated set +
precise unsupported error.
**Decision (committed default; pending HM).** **(b)+(c).** Public contract = **ISO-4217**, backed by a **version-controlled
`ISO → country_currency_desc` mapping (data, not code)**, resolved by a three-part policy grounded in F9:
1. **Simple 1:1** — most currencies map to exactly one descriptor; homonyms are disambiguated purely by
   committing to ISO (`CAD → Canada-Dollar`, `AUD → Australia-Dollar`).
2. **Unions** — if a consolidated zone row exists, use it (`EUR → Euro Zone-Euro`); otherwise pick a
   **designated primary** member and document it (`XOF →` a BCEAO member e.g. `Senegal-Cfa Franc`;
   `XAF →` a BEAC member e.g. `Cameroon-Cfa Franc`; `XCD →` e.g. `Antigua & Barbuda-East Caribbean Dollar`).
   The pick is value-neutral within a union (members share a rate) but **must respect XOF ≠ XAF**.
3. **USD** — identity, handled in-app (D-07); never mapped to a dollarized proxy.
**Rationale.** ISO is the consumer-facing standard and isolates Treasury's quirks behind our contract;
the homonym/union/USD distinctions only become tractable once you commit to ISO + a curated map.
Word-keying is **provably unsafe** (`Cfa Franc → XOF/XAF` with different rates, F9).
**Assumptions.** Consumers speak ISO-4217; zone rate intended for EUR; designated-primary acceptable for
XOF/XAF/XCD; supported set is curated, not all 165 descriptors.
**Consequences.** Need a mapping artifact + an automated test asserting every entry resolves on the live
API and (for unions) that within-union members agree; need an `unsupported currency` error; choose union
primaries with continuous history for back-dated purchases.
**Revisit-if.** HM wants raw descriptors ⇒ collapse to (a). HM wants *all* supported currencies ⇒
generate the map from the dataset while keeping the union/primary policy.

### D-02 — Which date governs rate selection (`effective_date`)  `DECIDED`
**Decision.** Select by **`effective_date`**: pick `max(effective_date) ≤ purchaseDate` within the
6-month window (window also measured on `effective_date`).
**Rationale.** F4 + **F8 (source-confirmed)**: an amended rate's `effective_date` is the date from which
it applies to transactions ("used for transactions occurring during the remaining month(s) of the
quarter"). `record_date` only marks the booking quarter and ignores amendments, which would mis-rate
transactions dated after an intra-quarter amendment — e.g. a **2025-05-01** purchase must use the
Argentina amendment effective **2025-04-15 = 1230.0**, not the Q1 base `1093.0` nor the not-yet-effective
Q2 `1205.0`.
**Assumptions.** The 6-month window is measured on `effective_date`. We will **state this explicitly in
the submission's assumptions**, since a naïve reference solution might use `record_date` (identical
logic, different field).
**Consequences.** Query/sort on `effective_date` (F7); any ingest schema indexes `effective_date`; a test
fixture **must** include an amendment (Argentina Q1/Q2 2025) to lock the behavior.
**Both readings are shipped (configurable).** The selection date is a one-line config flip,
`fx.rates.rate-date-basis = effective_date` (default) **or** `record_date` — a `RateDateBasis` strategy
threaded through both the pure `RateSelector` *and* the server-side push-down field (so the query and the
spec stay aligned). This converts the only graded ambiguity from "an undocumented deviation a skim-reader
might dock" into "we implemented **both** readings, default to the provably-correct one, and prove with a
test that they agree except across an amendment." A reviewer who insists on the literal brief reproduces
that reading without a code change. *(See F8 for the source quote; toggle locked by
`RateSelectorTest$RateDateBasisReadings`.)*

### D-03 — Rates acquisition strategy  `DECIDED`
**Context.** Treasury data is public, immutable-historical, quarterly, small; selection is fully
server-side-expressible (F7). Decision is fundamentally about **coupling** vs **operational complexity**.
**Options (full spectrum).**
- **A0 — Pure passthrough.** Call Treasury per request, no cache. Simplest; worst availability/latency coupling.
- **A — On-demand + persistent cache.** One filtered call (F7) → cache the resolved `(currency,
  effective-quarter) → rate`. High hit rate (historical rates never change). Needs timeouts, bounded
  retries (5xx/timeout only), circuit breaker, tolerant parsing.
- **B — Ingest/sync into our own `exchange_rates` table; query locally.** Fully decouples runtime;
  fast indexed local reads; own clean history. Costs: backfill + periodic sync job, staleness handling,
  reconciling amendments.
- **C — Hybrid.** Serve from local store; lazy-populate on miss and/or background-refresh from Treasury.
  Best of both; most moving parts.
- **Cross-cutting:** whichever we pick, hide it behind an **`ExchangeRateProvider` port** so the choice
  is reversible by a single adapter swap.
**Decision.** **Default = A (on-demand + cache)** behind the `ExchangeRateProvider` port; **build all
four variants (A0/A/B/C) as config-selectable adapters** (`fx.rates.provider`). A0 and A share one HTTP
fetcher (A = a cache **decorator** over the fetcher), so both are nearly free. Cache is **in-memory
(Caffeine)** with a **quarter-aware TTL**: settled/past quarters are effectively immutable (cache
long); the **current quarter** uses a short TTL so a late amendment is picked up (F8). Historical-rate
immutability nullifies A's usual staleness weakness, which is what makes A the right default for this
read-mostly, low/moderate-volume workload.

**Scale regime — when each adapter is the right call (answers HM Q7).** **A0** dev/diagnostic or
negligible volume wanting zero cache and always-latest. **A (default)** low/moderate production,
read-heavy with locality — the cache absorbs load and historical immutability keeps it correct. **B**
high-RPS / hot read path, strict offline/availability SLA, Treasury rate limits, or a need for our own
audited rate history — local indexed reads fully decoupled from Treasury. **C** the same high-scale
regime as B but wanting self-healing lazy fill + current-quarter freshness without a full upfront
backfill. Per-adapter detail (with rough rps envelopes) in `plan.md`.
**Assumptions.** Low/moderate conversion volume; some tolerance for Treasury uptime. **Single instance**
(in-memory cache); a multi-instance deployment moves the cache to a shared store (Redis) or runs
`provider=B/C`. ⚠ Hinges on HM Q7 (scale): a hot, high-RPS read path would flip the **default** to B/C.
**Consequences.** Resilience patterns required around the HTTP fetcher; cache key on `(currency,
resolved-quarter)`; B/C require the `exchange_rates` table (D-10) + a sync/reconcile job; a
**provider-parity test** asserts all four return the same rate for a fixture date.
**Revisit-if.** Scale answer; strict offline-runtime SLA; need for our own audited rate history;
Treasury rate limits ⇒ make B or C the default.
**User decision (2026-06):** **build all four variants** (A0/A/B/C), config-selectable, **default A** —
a deliberate demonstration of the port/adapter seam and of explicit, configurable trade-offs. To stay
incrementally shippable on the 5-day clock, implement in order **A0 → A** (shared fetcher + cache
decorator), then **B** (ingest: triggered + scheduled sync over `exchange_rates`, amendment
reconciliation, local indexed selection), then **C** (hybrid: local-first over B with lazy fill +
current-quarter background refresh). The system is fully functional after A; B/C are additive.

**On the "why four providers?" critique (assumption matrix).** The four adapters are **not** four ways to
do one thing — each commits to a *different load-bearing assumption about coupling to Treasury*, and which
assumption holds is a deployment question the brief leaves open. Making them config-selectable is the
honest answer to that openness: pick the posture, don't fork the code.

| Adapter | Assumption it commits to | Pick it when |
|---|---|---|
| **A0** passthrough | Treasury is reachable enough to call on every request; simplicity > decoupling | dev/diagnostic, negligible volume, always-latest, no cache |
| **A** on-demand + cache *(default)* | Historical rates are immutable; only the current quarter can change | low/moderate read-heavy production — the cache absorbs load, immutability keeps it correct |
| **B** ingest | The request path must be **fully decoupled** from Treasury availability/latency | strict offline/availability SLA, high-RPS hot reads, rate limits, or a need for our own audited history |
| **C** hybrid | Same as B, but a full upfront backfill isn't warranted | want local-first + self-healing lazy fill + current-quarter freshness |

One `ExchangeRateProvider` port, one shared fetcher, one shared pure `RateSelector` — the variants differ
only in *where the candidate rows come from*, so the incremental surface per adapter is small and each is
independently tested (plus a provider-parity test pinning them to the same answer). The deliberate breadth
is feasible precisely because the seam is clean; it demonstrates the port/adapter design under real
alternatives rather than asserting it. *(Trade-off accepted: a reviewer optimising purely for minimal
surface area may still prefer a single adapter; we keep the menu and document the assumptions so the choice
is explicit, not implicit.)*

### D-04 — Money representation & rounding  `DECIDED`
**Context.** Java; payments; `converted = usd × rate` then round to 2 dp; rates have variable precision (F2).
**Representation options.** (1) `double`/`float` — **rejected outright** (binary FP can't represent decimal
cents). (2) minor-units `long` (cents) — exact for USD storage but conversion still needs decimal math,
overflow risk, awkward for variable-scale rates. (3) `BigDecimal` — exact decimal, arbitrary precision,
native JDBC `NUMERIC` mapping. (4) `Money` value object (BigDecimal + currency), or JSR-354 / Joda-Money.
**Decision/Lean.** **`BigDecimal` wrapped in a small custom `Money` value object**; persist USD as
`NUMERIC(19,2)`. A heavy money lib is overkill here; the value object gives type-safety (no cross-currency
add), centralizes rounding, documents intent. Compare with `compareTo`, never `equals` (scale-sensitive).
**Rounding policy.** Compute `usdAmount.multiply(rate)` at **full precision**, then
`setScale(2, RoundingMode.HALF_UP)` — **round exactly once, at the end**; never pre-round the rate or
intermediates. Mode = **HALF_UP** (matches plain-English "nearest cent, .5 up" and a likely reference
grader); centralize as one policy constant. *Note:* HALF_EVEN (banker's) would be preferred for a ledger
accumulating many postings (bias control) — not this read-time projection. Output always scale 2.
**Serialization.** Represent money as a **JSON string** (or `{amount, currency}`), not a float, to avoid
client-side float coercion; set Jackson `WRITE_BIGDECIMAL_AS_PLAIN` (no scientific notation).
**Known simplification.** The brief mandates 2 dp for **all** targets — technically wrong for zero-decimal
(JPY, CLP) and three-decimal (BHD, KWD) currencies. We follow the brief (2 dp) and **flag it**; a real
system would use the currency's minor unit (`java.util.Currency.getDefaultFractionDigits`).
**Assumptions.** Input amounts are cent-precision (enforced by D-05, not silently rounded);
`NUMERIC(19,2)` range is ample.
**Consequences.** The `Money` / rounding util is the most heavily unit-tested component (D-11).

### D-05 — Amount input precision & validation  `DECIDED`
**Context.** R1: "valid positive amount rounded to the nearest cent." Two readings — (1) input must already
be cent-precision ⇒ reject more-precise input; (2) the system rounds the input.
**Decision/Lean.** **Reject** input with > 2 decimal places → `400 AMOUNT_PRECISION`. Never silently mutate
the **principal**; the only rounding in the system is the *derived* conversion output (D-04).
**Full amount rules.** decimal **string**; strict pattern `^\d{1,17}(\.\d{1,2})?$`; `> 0` (reject 0/negative
→ `AMOUNT_NOT_POSITIVE`); no thousands separators / symbols / sign / scientific notation; bounded by a sane
business cap (≤ `NUMERIC(19,2)` range; configurable, e.g. 9_999_999_999.99). If numeric JSON is accepted,
parse via `BigDecimal` (`USE_BIG_DECIMAL_FOR_FLOATS`) — but **string is canonical** (D-04).
**Assumption / HM.** Brief's "rounded to nearest cent" *might* intend accept-and-round. **Default = reject.**
If HM wants rounding: HALF_UP **and** echo the normalized amount in the 201 + retain the original submitted
value for audit. *(HM Q3.)*
**Consequences.** Heavily unit-tested: boundary 2 vs 3 dp, `0`, negative, max/overflow, junk strings.

### D-06 — Transaction date validation (format & future dates)  `DECIDED`
**Format.** ISO-8601 **local date** `YYYY-MM-DD` (not datetime/timezone — conversion is date-granular and
Treasury keys on dates). **Strict** parsing (`ResolverStyle.STRICT`) rejects impossible dates (e.g.
`2026-02-30`) and non-ISO formats. Maps to `LocalDate` / `DATE`.
**Future dates.** **Reject** dates after "today" → `400 DATE_IN_FUTURE`. A purchase is a past event; future
dates also yield inconsistent conversions (a near-future date borrows the latest rate; a far-future date
errors). "Today" = current date in a configured business zone (default **UTC**); a small tolerance (e.g.
+1 day / UTC+14) absorbs timezone skew at the boundary — documented & configurable.
**Old dates are NOT rejected at store time.** A too-old purchase is *stored*, and only fails at **conversion**
with `422 NO_RATE_AVAILABLE` (R2's error path). Clean separation: store-time validates *future*;
rate-availability is a *conversion-time* concern. (Optional sanity floor, e.g. year ≥ 1900, to catch typos.)
**Consequences.** Inject a `Clock` for deterministic "today"/"6-months" tests (D-11); boundary tests around
today / tomorrow / skew.

### D-07 — `USD` as target currency  `DECIDED`
**Context.** F9: USD has **no self-row** in Treasury; dollarized economies appear as `1.0` proxies.
**Decision/Lean.** Treat target `USD` as **in-app identity**: `exchangeRate = 1.00`, converted = original,
**no Treasury lookup**; do not resolve via a dollarized proxy descriptor.
**Rationale.** There is no authoritative USD-per-USD row; identity is exact and avoids coupling a trivial
case to the external API; proxies (e.g. `Panama-Dolares`) are semantically wrong keys for USD.
**Assumptions.** USD is an allowed target (HM Q5). **Consequences.** Conversion path special-cases the
base currency before hitting the provider; covered by an explicit test.

### D-08 — Identifier scheme & idempotency  `DECIDED`
**Identifier — DECIDED: server-generated UUIDv7.** **Non-enumerable** externally (no IDOR / volume leakage,
unlike sequential ids) yet **time-ordered** → B-tree index locality and ~chronological sort (better than
random UUIDv4). **App-generated** (not a DB default) so the id is known pre-insert and the code stays
DB-portable. Stored as native `UUID` (16 bytes), not text.
**Idempotency — PROPOSED (mechanics in D-09).** `Idempotency-Key` header → `idempotency_keys` table; the
purchase insert and key insert commit in **one transaction**; the `UNIQUE` PK on the key + conflict handling
makes concurrent duplicate retries safe (loser reads & replays the stored response). TTL cleanup ~24–48h.
**Revisit-if.** A client-supplied-id requirement ⇒ accept + validate the UUID and dedup on it.

### D-09 — API contract & resource design  `DECIDED`
**Style.** REST/JSON; URI versioning `/v1`; success `application/json`, errors `application/problem+json`
(RFC 9457); ISO-8601 dates; money & rates as **strings** (D-04); UTF-8.

**Resource model.** A **Purchase** (stored, immutable financial record) and a **Conversion** (a *computed
projection* of a purchase into a target currency — never persisted).

| Method & path | Purpose | Success | Safe/Idempotent |
|---|---|---|---|
| `POST /v1/purchases` | Create a purchase | 201 + `Location` | No → use `Idempotency-Key` |
| `GET /v1/purchases/{id}` | Fetch stored purchase (USD) | 200 | Yes |
| `GET /v1/purchases/{id}/conversions/{currencyCode}` | Converted view + rate metadata | 200 | Yes |

- **Conversion = path sub-resource keyed by ISO-4217** (`…/conversions/EUR`) — addressable & cache-friendly
  (clean key, no query-string normalization); chosen over `?targetCurrency=` (Option B, refined).
- **No `PUT`/`PATCH`/`DELETE` on purchases** — deliberate: financial transactions are **append-only /
  immutable / auditable**; corrections are modeled as new reversing records, never in-place edits.
- `GET /v1/purchases` (list) **out of scope** (brief doesn't require it); if added → cursor pagination.

**POST /v1/purchases — request**
```json
{ "description": "Office supplies", "transactionDate": "2026-03-15", "amount": "12.34" }
```
- `description` — required; trimmed; 1–50 **Unicode code points** (definition documented); control chars rejected.
- `transactionDate` — ISO-8601 local date `YYYY-MM-DD`; future-date policy per D-06.
- `amount` — decimal **string**; `> 0`; **≤ 2 dp → reject, don't round (D-05)**; no separators/symbols; bounded.
- `currency` — optional, defaults `USD`; any non-USD ⇒ 422 (only USD stored).
- Optional header **`Idempotency-Key`** (opaque, ≤255 chars) for safe retries.

**POST response — 201**, `Location: /v1/purchases/{id}`:
```json
{ "id": "<uuidv7>", "description": "Office supplies", "transactionDate": "2026-03-15",
  "amount": "12.34", "currency": "USD", "createdAt": "2026-06-02T09:35:12Z" }
```

**GET conversion — 200** (`…/conversions/EUR`):
```json
{ "purchaseId": "<uuidv7>", "description": "Office supplies", "transactionDate": "2026-03-15",
  "originalAmount": "12.34", "originalCurrency": "USD", "targetCurrency": "EUR",
  "exchangeRate": "0.924", "rateEffectiveDate": "2025-03-31", "convertedAmount": "11.40",
  "rateSource": "U.S. Treasury Reporting Rates of Exchange" }
```
Returns the brief's required fields **plus `rateEffectiveDate` + `rateSource`** for auditability/reproducibility.
`USD` target ⇒ identity (rate `1.00`, no upstream call — D-07).

**Status codes (deliberate).** `200/201` success · `400` malformed/validation (field-level details) ·
`404` purchase not found · `422` well-formed-but-unfulfillable: (a) **no rate within 6 months** (brief's
required error, `NO_RATE_AVAILABLE`); (b) ISO-valid but **unsupported** currency (`CURRENCY_UNSUPPORTED`) ·
`400` for a **malformed** currency token (not `^[A-Z]{3}$`) · `409` `Idempotency-Key` reused with a
different payload · `429` rate-limited · `502/503/504` Treasury upstream failure / circuit-open
(`503` + `Retry-After`) · `500` unexpected.

**Error shape (RFC 9457 + extensions).**
```json
{ "type": "https://api.example.com/problems/no-rate-available", "title": "No exchange rate available",
  "status": 422, "code": "NO_RATE_AVAILABLE",
  "detail": "No USD→EUR rate published on or within 6 months before 2026-03-15.",
  "instance": "/v1/purchases/<id>/conversions/EUR", "traceId": "..." }
```
Machine-readable `code`, human `detail`, `traceId` for support; validation errors carry an `errors[]` array.

**Idempotency (POST).** `Idempotency-Key` → persist `{key, requestHash, response}` with a TTL (e.g. 24h).
Same key + same hash ⇒ **replay** stored 201; same key + different hash ⇒ **409**. Rationale: two identical
purchases are legitimately distinct (no natural dedup), so safe retries require an explicit key. (Ties D-08.)

**Caching.** Past-dated conversions are (near-)deterministic ⇒ `Cache-Control: public, max-age=…` +
`ETag`/`If-None-Match`. **Not** `immutable`: recent quarters can gain an amendment (F8) and the mapping can
change — moderate TTL, not infinite. Purchases carry an `ETag`.

**Security.** TLS/HSTS; **no amounts or PII (description) in URLs or logs** (log ids/traceId only);
request-size limits; `currencyCode` regex-validated; AuthN/AuthZ assumed at the gateway (OAuth2 bearer /
internal mTLS) — out of scope but slotted; deny-by-default CORS; optional rate limiting (429).

**Spec-driven.** Ship an **OpenAPI 3.1** contract (springdoc-generated, served at `/v3/api-docs` + Swagger
UI) as the source of truth for schemas — enabling contract tests and matching the JD's "spec-driven
development." *(Materialize the OpenAPI doc as the first build artifact.)*

**Assumptions.** Single-tenant; auth upstream; USD-only storage; no list/search.
**Revisit-if.** Multi-tenancy (scope ids per tenant), bulk conversion (`…/conversions?targets=EUR,GBP`),
or webhooks/data-streams (JD mentions them) come into scope.

### D-10 — Persistence & local-dev experience  `DECIDED`
**Datastore — PostgreSQL.** ACID + exact `NUMERIC` decimals + strong constraints + relational integrity suit
financial data; ubiquitous on AWS (RDS/Aurora) & Azure; great local Docker story; native `uuid`,
`timestamptz`, `JSONB`. (NoSQL / in-memory rejected: we need transactions, constraints, and prod parity.)
**Schema (Flyway-managed).**
- `purchases(id UUID PK, description VARCHAR(50) NOT NULL CHECK(char_length(description) BETWEEN 1 AND 50),
  transaction_date DATE NOT NULL, amount NUMERIC(19,2) NOT NULL CHECK(amount > 0),
  currency CHAR(3) NOT NULL DEFAULT 'USD' CHECK(currency='USD'), created_at TIMESTAMPTZ NOT NULL DEFAULT now())`
  — **append-only** (no `updated_at`); DB constraints **mirror** app validation (defense in depth).
- `idempotency_keys(key VARCHAR(255) PK, request_hash CHAR(64) NOT NULL,
  purchase_id UUID NOT NULL REFERENCES purchases(id), response_status SMALLINT, response_body JSONB,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(), expires_at TIMESTAMPTZ)` — exact replay + TTL sweep.
- `exchange_rates(...)` — **only for the rates Approach-B adapter** (D-03); index `(country_currency_desc,
  effective_date)`; migration kept modular so the A/cache adapter doesn't require it.
**Migrations — Flyway (plain SQL).** Versioned, transparent (reviewers read the DDL), runs identically on
startup, in Testcontainers, and in prod. **Least-privilege:** a `migration` DB user (DDL) distinct from the
`app` user (DML only).
**Data access — Spring Data JDBC** (explicit SQL, no lazy-loading footguns, fast startup) over JPA/Hibernate
(overkill for one immutable aggregate) or JOOQ (codegen overhead). Custom converter maps the `Money` value
object ↔ `NUMERIC`.
**Local-dev DX (brief's explicit ask).**
- **One command up:** `spring-boot-docker-compose` auto-starts Postgres on `bootRun` (a dev just runs the
  app), or Testcontainers-at-dev (`@ServiceConnection`) for an ephemeral DB with zero local install.
- **Seed data** via a dev-profile seeder / repeatable migration so the API is explorable immediately.
- **Profiles:** `dev` (compose, Swagger UI, verbose), `test` (Testcontainers), `prod` (RDS, secrets from
  env / secret-manager, Swagger gated). 12-factor config; `.env.example`; no secrets in repo.
- **Makefile** targets `make dev / test / build / db-migrate / clean`; README quickstart (JDK + Docker → one command).
- **Java 21 LTS, Spring Boot 3.x**; **virtual threads** (`spring.threads.virtual.enabled=true`) for the
  blocking Treasury IO (cheap concurrency).
**Prod-readiness (parity, not compromise).** Same Postgres dialect + Flyway everywhere (**no H2** — dialect
drift hides NUMERIC bugs); Actuator health (liveness/readiness) + Micrometer/Prometheus metrics + structured
JSON logs + graceful shutdown; HikariCP pool (sized); TLS + encryption-at-rest (KMS) + automated backups/PITR
on RDS (infra-side, noted).
**Transactions.** POST create = single tx inserting purchase + idempotency row atomically (retry-safe); reads
are read-only.
**Consequences.** The schema + Flyway baseline are the first build artifacts after the OpenAPI contract.

### D-11 — Test strategy (the pyramid, made concrete)  `DECIDED`
**Shape.** A real pyramid — ~70% pure unit, ~20% component/slice, ~10% integration/E2E — plus orthogonal
quality gates (contract, mutation, architecture). Explicitly avoid the **ice-cream-cone** anti-pattern
(few unit, many slow E2E) and never test pure logic *through* the DB or a live external API.
**Determinism.** Inject a fixed `Clock` (no `LocalDate.now()` in code); **zero real network** in the gating
suite (WireMock + Testcontainers only); test-data builders / object-mothers with named fixtures.
**Stack.** JUnit 5 + AssertJ; WireMock (Treasury); Testcontainers Postgres; jqwik (property-based);
PIT (mutation); ArchUnit (boundaries); JaCoCo (coverage floor). Split source sets: fast `test` vs
heavier `integrationTest`.

**(1) Unit — base, the majority (pure, no Spring, µs-fast).**
- **Money / rounding (D-04):** `12.34 × 0.853 = 10.52602 → 10.53`; tie case `0.10 × 0.05 = 0.005 → 0.01`
  **locks HALF_UP vs HALF_EVEN**; "round **once**" (high-precision rate not truncated mid-calc); huge amounts
  (no overflow); USD identity (`×1.00`). *jqwik invariants:* output scale always 2; `convert(a,1)=a.setScale(2)`;
  monotonic in amount.
- **Rate selection (crown jewel — pure fn over candidate rows):** exact match; no-exact → latest
  `effective_date ≤ purchaseDate`; **6-month boundary inclusive** (floor date in) vs floor−1 day (out → error);
  empty window → `Optional.empty()`; deterministic tiebreak on equal `effective_date`. **Showcase fixtures
  (real data):** *Argentina amendment* — candidates `{2025-03-31:1093, 2025-04-15:1230, 2025-06-30:1205}`,
  purchase `2025-05-01` ⇒ **1230** (not 1093, not 1205); purchase `2025-07-15` ⇒ **1205** (excludes future
  amendment `2025-08-31:1345`). **Calendar-month edge:** `2024-08-31 −6mo = 2024-02-29` (leap) vs
  `2023-08-31 −6mo = 2023-02-28`.
- **Validation (D-05/06):** description 49/50/51 **code points** + emoji/control/blank; amount `12.34` ok,
  `12.345`/`0`/`-1`/`1,000`/`1e3`/max+1 reject; date `2026-02-30`/`2026-13-01`/future reject (fixed Clock).
- **Currency mapping (D-01):** `EUR→Euro Zone-Euro`, `CAD→Canada-Dollar`, `XOF`/`XAF` → **different**
  descriptors (the XOF≠XAF guard), `USD`→identity, `ZZZ`/lowercase → unsupported/format error.

**(2) Component / slice — middle.**
- `@WebMvcTest` (mocked services): POST 400 problem+json with `errors[]` / 201 + `Location`; GET conversion
  200 shape, 404, **422 NO_RATE_AVAILABLE**, **422 CURRENCY_UNSUPPORTED**, 400 malformed code; assert
  `application/problem+json` + `code` + `traceId`.
- Persistence slice on **Testcontainers Postgres**: NUMERIC(19,2) **scale preserved** (`12.30` ≠ `12.3`);
  DB `CHECK`s reject `amount ≤ 0` / `description > 50` (defense-in-depth); **idempotency atomicity** (purchase
  + key one tx; duplicate key → unique violation; concurrent insert → one wins); Flyway migrations apply clean.
- Treasury adapter vs **WireMock**: asserts the **outgoing** query (`country_currency_desc:eq`,
  `effective_date:lte/gte`, `sort=-effective_date`, `page[size]=1`); parses a **captured real-shaped** JSON
  fixture; empty `data[]` → "no rate" (not exception); **resilience:** 500 → bounded retries → circuit-opens;
  fixed-delay → read-timeout mapping; circuit-open → fast 503; **schema tolerance** (unknown field ignored,
  missing critical field → clear error).

**(3) Integration / E2E — top, few.** `@SpringBootTest`, Testcontainers Postgres + WireMock Treasury wired
via `@ServiceConnection`/dynamic props, real HTTP: golden path (POST→GET EUR incl. rate metadata); no-rate →
422; **amendment path** (Argentina) selects 1230; idempotency (same key twice → one record, identical body).
Cover **wiring**, not edge cases.

**(4) Contract / drift protection.** A test asserting our parse assumptions against a **captured real Treasury
payload** (locks F1/F2 field names, rate-as-string, date formats) — fails in CI if Treasury's shape drifts.
Plus a **tagged, non-gating canary** (`@Tag("live")`, nightly/manual, **never gates PRs**): the live endpoint
still returns expected fields **and every entry in our ISO→descriptor map still resolves** (catches descriptor
retirements like the per-country euros / East-Caribbean shrink — operationalizes D-01).

**(5) Quality-of-tests / architecture (the differentiators).**
- **Mutation testing (PIT)** on `money` + `rate-selection` packages with a score threshold — proves assertions
  *catch* bugs (coverage measures execution; mutation measures assertion strength).
- **ArchUnit:** domain must not depend on Spring/web/persistence; adapters depend inward only — keeps the
  swappable `ExchangeRateProvider` seam (D-03) honest.
- **JaCoCo** thresholds as a **floor/guardrail**, explicitly *not* the quality target (mutation score is).

**Requirement traceability (spec-driven).** Maintain an R↔test map: R1 store → validation unit + persistence
slice + POST E2E; R2 convert → rate-selection unit + money unit + adapter slice + conversion E2E;
"no rate in 6 months" → boundary unit + 422 slice + E2E; amendment correctness → Argentina fixture (unit+E2E).

**CI mapping.** PR: `test` (unit+slice) + `integrationTest` (Testcontainers) + ArchUnit, parallelized,
fast feedback. Nightly: mutation + live canary. **Consequences.** Fixtures captured from live API are
committed as golden files; `Clock` and the `ExchangeRateProvider` port are injectable for testability.

---

### D-12 — Deployment target & runtime topology  `DECIDED`
**Context.** This is a take-home, but we want to *demonstrate reaching production* on a free, durable
host — a live, shareable HTTPS URL. The app is a Spring Boot (JVM) service that **requires a real
PostgreSQL** (no H2; prod-parity per D-10). The 2026 free-tier landscape was verified live: every free
*compute* tier scales to zero (JVM cold start is felt) and bundles a **booby-trapped Postgres** —
Render's free PG **deletes 30 days** after creation, Koyeb's allows **5 active-hours/month**, Supabase
**pauses after 7 days** idle.
**Options.** (a) **Cloud Run + Neon**, GraalVM native image — truly free, container-native, ~100 ms cold
start, strongest production story; cost: GCP card on file, stricter native build. (b) **Render (Docker)
+ Neon** — *no credit card*, fastest path to a live URL, single dashboard; cost: free web spins down
after 15-min idle (~1-min JVM cold start). (c) **Oracle Always Free ARM VM** (Docker Compose:
app + Postgres + Caddy TLS) — always-warm (no cold start), 24 GB, full control; cost: card + account-
approval friction, idle-reclaim caveat, most ops. (d) Fly.io / Railway — no longer meaningfully free
(trial-only) — rejected.
**Decision.** **Render (Docker web service) + Neon (managed Postgres).** Decouple compute from the DB:
**Neon is the database on every path** (0.5 GB, scale-to-zero, branching, no card, *no expiry*), which
sidesteps Render's 30-day free-PG deletion.
**Rationale.** Optimizes for the take-home's real goal — a genuinely-free, low-friction, shareable live
URL with **no credit card** — over raw production polish. Docker deploy preserves prod-parity with our
Testcontainers/Compose dev story (same image shape). Render builds the image from the repo Dockerfile on
*its* infrastructure, so shipping needs no local Docker/JDK.
**Assumptions.** Reviewer traffic is bursty/low → the 15-min spin-down is acceptable and the ~1-min cold
start is **disclosed, not hidden**. Neon free (0.5 GB, 100 compute-hrs/mo) comfortably covers demo load.
Secrets (Neon connection string, `app`/`migration` DB creds) come from Render env vars / Neon — never the
repo (constitution §5).
**Consequences.** Phase 0 gains: a **multi-stage `Dockerfile`** (build → slim JRE runtime), a
**`render.yaml`** blueprint (web service + env wiring), a `prod` profile pointed at Neon via
`DATABASE_URL`/discrete creds, Actuator `/health` as Render's health check, and graceful shutdown for
clean spin-down. Two Neon roles (`migration` = DDL, `app` = DML) honor least-privilege (§5/§6).
**Revisit-if.** A hot/always-on path or a strict cold-start SLA appears → switch to **Cloud Run + native
image** (already analyzed) or the **Oracle always-warm VM**; the Docker artifact ports to either
unchanged. Cold start, if it ever matters for the demo, is removed by a keep-warm ping or a GraalVM
native image without changing the topology.

---

### D-13 — Serve the interactive explorer as the app's front door (`/`) under an all-`'self'` CSP  `DECIDED`
**Context.** The submission ships an interactive explorer (`explore.html` + tour/playground UI) that tours
the codebase and exercises the live API. To make it the *recommended* way in — and to let its **Live App**
tab call the API **same-origin (no CORS)** — we serve it from the Spring app and forward `GET /` to it. But
the app's security posture (constitution §9) sets a deliberately strict, JSON-only `Content-Security-Policy`
(`default-src 'none'`) that is right for an API and **fatal to a real HTML page** — it blocks script, style
and every `fetch`. Serving an HTML page therefore forces a CSP decision on a payments service, where *any*
relaxation deserves scrutiny. **A constraint changed mid-flight:** the page started life as a *single
self-contained file* (inline HTML/CSS/JS, opens offline by double-click) and that portability was treated as
load-bearing. Once it became the **deployed front door** — reached over HTTP far more often than from disk —
the offline-single-file property stopped being worth a security concession, and this decision was revised
(see Changelog) to the strictest policy that still works.
**Options.**
(a) **Don't serve it from the app** — keep the API origin pure; the explorer stays a local/offline file or
a separately-hosted static page (GitHub Pages / CDN). Cleanest API; loses the same-origin playground and
the "open the root and explore" affordance for the reviewer.
(b) **Serve it; relax the CSP globally** — simplest, **rejected**: it would weaken the `/v1` data plane.
(c) **Serve it; per-surface CSP, keep the single file via `'unsafe-inline'`** — strict `default-src 'none'`
stays on `/v1/**`; `/` and `/explore.html` get `script-src/style-src 'self' 'unsafe-inline'` + `img-src
'self' data:`. This was the **interim** choice while the single-file property was held sacrosanct; it leaves
an inline-execution escape hatch on the page (tolerable only because the page has no auth/cookies/session and
escapes on write — but still a hatch). **Superseded by (e).**
(d) **Serve it under a nonce/hash CSP** to avoid `'unsafe-inline'`. A **nonce** needs per-response server
injection → turns the static page into a server-rendered template (and still wouldn't cover inline
`style="…"` *attributes*, which hashes don't apply to). A **script hash** is feasible (the page uses
`addEventListener` only — **no inline `on*` handlers**) but brittle: a later edit silently blanks the page
unless a pinning test guards it. More machinery than (e) for no extra safety.
(e) **De-inline: split the assets into sibling same-origin files** (`explore.js`, `explore.css`,
`favicon.svg`), convert the page's static inline `style="…"` attributes to utility CSS classes, and ship a
real icon — so a pure `'self'` policy with **no `'unsafe-inline'`, no `data:`** suffices. Cost: the artifact
is no longer one emailable/offline file (it is now 4 sibling files served same-origin). ← **chosen.**
**Decision.** Option (e). `SecurityHeadersFilter` applies CSP per path: `/v1/**` →
`default-src 'none'; frame-ancestors 'none'` (unchanged); `/` and `/explore.html` →
`default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'`;
every other path (Swagger) → no CSP. Baseline HSTS / `nosniff` / `X-Frame-Options: DENY` /
`Referrer-Policy: no-referrer` stay on **every** response. `WebConfig.addViewControllers` forwards
`/` → `/explore.html`. The page loads `explore.css` / `explore.js` / `favicon.svg` as same-origin siblings;
there is no inline `<script>`, no inline `<style>`, and no inline `style=` attribute left on the page (the
JS still uses CSSOM `el.style.x = …` for *dynamic geometry*, which CSP does **not** govern — only HTML
`style=` attributes and `<style>`/`<script>` blocks do).
**Rationale.** The page now runs under the **same family of policy as the data plane** — everything is
`'self'`, the only difference from `/v1` being the asset/`fetch` allowances a real document genuinely needs.
There is **no inline-execution escape hatch at all**: even if `escapeHtml` were someday missed on an
`innerHTML` write, injected `<script>`/inline handlers cannot run under `script-src 'self'`, and injected
inline `style` cannot apply under `style-src 'self'`. So the CSP is a true second line of defence rather
than a policy that merely *documents* trust in the page's own escaping. `connect-src 'self'` keeps the
playground's `fetch` same-origin only; `base-uri`/`form-action 'none'` and `frame-ancestors 'none'` close
the remaining injection/clickjacking vectors. We pay for this with the single-file property — acceptable
because the page's primary delivery is now the deployed origin, not a file on disk, and the 4-file layout is
still trivially static (no build step, no dependencies).
**Assumptions.** The deployed instance *is* the demo, so the explorer is served in **all** profiles
(incl. `prod`) — there is no separate marketing host to gate it to. Reviewer traffic is low/bursty (D-12),
so serving a few static files from the API process is fine. The page stays escape-on-write; the hardened CSP
is now a backstop for that, not a substitute.
**Consequences.** Two tests pin the behavior: `SecurityHeadersFilterTest` asserts the **per-path CSP
matrix** (strict on `/v1`, all-`'self'`-with-**no-`unsafe-inline`/no-`data:`** on the page, none on Swagger,
baseline headers everywhere) and `PurchaseConversionE2EIT#home_servesInteractiveExplorer_…` asserts `GET /`
end-to-end (200, `text/html`, hardened CSP) **and** that `/explore.js` + `/explore.css` are actually served
same-origin (so the CSP is satisfiable, not aspirational). The `/v1` strict-CSP slice assertion is unchanged.
A `noindex,nofollow` meta keeps the deployed demo out of search.
**Revisit-if.** For a **real production** payments service, serve the explorer from a **separate static host
/ CDN** (or exclude it from the prod image) and keep the API origin pure — the four static files port
unchanged. If the page ever needs a third-party asset, prefer **SRI-pinned `'self'`-hosted copies** over
widening the policy. The single-file artifact, if ever wanted again (e.g. an offline hand-off), is a trivial
re-inline — but it would re-introduce `'unsafe-inline'`, so it should stay a separate, clearly-labelled
build, never the served page.

---

## 5. Glossary

- **`record_date`** — the quarter-end date a rate is recorded against (base cadence; F3).
- **`effective_date`** — the date a rate becomes active; equals `record_date` except for intra-quarter
  amendments (F4). Authoritative for selection per D-02.
- **`country_currency_desc`** — Treasury's country-coupled currency key, e.g. `"Euro Zone-Euro"` (F5).
- **Amendment** — an extra row sharing a `record_date` with a distinct `effective_date` and rate (F4).
- **6-month window** — `[purchaseDate − 6 months, purchaseDate]` on the selection date (D-02); calendar-month arithmetic.

---

## Changelog
- *2026-06 (discovery):* Created. Verified API facts F1–F7 against live API. Logged D-01..D-11;
  D-01/D-02/D-03 advanced with evidence. Open HM questions captured.
- *2026-06 (discovery, cont.):* Added F8 (amendment rule, source-confirmed) and F9 (full currency
  landscape: homonyms / unions / USD-proxies; `Cfa Franc → XOF≠XAF` trap; USD has no self-row).
  **D-02 → DECIDED** (`effective_date`). **D-04, D-07 → PROPOSED.** Expanded D-01 with the three-part
  mapping policy. D-03 deferred (user wants config-selectable adapter variants — see note).
- *2026-06 (discovery, cont.):* **D-09 → PROPOSED.** Full API contract: conversion as ISO-keyed path
  sub-resource (`…/conversions/{code}`); purchases immutable (no PUT/PATCH/DELETE); RFC 9457 error model
  with machine `code` + `traceId`; status-code semantics (422 for no-rate/unsupported); `Idempotency-Key`
  for safe POST retries; conditional caching with the amendment caveat; OpenAPI 3.1 as source of truth.
- *2026-06 (foundations):* Closed validation calls — **D-05 → PROPOSED** (reject >2 dp, full amount rules),
  **D-06 → PROPOSED** (ISO local-date, reject future, store old / fail at convert). **D-08 id → DECIDED**
  (UUIDv7). **D-10 → PROPOSED** (Postgres + Flyway + Spring Data JDBC; schema for purchases / idempotency_keys
  / optional exchange_rates; one-command DX via docker-compose/Testcontainers; profiles; Java 21 + virtual
  threads; least-privilege DB users; no-H2 parity). **Next: D-11 testing.**
- *2026-06 (foundations, cont.):* **D-11 → PROPOSED.** Concrete pyramid (~70/20/10) + quality gates: unit
  base (money/rounding, rate-selection with the **Argentina amendment** & calendar-month fixtures, validation,
  mapping incl. XOF≠XAF); WebMvc/persistence/adapter slices (WireMock + Testcontainers); thin E2E; captured-
  payload contract test + **non-gating live canary** verifying every mapping entry resolves; **PIT mutation**,
  **ArchUnit** boundaries, jqwik properties, JaCoCo floor; R↔test traceability; CI split (PR vs nightly).
- *2026-06 (builder harness):* Authored the **Spec-Driven builder harness** (separate from this rationale
  log). Entry point **`AGENTS.md`** (open standard; minimal always-on root + golden rules + doc map) with a
  one-line `CLAUDE.md` discovery pointer. Detail units under **`docs/builder/`**: `constitution.md`,
  `spec.md` (R1/R2 acceptance criteria), `plan.md` (hexagonal architecture + `ExchangeRateProvider` seam),
  `data-model.md`, `api-contract.md`, `rate-selection.md`, `currency-mapping.md`, `test-strategy.md`,
  `tasks.md` (ordered, gated build plan). Design choices: Spec-Kit-hybrid vocabulary, progressive disclosure
  (thin root, load-on-demand), fine-grained units. **This log remains the single source of *why*; the harness
  is the *what/how* and links back here — no rationale duplicated.** Still **no implementation code**; the
  build plan's Phase 0 is explicitly gated on the user's green light.
- *2026-06 (rates default):* **D-03 → DECIDED.** Default = **A (on-demand + cache)**; **build all four**
  variants (A0/A/B/C) config-selectable behind the `ExchangeRateProvider` port. Cache = in-memory Caffeine
  with a quarter-aware TTL (settled quarters effectively immutable; current quarter short TTL for
  amendments). Build order A0→A→B→C (shippable after A; B/C additive). Documented flip triggers
  (high-RPS / offline SLA / own audited history / Treasury rate limits) and the single- vs multi-instance
  cache note. Propagated to `plan.md`, `tasks.md` (Phase 4 expanded to A0/A/B/C + provider-parity test),
  and `data-model.md` (`exchange_rates` now in-scope for B/C).
- *2026-06 (clarifications resolved by default):* Committed to a default answer for all seven HM
  questions so the build is unblocked (each restated as an assumption in the submission, overridable on
  HM input). **D-01 → DECIDED** (ISO-4217 + curated map; `EUR→Euro Zone-Euro`). **D-02** confirmed
  (`effective_date`). **Scale:** no single answer — added **per-adapter scale-regime guidance** (A0
  dev/diagnostic · A low/moderate, default · B high-RPS / offline-SLA / own-history · C hybrid at scale)
  to **D-03** and `plan.md`. **D-05 → DECIDED** (reject >2 dp). **D-06 → DECIDED** (reject future /
  accept old). **D-07 → DECIDED** (USD identity). **Auth** confirmed assumed at an upstream gateway
  (**D-09** assumption). Email saved to `docs/HIRING_MANAGER_QUESTIONS.md`; §3 annotated with resolutions.
- *2026-06 (ledger green; build green-lit):* Confirmed the remaining authored recommendations —
  **D-04, D-08 (idempotency), D-09, D-10, D-11 → DECIDED**. Design phase complete (all D-01..D-11
  DECIDED); implementation green-lit, beginning at Phase 0 (contract-first).
- *2026-06 (deployment target):* **D-12 → DECIDED.** Free-tier landscape verified live (Render / Fly /
  Koyeb / Cloud Run / Oracle compute; Neon / Supabase Postgres). Chose **Render (Docker) + Neon**,
  decoupling compute from a durable DB (avoids Render free-PG's 30-day deletion; no credit card).
  Trade-off accepted: 15-min spin-down → ~1-min JVM cold start, mitigable later via keep-warm or a
  GraalVM native image. Phase 0 gains a multi-stage `Dockerfile`, `render.yaml`, and a Neon-backed
  `prod` profile with two least-privilege DB roles.
- *2026-06 (presentation layer):* **D-13 → DECIDED.** Serve the self-contained interactive explorer as
  the app's front door (`GET /` → `forward:/explore.html`) so its Live App tab is same-origin (no CORS).
  Tailor CSP per surface — strict `default-src 'none'` on `/v1`, a minimal bounded relaxation on `/` and
  `/explore.html` (inline assets + `connect-src 'self'`), none on Swagger; baseline security headers
  unchanged. Recorded the core trade-off (single-file portability vs `'unsafe-inline'`) and the stricter
  alternatives weighed (nonce/hash CSP, asset-split, and — for real prod — a separate static host). Pinned
  by `SecurityHeadersFilterTest` (per-path CSP matrix) and `PurchaseConversionE2EIT` (`GET /` round-trip).
- *2026-06 (presentation layer, hardened):* **D-13 revised — option (c) → (e).** Now that the explorer is
  the *deployed* front door (reached over HTTP, not from disk), the single-self-contained-file property is
  no longer worth a security concession, so it was **dropped deliberately** to buy a strictly stronger CSP.
  De-inlined the page into same-origin siblings (`explore.js`, `explore.css`, `favicon.svg`) and converted
  all static inline `style=` attributes to utility CSS classes, so the page now runs under
  `script-src 'self'; style-src 'self'; img-src 'self'` with **no `'unsafe-inline'` and no `data:`** — the
  inline-execution escape hatch is gone, making the CSP a real backstop rather than a documented trust in
  the page's own escaping. `/v1` strict policy and the baseline headers are unchanged. Tests updated to
  assert the hardened policy (no `unsafe-inline`/`data:`) and that the de-inlined assets are actually served
  same-origin. The single-file artifact remains a trivial (separate, clearly-labelled) re-inline if ever
  wanted for offline hand-off — but never the served page.
