# Data model — schema & migrations

> The persistence contract: tables, constraints, types, migrations. Implements constitution §6 and
> D-08/D-10. Flyway-managed plain SQL — reviewers read the DDL. Keep aligned with `api-contract.md`
> (field shapes) and `plan.md` (the persistence adapter).

## Principles

- **Postgres everywhere** (dev/test/prod) — no H2 (dialect drift hides `NUMERIC` bugs).
- **DB constraints mirror application validation** (defense in depth — never trust the edge alone).
- **Append-only** for financial records: no `updated_at`, no in-place mutation.
- Native types: `UUID` (16 bytes), `NUMERIC(19,2)`, `TIMESTAMPTZ`, `DATE`.
- **Least privilege:** a `migration` role owns DDL; the `app` role gets DML only (`SELECT/INSERT`,
  plus `DELETE` on `idempotency_keys` for TTL sweeps). The app role cannot `ALTER`/`DROP`.

## Table: `purchases`  *(R1)*

```sql
CREATE TABLE purchases (
    id               UUID         PRIMARY KEY,                     -- UUIDv7, app-generated (D-08)
    description      VARCHAR(50)  NOT NULL
                      CHECK (char_length(description) BETWEEN 1 AND 50),
    transaction_date DATE         NOT NULL,
    amount           NUMERIC(19,2) NOT NULL CHECK (amount > 0),    -- USD principal, exact cents
    currency         CHAR(3)      NOT NULL DEFAULT 'USD' CHECK (currency = 'USD'),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);
```

Notes:
- `id` is **application-generated UUIDv7** (known pre-insert; time-ordered → index locality). Do not
  use a DB default — keeps the code DB-portable and the id available before the round-trip.
- `VARCHAR(50)` + `CHECK` is belt-and-suspenders with edge validation; `char_length` counts
  characters. (App enforces *code-point* counting per `api-contract.md`; document any nuance.)
- `currency` is constrained to `USD` — only USD is ever stored (conversion is a read-time projection,
  never persisted).
- **No `DELETE`/`UPDATE`** path exists for this table in the app.

## Table: `idempotency_keys`  *(D-08/D-09)*

```sql
CREATE TABLE idempotency_keys (
    key             VARCHAR(255) PRIMARY KEY,                      -- client-supplied Idempotency-Key
    request_hash    CHAR(64)     NOT NULL,                         -- SHA-256 of the canonical request
    purchase_id     UUID         NOT NULL REFERENCES purchases(id),
    response_status SMALLINT     NOT NULL,
    response_body   JSONB        NOT NULL,                         -- exact 201 body to replay
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ  NOT NULL                          -- TTL ~24–48h
);
CREATE INDEX idx_idempotency_expires_at ON idempotency_keys (expires_at);   -- sweep support
```

Semantics:
- **POST create = one transaction:** insert the `purchases` row **and** the `idempotency_keys` row
  atomically. The PK on `key` makes concurrent duplicate retries safe — the loser hits a unique
  violation, then **reads and replays** the stored response.
- Same key + same `request_hash` ⇒ replay stored `201`. Same key + **different** hash ⇒ `409`.
- A scheduled sweep deletes rows past `expires_at` (the app role's only `DELETE` grant).

## Table: `exchange_rates`  *(in scope — providers B (ingest) & C (hybrid), D-03)*

```sql
CREATE TABLE exchange_rates (
    country_currency_desc TEXT          NOT NULL,
    effective_date        DATE          NOT NULL,
    record_date           DATE          NOT NULL,
    exchange_rate         NUMERIC(19,6) NOT NULL,                  -- variable precision; store generously
    ingested_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (country_currency_desc, effective_date)
);
CREATE INDEX idx_rates_desc_eff ON exchange_rates (country_currency_desc, effective_date DESC);
```

- Keyed `(country_currency_desc, effective_date)` so amendments coexist with their base quarter.
- The index makes the selection query (`= desc AND effective_date <= d ORDER BY effective_date DESC
  LIMIT 1`) an index seek. See `rate-selection.md`.
- Ships as `V3__exchange_rates.sql`. **A0/A do not read it** (they go to Treasury); it backs B/C only.
  The table is harmless when an A0/A profile runs, so a plain versioned migration is fine — no need to
  gate it, though you may if you prefer a minimal A-only schema.

## Migrations (Flyway)

- `V1__purchases.sql`, `V2__idempotency_keys.sql`, (optional) `V3__exchange_rates.sql`.
- Versioned and immutable once shipped; repeatable migration `R__seed_dev.sql` for dev seed data only
  (guarded so it never runs in prod).
- Migrations run identically on app startup, in Testcontainers, and in prod (parity).
- Run as the `migration` role; the app connects as the `app` role.

## Type-mapping guidance (persistence adapter)

- `Money` (BigDecimal + currency) ↔ `NUMERIC(19,2)` via a custom converter; **assert scale 2 is
  preserved** on read (`12.30` must not collapse to `12.3`) — covered by a persistence-slice test.
- `LocalDate` ↔ `DATE`; `UUID` ↔ `uuid`; `Instant`/`OffsetDateTime` ↔ `TIMESTAMPTZ`.
- Read paths are read-only transactions; the create path is the single write transaction above.
