-- =============================================================================
-- V2 — idempotency_keys (D-08/D-09). Makes POST create exactly-once and stores
-- the canonical 201 body for safe replay. The PK on `key` turns concurrent
-- duplicate retries into a unique-violation the loser catches, reads, replays.
-- =============================================================================

CREATE TABLE idempotency_keys (
    key             VARCHAR(255) PRIMARY KEY,                    -- client-supplied Idempotency-Key
    request_hash    CHAR(64)     NOT NULL,                       -- SHA-256 of the canonical request
    purchase_id     UUID         NOT NULL REFERENCES purchases(id),
    response_status SMALLINT     NOT NULL,
    response_body   JSONB        NOT NULL,                       -- exact 201 body to replay
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ  NOT NULL                        -- TTL ~24-48h; swept by the app
);

CREATE INDEX idx_idempotency_expires_at ON idempotency_keys (expires_at);  -- sweep support

-- The app inserts on create, reads on replay, and DELETEs expired rows — its
-- ONLY DELETE grant, scoped to the TTL sweep. No UPDATE: entries are immutable.
GRANT SELECT, INSERT, DELETE ON idempotency_keys TO app;
