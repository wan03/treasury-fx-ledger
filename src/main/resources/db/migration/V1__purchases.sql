-- =============================================================================
-- V1 — purchases (R1). The append-only USD principal ledger. See data-model.md.
-- DB constraints deliberately mirror the edge validation (defense in depth): the
-- service must never be the only thing standing between bad data and the table.
-- =============================================================================

CREATE TABLE purchases (
    id               UUID          PRIMARY KEY,                  -- UUIDv7, app-generated (D-08)
    description      VARCHAR(50)   NOT NULL
                       CHECK (char_length(description) BETWEEN 1 AND 50),
    transaction_date DATE          NOT NULL,
    amount           NUMERIC(19,2) NOT NULL CHECK (amount > 0),  -- USD principal, exact cents (D-04)
    currency         CHAR(3)       NOT NULL DEFAULT 'USD' CHECK (currency = 'USD'),
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- Least privilege: the app reads and appends only. No UPDATE/DELETE path exists
-- for this table anywhere in the application — corrections are new reversing
-- records, never mutations (constitution §6 / D-09).
GRANT SELECT, INSERT ON purchases TO app;
