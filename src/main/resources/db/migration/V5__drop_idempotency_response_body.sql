-- =============================================================================
-- V5 — stop storing a second at-rest copy of PII (finding #8). response_body
-- duplicated the full 201 body, including the purchase `description` (PII). That
-- body is fully re-projectable from the referenced purchases row on replay
-- (every field, including created_at, lives there), so the copy is redundant.
-- The purchase_id FK (the projection source), request_hash (409 detection) and
-- response_status remain. PII now lives only in `purchases`.
-- =============================================================================

ALTER TABLE idempotency_keys DROP COLUMN response_body;
