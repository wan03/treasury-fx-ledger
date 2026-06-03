# Currency mapping — ISO-4217 → Treasury descriptor

> How a target currency code becomes a Treasury `country_currency_desc`. Implements D-01; grounded in
> F5/F6/F9. The map is **version-controlled data, not code**, and is **verified against the live API
> by an automated test** (the canary). Read `DECISION_LOG.md` F9 for the full landscape and evidence.

## Why a map is needed (the three classes)

Treasury has **no ISO codes**. Its only reliable key is `country_currency_desc` (e.g. `Canada-Dollar`);
the bare `currency` word collides (`"Dollar"` spans 22 countries). The landscape has three shapes:

1. **Homonyms** — one *word* spans many distinct ISO currencies (`Dollar`×22, `Dinar`×8, `Peso`×8,
   `Franc`×6, `Rupee`×6, `Pound`×5, `Shilling`×4, …). A **disambiguation** problem — solved cleanly by
   keying on ISO → one specific descriptor (`CAD → Canada-Dollar`, `AUD → Australia-Dollar`).
2. **Currency unions** — one ISO currency spans many descriptors (a **canonicalization** problem):
   - `EUR` → the consolidated **`Euro Zone-Euro`** (continuous 2001→present; per-country euros like
     `Germany-Euro` were discontinued after 2025-03-31 — don't use them).
   - `XCD` → 3 per-country East-Caribbean rows (all share the rate; pick one designated primary).
   - **⚠ The trap:** the word `Cfa Franc` is **two different ISO currencies with different rates** —
     `XOF` (BCEAO) ≈ 567.0 and `XAF` (BEAC) ≈ 570.79. **`XOF ≠ XAF`.** Word-keying is *provably
     unsafe* here. They must resolve to different descriptors.
3. **USD & dollarized economies** — **USD has no self-row.** Dollarized economies appear as `1.0`
   proxies (`Panama-Dolares`, `Ecuador-Dolares`, …). **Never** map `USD` to a proxy — `USD` is an
   in-app identity (D-07).

## The resolution policy (three parts)

```
resolve(ISO code) →
  1. USD            → identity (rate 1.00, no Treasury call)                      [D-07]
  2. in curated map → its country_currency_desc                                   [the common case]
  3. not in map     → 422 CURRENCY_UNSUPPORTED  (ISO-valid but we don't support it)
  malformed token   → 400 CURRENCY_CODE_MALFORMED (not ^[A-Z]{3}$)
```

Map construction rules:
- **1:1 currencies** → their single descriptor (homonyms disambiguated by committing to ISO).
- **Unions** → prefer a **consolidated zone row** if one exists (`EUR → Euro Zone-Euro`); else a
  **designated primary** member, documented, with **continuous history** (matters for back-dated
  purchases). The pick is value-neutral *within* a union (members share a rate) but **must respect
  `XOF ≠ XAF`**.
- The supported set is **curated**, not all 165 descriptors. Start with a sensible core; expanding is
  adding rows, not changing logic.

## Map artifact (data, not code)

Ship a version-controlled resource (e.g. `currency-map.csv` / `.yaml` under `resources/`), loaded at
startup into an immutable lookup. Illustrative rows:

```
# iso , country_currency_desc                 , note
EUR   , Euro Zone-Euro                        , consolidated zone series (F6)
CAD   , Canada-Dollar                         , 1:1
AUD   , Australia-Dollar                      , 1:1 (homonym disambiguated by ISO)
GBP   , United Kingdom-Pound                  , 1:1
JPY   , Japan-Yen                             , 1:1 (note: brief forces 2 dp — flagged, D-04)
XOF   , Senegal-Cfa Franc                     , BCEAO primary  ⚠ distinct from XAF
XAF   , Cameroon-Cfa Franc                    , BEAC primary   ⚠ distinct from XOF
XCD   , Antigua & Barbuda-East Caribbean Dollar, union primary
# USD intentionally absent → in-app identity (D-07)
```
*(Verify each `country_currency_desc` string against the live dataset before committing — exact
spelling/punctuation matters; the canary enforces it ongoing.)*

## Verification test (operationalizes D-01)

A **non-gating** `@Tag("live")` canary (nightly/manual, never blocks a PR) asserts, against the live
Treasury API:
- every `country_currency_desc` in the map **still resolves** (catches retirements like the
  per-country euros and the East-Caribbean shrink);
- for unions, sampled members **agree on the rate** (sanity on the "value-neutral within a union"
  assumption);
- `XOF` and `XAF` resolve to **different** rows.

The **gating** suite tests the resolution *logic* against fixtures only (no network): `EUR → Euro
Zone-Euro`, `CAD → Canada-Dollar`, `XOF`/`XAF` → different descriptors, `USD` → identity,
`ZZZ`/lowercase → unsupported/malformed.

## Assumptions (state these in the submission)

- Consumers speak **ISO-4217**; the zone rate is intended for `EUR`.
- Designated primaries for `XOF`/`XAF`/`XCD` are acceptable (members share a rate).
- The supported set is curated, not exhaustive. *(HM Q1 may change these — keep the map as data so a
  change is a data edit, not a refactor.)*
