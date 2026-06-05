-- =============================================================================
-- V4 — scope idempotency keys by caller (finding #6). A key is unique PER
-- PRINCIPAL, not globally, so once an auth gateway authenticates multiple
-- clients the same key + payload from a different caller can no longer replay
-- another caller's stored 201 (which carries the purchase id + description PII).
-- With no gateway today every caller is the 'anonymous' sentinel, so existing
-- rows and behavior are unchanged (the DEFAULT backfills them).
-- =============================================================================

ALTER TABLE idempotency_keys
    ADD COLUMN principal VARCHAR(255) NOT NULL DEFAULT 'anonymous';

-- Repoint the primary key from (key) to (principal, key). The composite PK stays
-- the concurrency guard the loser of a race catches as a unique violation.
ALTER TABLE idempotency_keys DROP CONSTRAINT idempotency_keys_pkey;
ALTER TABLE idempotency_keys ADD PRIMARY KEY (principal, key);
