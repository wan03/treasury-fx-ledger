# Rate selection — the crown jewel

> The single most important piece of correctness in the system. Implements D-02; grounded in verified
> facts F2/F3/F4/F7/F8. Build it as a **pure function** so it is exhaustively unit-testable, then wrap
> it in the `ExchangeRateProvider` adapter. Read `DECISION_LOG.md` F4/F8 for the evidence.

## The rule (precise)

> Given a `purchaseDate` and a target currency, choose the Treasury rate with the **greatest
> `effective_date` that is ≤ `purchaseDate`** and **≥ `purchaseDate − 6 calendar months`**.
> If none exists, the purchase **cannot be converted** → `422 NO_RATE_AVAILABLE`.
> `convertedAmount = originalAmount × exchange_rate`, rounded HALF_UP to 2 dp (round once).

Three sub-rules, each independently tested:

1. **Direction & arithmetic (F2).** `exchange_rate` is *foreign units per 1 USD* ⇒ multiply.
   Parse the rate **string → BigDecimal**; never assume scale (observed 2–4 dp).
2. **Which date governs: `effective_date`, not `record_date` (F4/F8).** Treasury issues intra-quarter
   **amendments** as extra rows that share a `record_date` but carry a new, later `effective_date`.
   The amended rate applies to transactions from its `effective_date`. Selecting by `record_date`
   would mis-rate any transaction dated after an amendment. **`effective_date` is authoritative.**
3. **The 6-month window.** Inclusive of both ends, measured on `effective_date`, using **calendar-month
   arithmetic** (`minusMonths(6)`), not 180 days.

## Pure function (domain)

```java
/** No HTTP, no Spring, no Clock. Deterministic over the candidate rows. */
public Optional<ExchangeRate> select(List<ExchangeRate> candidates, LocalDate purchaseDate) {
    LocalDate floor = purchaseDate.minusMonths(6);                 // calendar-month, inclusive
    return candidates.stream()
        .filter(r -> !r.effectiveDate().isAfter(purchaseDate))     // effective_date <= purchaseDate
        .filter(r -> !r.effectiveDate().isBefore(floor))           // effective_date >= floor
        .max(Comparator.comparing(ExchangeRate::effectiveDate)     // latest wins
            .thenComparing(ExchangeRate::recordDate));             // deterministic tiebreak
}
```

`ExchangeRate` carries `country_currency_desc`, `effectiveDate`, `recordDate`, and `exchangeRate`
(BigDecimal). The tiebreak only matters for pathological duplicate `effective_date`s — make it
deterministic anyway.

## Server-side push-down (adapter A, F7)

The on-demand adapter expresses the entire rule in one request so only ~1 row crosses the wire:
```
GET …/v1/accounting/od/rates_of_exchange
  ?fields=country_currency_desc,exchange_rate,effective_date,record_date
  &filter=country_currency_desc:eq:<desc>,effective_date:lte:<purchaseDate>,effective_date:gte:<floor>
  &sort=-effective_date
  &page[size]=1
  &format=json
```
- Empty `data[]` ⇒ `Optional.empty()` ⇒ `422 NO_RATE_AVAILABLE` (a **normal** outcome, not an error).
- Still run the pure `select()` over whatever rows you fetch — never trust the server alone for
  correctness; the pushed-down filter is an optimization, the pure function is the spec.
- `<floor> = purchaseDate.minusMonths(6)` formatted ISO.

## Worked examples (lock these as fixtures)

**Argentina-Peso amendment (the headline test).** Candidates:

| effective_date | exchange_rate |
|---|---|
| 2025-03-31 | 1093.0 |
| **2025-04-15** | **1230.0** |
| 2025-06-30 | 1205.0 |

- `purchaseDate = 2025-05-01` ⇒ **1230.0** (latest `effective_date ≤ 2025-05-01`; the Q1 base is
  older, the Q2 `1205` is not yet effective). **Not 1093, not 1205.**
- `purchaseDate = 2025-07-15` ⇒ **1205.0** (excludes a later `2025-08-31:1345` amendment if present —
  it isn't effective yet).

**6-month boundary (calendar-month + leap year).**
- `purchaseDate = 2024-08-31` ⇒ floor `2024-02-29` (leap). A rate effective exactly `2024-02-29` is
  **in**; `2024-02-28` would be out if it were below the floor — verify inclusivity at the exact edge.
- `purchaseDate = 2023-08-31` ⇒ floor `2023-02-28` (non-leap). One day earlier ⇒ out → error.

**Empty window.** A very old purchase (or a newly-added/discontinued currency) with no row in range ⇒
`Optional.empty()` ⇒ `422`.

## USD identity short-circuit  *(D-07)*

Target `USD` never enters this path: `exchangeRate = 1.00`, converted = original, no upstream call.
Handle it in the application service before invoking the provider.

## Edge & failure handling

- **Exact match** (`effective_date == purchaseDate`) selects that row.
- **No exact match** → latest earlier in-window row.
- **Amendment dated after the purchase** must be excluded (not yet effective).
- **Variable rate precision** — keep full precision into the multiply; round once at the end (D-04).
- **Upstream failure ≠ no rate** — a 5xx/timeout is a resilience concern (`502/503/504`), distinct
  from an empty result (`422`). Never collapse the two.
