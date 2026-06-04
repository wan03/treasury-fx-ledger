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

## Requirement traceability (R↔test matrix)

Every acceptance criterion in `spec.md` maps to ≥1 automated, deterministic test. Layer key: **U**nit
(`src/test`, pure), **Sl**ice (`@WebMvcTest`), **App** (use-case, mocked ports), **IT** (Testcontainers
+ WireMock), **E2E** (`@SpringBootTest` random-port), **Canary** (`@Tag("live")`, non-gating).

| AC | What it locks | Tests (`class#method`) | Layers |
|---|---|---|---|
| AC-1.1 | UUIDv7 id; exact cent scale (`12.30`≠`12.3`) | `StorePurchaseServiceTest#stores_a_purchase_with_server_id_usd_and_the_clock_timestamp`; `MoneyTest#normalizes_to_scale_2_padding_a_cent_precise_principal`; `StorePurchasePersistenceIT#service_stores_a_purchase_and_it_round_trips_with_scale_and_timestamp_intact`; `PurchasePersistenceIT#numeric_scale_is_preserved_on_round_trip` | App·U·IT |
| AC-1.2 | `201` + `Location` + echoed fields + `createdAt` | `PurchaseControllerTest#create_returns201_withLocation_andMoneyAsString`; `PurchaseConversionE2EIT#goldenPath_store_thenConvertToEur` | Sl·E2E |
| AC-1.3 | desc >50 → `400`; 50 ok; counts code points | `PurchaseValidatorTest#description_over_50_code_points_is_rejected` / `#description_at_or_below_50_code_points_is_accepted_and_trimmed` / `#description_counts_code_points_not_utf16_chars`; `PurchaseControllerTest#create_validationFailure_returns400_withErrorsArray` | U·Sl |
| AC-1.4 | >2dp reject-not-round; ≤0 reject; 2dp ok | `PurchaseValidatorTest#more_than_two_decimals_is_rejected_not_rounded` / `#zero_and_negative_amounts_are_not_positive` / `#separators_symbols_sign_sci_notation_and_over_cap_are_malformed` / `#valid_amounts_are_accepted_with_value_preserved` | U |
| AC-1.5 | impossible/non-ISO/future date reject; past ok | `PurchaseValidatorTest#impossible_and_non_iso_dates_are_invalid` / `#future_dates_are_rejected` / `#valid_past_and_today_dates_are_accepted` | U |
| AC-1.6 | append-only (no update/delete) | `PurchaseControllerTest#put_isMethodNotAllowed_provingAppendOnly`; `PurchasePersistenceIT#app_role_is_denied_mutation_on_append_only_ledger` / `#app_role_is_denied_ddl` | Sl·IT |
| AC-1.7 | idempotency: same key → one row; diff body → `409` | `StorePurchaseServiceTest#same_key_same_payload_replays_the_stored_response_without_a_second_insert` / `#same_key_different_payload_is_a_conflict` / `#a_concurrent_duplicate_key_is_resolved_by_replaying_the_winner`; `PurchaseControllerTest#create_withIdempotencyKey_replay_setsHeader_andFingerprintsRequest` / `#create_idempotencyConflict_returns409_withoutEchoingKey`; `StorePurchasePersistenceIT#same_key_replays_the_committed_jsonb_body_and_mints_no_new_id` / `#the_dual_insert_is_atomic_a_failure_rolls_back_both_rows`; `PurchaseConversionE2EIT#idempotentCreate_sameKeyTwice_oneRecord_sameBody` | App·Sl·IT·E2E |
| AC-2.1 | `200`; converted = amount×rate, HALF_UP, round once | `ConvertPurchaseServiceTest#a_supported_currency_converts_at_the_selected_rate`; `MoneyTest#converts_with_half_up_rounding_once_at_the_end` / `#tie_rounds_half_up_not_half_even` / `#rounds_once_a_high_precision_rate_is_not_truncated_mid_calc`; `MoneyPropertiesTest` (∀); `ConversionControllerTest#convert_treasury_returns200_withRateAndCache`; `PurchaseConversionE2EIT#goldenPath_store_thenConvertToEur` | App·U·Sl·E2E |
| AC-2.2 | latest `effective_date ≤ date` incl. amendment (Argentina ⇒ 1230) | `RateSelectorTest#argentina_amendment_selects_the_amended_rate_for_an_in_between_purchase` / `#later_purchase_excludes_a_not_yet_effective_amendment` / `#exact_effective_date_match_is_selected`; `PurchaseConversionE2EIT#convert_selectsIntraQuarterAmendment_notBaseOrLaterRow`; `TreasuryRateIngestIT#sync_backfills_then_provider_B_selects_the_amendment_locally` | U·E2E·IT |
| AC-2.3 | 6-month floor inclusive, calendar-month, leap-day | `RateSelectorTest#window_floor_uses_calendar_month_arithmetic_into_a_leap_day` / `#a_rate_exactly_on_the_leap_day_floor_is_included` / `#a_rate_one_day_before_the_floor_is_excluded` / `#non_leap_floor_is_inclusive_and_one_day_earlier_is_out` | U |
| AC-2.4 | no rate → `422 NO_RATE_AVAILABLE` naming pair + date | `ConvertPurchaseServiceTest#no_rate_in_window_yields_no_rate_available`; `ConversionControllerTest#convert_noRate_returns422_namingPairAndDate`; `PurchaseConversionE2EIT#convert_noRateInWindow_returns422` | App·Sl·E2E |
| AC-2.5 | USD identity: rate `1.00`, no upstream call | `ConvertPurchaseServiceTest#usd_target_is_an_in_app_identity_with_no_provider_call`; `CurrencyMapTest#usd_is_an_in_app_identity_never_mapped`; `ConversionControllerTest#convert_usdIdentity_returns200_rateOne_noEffectiveDate`; `MoneyTest#usd_identity_multiplies_by_one` | App·U·Sl |
| AC-2.6 | ISO-4217 via curated map; `XOF`≠`XAF`; unsupported→`422`; malformed→`400` | `CurrencyMapTest#xof_and_xaf_resolve_to_different_descriptors` / `#supported_currencies_resolve_to_their_exact_descriptor` / `#iso_valid_but_uncurated_currency_is_unsupported` / `#malformed_tokens_are_flagged_distinctly_from_unsupported`; `ConvertPurchaseServiceTest#an_iso_valid_but_uncurated_currency_is_unsupported` / `#a_malformed_currency_token_is_rejected`; `ConversionControllerTest#convert_unsupportedCurrency_returns422` / `#convert_malformedCurrency_returns400`; `TreasuryLiveCanaryIT#xof_isNotXaf_differentLiveRates` | U·App·Sl·Canary |
| AC-2.7 | unknown purchase → `404` | `ConvertPurchaseServiceTest#an_unknown_purchase_is_not_found`; `ConversionControllerTest#convert_unknownPurchase_returns404` | App·Sl |
| AC-2.8 | resilience: bounded retry (5xx/timeout), breaker, `502/503/504`, never hang/`500` | `TreasuryRateProviderResilienceIT#a_persistent_5xx_retries_then_trips_the_breaker_into_fast_fail` / `#a_4xx_is_not_retried_and_does_not_trip_the_breaker` / `#a_read_timeout_surfaces_as_TIMEOUT`; `ConversionControllerTest#convert_upstreamError_returns502` / `#convert_upstreamTimeout_returns504` / `#convert_circuitOpen_returns503_withRetryAfter` | IT·Sl |
| CC-1 | RFC 9457 `problem+json` + `code` + `traceId` | every error case in `ConversionControllerTest` / `PurchaseControllerTest` (assert `application/problem+json`, `$.code`, `$.traceId`) | Sl |
| CC-2 | no amount/desc in any URL or log | review (no `description`/`amount` in any `log.*` — ids + `traceId` only; the conversion URL carries `{id}`/`{cc}` only); `PurchaseControllerTest#get_returns200_withCacheControl_etag_andSecurityHeaders` | review·Sl |
| CC-3 | money & rates are JSON strings | `PurchaseControllerTest#create_returns201_withLocation_andMoneyAsString`; `ConversionControllerTest#convert_treasury_returns200_withRateAndCache` (`$.exchangeRate` isString); `PurchaseConversionE2EIT#goldenPath_store_thenConvertToEur` (`exchangeRate().isTextual()`) | Sl·E2E |
| CC-4 | OpenAPI 3.1 matches the implemented contract | authored `openapi.yaml` served via springdoc (the source of truth); the slice + E2E assertions above lock each documented field, type, money-as-string and error `code` against it. *Follow-up below.* | Sl·E2E |
| CC-5 | deterministic under test; no live network in the gate | injected `Clock` (`Clock.fixed` in unit/IT); the gating suite uses WireMock/Testcontainers only — the sole real-network test is the `@Tag("live")` canary, excluded by default (`build.gradle.kts`) | all |

**One conscious follow-up (CC-4).** Field-level contract conformance is enforced today by the authored
OpenAPI being the served source of truth *plus* the slice/E2E assertions that mirror every field, type,
money-as-string and error `code`. A *message-level* validator that replays each E2E response through the
`openapi.yaml` schema (e.g. `swagger-request-validator`) is the gold-standard hardening and is the single
documented deferral — it adds a dependency and is orthogonal to the behavioural correctness the matrix
already covers.

## CI mapping (implemented — `.github/workflows/`)

- **`ci.yml` (every PR + push to `main`):** `./gradlew check` = fast `test` (unit + slice) + ArchUnit
  boundaries + the JaCoCo core-coverage floor (`domain.*`+`application.*` ≥ 85% instruction). No Docker,
  no network — the constitution §10 gate. Wrapper-validated; concurrency-cancelled.
- **`nightly.yml` (schedule + manual):** three jobs — `integrationTest` (Testcontainers + WireMock,
  `@Tag("live")` excluded) · `pitest` (mutation on the money + rate-selection core, threshold 85) · the
  **live canary** (`-Plive`, `continue-on-error` — drift surfaces, never blocks).
- The captured Treasury payload shape is exercised inline in `TreasuryRateFetcherTest` (fed through the
  real Jackson mapping) and guarded against live drift by `TreasuryLiveCanaryIT`. `Clock` and the
  `ExchangeRateProvider` port are injectable for determinism.
