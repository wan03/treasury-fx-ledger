# Spec — functional contract (what & why)

> The behavior the system must exhibit, as testable acceptance criteria. Technology-light on
> purpose: *what* and *why*, not *how* (the how is `plan.md` and the detail units). Every criterion
> below maps to a test (`test-strategy.md`) and traces back to a requirement (R1/R2).

## Actors & scope

A single client (assume an authenticated upstream gateway) records **USD purchase transactions** and
later **reads them converted** into a target currency using official U.S. Treasury rates. No UI. No
human-facing auth in scope. Two capabilities only.

---

## R1 — Store a purchase transaction

**Story.** *As a client, I record a purchase (description, date, USD amount) and receive a durable,
uniquely identified record, so I can later retrieve and convert it.*

**Input fields & rules** *(detail: `api-contract.md`, D-05/D-06)*

| Field | Rule |
|---|---|
| `description` | required; 1–50 Unicode code points; trimmed; no control chars |
| `transactionDate` | required; ISO local date `YYYY-MM-DD`; strict; **not in the future** |
| `amount` | required; positive USD decimal **string**; **≤ 2 decimal places**; bounded; no separators/symbols/sign/sci-notation |
| `currency` | optional; defaults `USD`; any non-USD ⇒ rejected (only USD is stored) |

**Acceptance criteria**

- **AC-1.1** Given a valid purchase, when stored, then a **UUIDv7** `id` is assigned and the record is
  persisted with the exact amount (cent scale preserved, e.g. `12.30` ≠ `12.3`).
- **AC-1.2** The response is `201` with a `Location` header and echoes the stored fields plus
  `currency: "USD"` and a `createdAt` timestamp.
- **AC-1.3** A `description` of 51+ code points is rejected (`400 DESCRIPTION_TOO_LONG`); exactly 50 is
  accepted.
- **AC-1.4** An `amount` with 3+ decimals is **rejected, not rounded** (`400 AMOUNT_PRECISION`); `0` or
  negative is rejected (`400 AMOUNT_NOT_POSITIVE`); 2 decimals is accepted.
- **AC-1.5** A non-existent or impossible date (`2026-02-30`), a non-ISO format, or a **future** date
  is rejected (`400`, `DATE_INVALID` / `DATE_IN_FUTURE`). A valid **past** date is accepted —
  including dates too old to convert (those fail only at R2).
- **AC-1.6** Purchases are **immutable**: no update or delete capability exists.
- **AC-1.7** *(Idempotency, D-08/D-09)* Re-sending the same request with the same `Idempotency-Key`
  returns the **same** record (one row); the same key with a different body returns `409`.

**Why these choices** → `DECISION_LOG.md` D-05 (reject vs round), D-06 (future vs old dates),
D-08 (UUIDv7), D-09 (immutability, idempotency).

---

## R2 — Retrieve a purchase converted to a target currency

**Story.** *As a client, I request a stored purchase in a target currency and receive the original
USD amount, the official rate used, and the converted amount, so I have an auditable conversion.*

**Selection rule (the heart of the system)** *(detail: `rate-selection.md`, D-02, F4/F7/F8)*

> Use the Treasury rate with the **greatest `effective_date` that is ≤ the purchase date** and **no
> more than 6 calendar months before** it. If no such rate exists, the purchase **cannot be
> converted** — return an error. The converted amount is `originalAmount × rate`, rounded to 2 dp.

**Output fields** (the brief's required set, plus two for auditability):
`purchaseId`, `description`, `transactionDate`, `originalAmount` (USD), `originalCurrency`,
`targetCurrency`, `exchangeRate`, `convertedAmount`, **`rateEffectiveDate`**, **`rateSource`**.

**Acceptance criteria**

- **AC-2.1** Given a stored purchase and a supported target currency with an in-window rate, the
  response is `200` with all output fields; `convertedAmount = originalAmount × exchangeRate` rounded
  **HALF_UP** to 2 dp, computed at full precision (round once).
- **AC-2.2** The rate chosen is the **latest `effective_date ≤ transactionDate`** within 6 months —
  **including** intra-quarter amendments. *Locked fixture:* Argentina-Peso candidates
  `{2025-03-31: 1093, 2025-04-15: 1230, 2025-06-30: 1205}`; a `2025-05-01` purchase ⇒ **1230**
  (not the Q1 base, not the not-yet-effective Q2 rate).
- **AC-2.3** The 6-month boundary is **inclusive** of the floor date and uses calendar-month
  arithmetic (e.g. `2024-08-31 − 6mo = 2024-02-29` in a leap year). One day past the floor ⇒ no rate.
- **AC-2.4** When no rate exists in the window, the response is **`422 NO_RATE_AVAILABLE`** with a
  human-readable reason naming the currency pair and date. *(R2's mandated error path.)*
- **AC-2.5** Target `USD` is an **identity** conversion: `exchangeRate = 1.00`,
  `convertedAmount = originalAmount`, **no upstream Treasury call**. *(D-07)*
- **AC-2.6** The target currency is **ISO-4217**, resolved through the curated map. `XOF` and `XAF`
  resolve to **different** Treasury descriptors/rates. An ISO-valid but unsupported currency ⇒
  `422 CURRENCY_UNSUPPORTED`; a malformed token (not `^[A-Z]{3}$`) ⇒ `400`. *(D-01)*
- **AC-2.7** Converting a non-existent purchase ⇒ `404`.
- **AC-2.8** *(Resilience, D-03)* When Treasury is slow or failing, the system degrades predictably:
  bounded retries on 5xx/timeout, circuit-breaker, mapped `502/503/504` — never a hang, never a `500`
  leaking internals.

**Why these choices** → `DECISION_LOG.md` D-02 (effective_date), D-01 (mapping), D-04 (rounding),
D-07 (USD identity), D-03 (resilience).

---

## Cross-cutting acceptance (apply to both)

- **CC-1** All errors are `application/problem+json` (RFC 9457) with a stable `code` + `traceId`.
- **CC-2** No amount or `description` ever appears in a URL or log line.
- **CC-3** Money and rates are JSON **strings** in every payload.
- **CC-4** The OpenAPI 3.1 document matches the implemented contract (contract test).
- **CC-5** Behavior is deterministic under test (injected `Clock`; no live network in the gate).

---

## Review & acceptance checklist (gate before "done")

- [x] Every AC above has at least one automated test, mapped in the R↔test matrix.
      *(`test-strategy.md` § Requirement traceability — every AC-1.x / AC-2.x / CC-x row is populated.)*
- [x] Money path contains no `float`/`double`; mutation score on `money` + `rate-selection` meets the
      threshold. *(Verified: the only `float` in `main` is the resilience `failureRateThreshold` knob —
      not the money path; PIT scores 92% on `domain.*` ≥ the 85 threshold.)*
- [x] The Argentina amendment fixture and the leap-year boundary fixture both pass.
      *(`RateSelectorTest#argentina_amendment_…` + `#window_floor_uses_calendar_month_arithmetic_into_a_leap_day`;
      amendment also end-to-end in `PurchaseConversionE2EIT#convert_selectsIntraQuarterAmendment_…`.)*
- [x] `XOF ≠ XAF` is asserted; `USD` identity path has no upstream call.
      *(`CurrencyMapTest#xof_and_xaf_resolve_to_different_descriptors` + live `TreasuryLiveCanaryIT#xof_isNotXaf_…`;
      `ConvertPurchaseServiceTest#usd_target_is_an_in_app_identity_with_no_provider_call`.)*
- [x] OpenAPI served; errors are RFC 9457; no PII/amounts in logs (verified by a test or review).
      *(springdoc serves the authored `openapi.yaml`; slice tests assert `problem+json`+`code`+`traceId`;
      grep confirms no `description`/`amount` in any `log.*`. CC-4 message-level validation = documented follow-up.)*
- [ ] One-command local run works from a clean checkout (`make dev`). *(Wiring is exercised by the
      `@SpringBootTest` context-load + E2E; the full clean-checkout `bootRun`+compose boot is owned by **T7.2/T7.4**.)*
- [ ] All `PROPOSED`-but-defaulted behaviors are listed in the submission's "assumptions" section.
      *(Compiled in `tasks.md` Phase 6 gate notes; surfaced in the README by **T7.1**.)*
