-- =============================================================================
-- V3 — exchange_rates (D-03). Backs providers B (ingest) and C (hybrid) only;
-- A0/A call Treasury live and never read this table, so it is harmless when an
-- on-demand profile runs. Keyed (desc, effective_date) so an intra-quarter
-- amendment coexists with its base quarter (rate-selection.md, F4/F8).
-- =============================================================================

CREATE TABLE exchange_rates (
    country_currency_desc TEXT          NOT NULL,
    effective_date        DATE          NOT NULL,
    record_date           DATE          NOT NULL,
    exchange_rate         NUMERIC(19,6) NOT NULL CHECK (exchange_rate > 0),  -- store generously; round at use
    ingested_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (country_currency_desc, effective_date)
);

-- Index makes the selection query an index seek:
--   WHERE country_currency_desc = ? AND effective_date <= ? ORDER BY effective_date DESC LIMIT 1
CREATE INDEX idx_rates_desc_eff
    ON exchange_rates (country_currency_desc, effective_date DESC);

-- Providers B/C backfill and reconcile amendments via upsert, so the app needs
-- INSERT/UPDATE here; SELECT powers the local effective-date selection.
GRANT SELECT, INSERT, UPDATE ON exchange_rates TO app;
