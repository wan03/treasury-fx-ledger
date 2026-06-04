# API contract

> The HTTP surface: resources, requests, responses, the error catalog, idempotency, caching,
> security. Implements D-09. **OpenAPI 3.1 is the source of truth** — generate it (springdoc), serve
> it at `/v3/api-docs` + Swagger UI, and contract-test the implementation against it. This doc is the
> human-readable companion; if they disagree, fix the code so both match.

## Conventions

- REST/JSON, **URI-versioned `/v1`**. Success `application/json`; errors `application/problem+json`.
- Dates ISO-8601 local (`YYYY-MM-DD`). **Money and rates are JSON strings** (never numbers).
- Jackson: `WRITE_BIGDECIMAL_AS_PLAIN`, fail on unknown properties for requests, UTF-8.

## Resource model

- **Purchase** — a stored, immutable USD financial record.
- **Conversion** — a *computed projection* of a purchase into a target currency. **Never persisted.**
  Modeled as a path sub-resource keyed by ISO-4217 code (addressable, cache-friendly).

| Method & path | Purpose | Success | Idempotent |
|---|---|---|---|
| `POST /v1/purchases` | Create a purchase | `201` + `Location` | No → `Idempotency-Key` |
| `GET /v1/purchases/{id}` | Fetch stored purchase (USD) | `200` | Yes |
| `GET /v1/purchases/{id}/conversions/{currencyCode}` | Converted view + rate metadata | `200` | Yes |

**No `PUT`/`PATCH`/`DELETE`** — purchases are append-only; corrections are new reversing records.
`GET /v1/purchases` (list) is **out of scope**.

## `POST /v1/purchases`

**Request**
```json
{ "description": "Office supplies", "transactionDate": "2026-03-15", "amount": "12.34" }
```
Field rules (reject → `400` with field-level `errors[]`; see catalog):

- `description` — required; trim; **1–50 Unicode code points**; reject control chars.
- `transactionDate` — required; ISO local date; **strict** parse; **not in the future** (injected
  `Clock`, configured zone default UTC, small skew tolerance). Old dates are accepted here.
- `amount` — required decimal **string**; matches `^\d{1,17}(\.\d{1,2})?$`; `> 0`; **≤ 2 dp →
  reject, never round**; no separators/symbols/sign/sci-notation; ≤ configured cap within
  `NUMERIC(19,2)`.
- `currency` — optional; default `USD`; any non-USD ⇒ `422 CURRENCY_NOT_STORABLE` (only USD stored).
- Header `Idempotency-Key` — optional, opaque, ≤255 chars.

**Response `201`**, `Location: /v1/purchases/{id}`:
```json
{ "id": "<uuidv7>", "description": "Office supplies", "transactionDate": "2026-03-15",
  "amount": "12.34", "currency": "USD", "createdAt": "2026-06-03T09:35:12Z" }
```

## `GET /v1/purchases/{id}`

`200` with the stored record (same shape as the `201` body). `404` if unknown id. Carries an `ETag`.

## `GET /v1/purchases/{id}/conversions/{currencyCode}`

`currencyCode` is ISO-4217 (`^[A-Z]{3}$`). Resolution and selection per `currency-mapping.md` and
`rate-selection.md`.

**Response `200`** (example `…/conversions/EUR`):
```json
{ "purchaseId": "<uuidv7>", "description": "Office supplies", "transactionDate": "2026-03-15",
  "originalAmount": "12.34", "originalCurrency": "USD", "targetCurrency": "EUR",
  "exchangeRate": "0.924", "rateEffectiveDate": "2025-03-31", "convertedAmount": "11.40",
  "rateSource": "U.S. Treasury Reporting Rates of Exchange" }
```
Returns the brief's required fields **plus `rateEffectiveDate` + `rateSource`** for auditability.
`USD` target ⇒ identity (`exchangeRate: "1.00"`, converted = original, **no upstream call**).

## Error catalog (RFC 9457 + extensions)

Every error body:
```json
{ "type": "https://api.example.com/problems/<slug>", "title": "<short>", "status": <code>,
  "code": "<MACHINE_CODE>", "detail": "<human, no PII beyond what the caller sent>",
  "instance": "<request path>", "traceId": "<id>" }
```
Validation errors additionally carry an `errors[]` array of `{field, code, message}`.

| HTTP | `code` | When |
|---|---|---|
| 400 | `DESCRIPTION_TOO_LONG` / `DESCRIPTION_INVALID` | >50 code points / blank / control chars |
| 400 | `AMOUNT_PRECISION` | >2 decimal places (do **not** round) |
| 400 | `AMOUNT_NOT_POSITIVE` | `≤ 0` |
| 400 | `AMOUNT_MALFORMED` | separators/symbols/sign/sci-notation/over cap |
| 400 | `DATE_INVALID` | non-ISO or impossible date (`2026-02-30`) |
| 400 | `DATE_IN_FUTURE` | after today (+ skew) |
| 400 | `CURRENCY_CODE_MALFORMED` | target not `^[A-Z]{3}$` |
| 404 | `PURCHASE_NOT_FOUND` | unknown purchase id |
| 409 | `IDEMPOTENCY_CONFLICT` | same `Idempotency-Key`, different payload |
| 422 | `CURRENCY_NOT_STORABLE` | POST `currency` ≠ USD |
| 422 | `CURRENCY_UNSUPPORTED` | ISO-valid target not in the curated map |
| 422 | `NO_RATE_AVAILABLE` | **no rate within 6 months** (R2's mandated error) |
| 429 | `RATE_LIMITED` | throttled (if rate limiting enabled) |
| 502/503/504 | `UPSTREAM_*` | Treasury failure / circuit-open (`503` + `Retry-After`) / timeout |
| 500 | `INTERNAL` | unexpected — never leak internals/stack |
| 400 | `MALFORMED_REQUEST` | unreadable/oversized body, unknown JSON property, over-long `Idempotency-Key` |
| 404 | `NOT_FOUND` | unknown route (distinct from `PURCHASE_NOT_FOUND`, a known route + absent id) |
| 405 | `METHOD_NOT_ALLOWED` | unmapped verb — how **append-only** (D-09) surfaces: no `PUT`/`PATCH`/`DELETE` |
| 406 | `NOT_ACCEPTABLE` | `Accept` cannot be satisfied |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | request `Content-Type` not JSON |

The last five are **protocol-level**: the framework rejects the request before any handler runs, and
the advice enriches that problem with the same `code` + `traceId` shape so the wire body is uniform.

**Status discipline:** `400` = malformed/can't-process-as-written; **`422` = well-formed but the
request can't be fulfilled** (no rate, unsupported currency). Keep this distinction crisp.

## Idempotency (POST)

`Idempotency-Key` → persist `{key, requestHash(SHA-256 of canonical body), response}` with TTL
(~24h). Same key + same hash ⇒ **replay** the stored `201`. Same key + different hash ⇒ `409`. Two
identical purchases are legitimately distinct (no natural dedup), so safe retries require the explicit
key. Insert is atomic with the purchase (`data-model.md`).

## Caching

Past-dated conversions are near-deterministic ⇒ `Cache-Control: …max-age=…` + `ETag` /
`If-None-Match`. **Not `immutable`** — recent quarters can gain an amendment (F8) and the mapping can
drift; use a **moderate** TTL. Purchases carry an `ETag` too.

> **Deviation — `private`, not `public`.** Both response bodies embed the purchase `description`, which
> is PII (constitution §5/§9). A `public` directive would license shared/CDN caches to retain it, so the
> implementation ships `Cache-Control: private` on both reads (`max-age=60` for the purchase, `3600` for
> the conversion). Revalidation still rides the `ETag`; only the *shared-cacheability* is given up. If
> `description` is ever dropped from these bodies, `public` becomes safe again.

## Security  *(constitution §5)*

- TLS/HSTS only. **No amounts or PII in URLs/query/logs** — log ids + `traceId` only.
- Request-size limits; strict content negotiation; `currencyCode` regex-validated before use.
- Deny-by-default CORS. AuthN/AuthZ assumed at the gateway (OAuth2 bearer / internal mTLS) — leave the
  filter seam, don't implement. Optional rate limiting → `429`.

## OpenAPI

Materialize `openapi.yaml` (or springdoc-generated) **as the first build artifact** — it anchors DTOs,
the error schema, and the contract test. Tag operations to R1/R2 for traceability.
