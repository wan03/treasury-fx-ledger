package com.wex.fx.application.port;

import com.wex.fx.application.error.DuplicateIdempotencyKeyException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port for the {@code idempotency_keys} table (D-08/D-09). Records, per
 * {@code (principal, key)}, the request fingerprint and the {@code purchase_id} of the created
 * record, so a retried {@code POST} is exactly-once.
 *
 * <p><strong>Scoped by caller (finding #6):</strong> the key is unique <em>per principal</em>, not
 * globally — the composite primary key {@code (principal, key)} is the concurrency guard, and {@link
 * #save} translates its unique-key violation into {@link DuplicateIdempotencyKeyException}, which
 * {@code StorePurchaseService} catches to run the loser's replay path. With no auth gateway every
 * caller is the sentinel {@code anonymous} principal, so behavior is unchanged until one is added.
 *
 * <p><strong>No duplicated PII (finding #8):</strong> the {@code 201} body is <em>not</em> stored —
 * it is re-projected from the {@code purchases} row via {@code purchaseId} on replay, so the
 * {@code description} (PII) lives only in the ledger, never a second at-rest copy.
 */
public interface IdempotencyStore {

    /** The stored record for a {@code (principal, key)}, or empty if unseen. */
    Optional<StoredResponse> find(String principal, String key);

    /**
     * Persists {@code (principal, key)} + request hash + the created purchase's id and status, in the
     * caller's transaction (atomic with the purchase insert).
     *
     * @throws DuplicateIdempotencyKeyException if {@code (principal, key)} already exists (a concurrent winner)
     */
    void save(
            String principal,
            String key,
            String requestHash,
            UUID purchaseId,
            int responseStatus,
            Instant expiresAt);

    /**
     * Deletes up to {@code batchLimit} rows whose {@code expires_at} is strictly before {@code now},
     * returning how many were removed. Bounded so the sweep holds only short lock windows; the caller
     * loops until a partial batch signals the backlog is drained. Uses the injected {@code Clock}'s
     * {@code now} so the cutoff is deterministic in tests.
     *
     * @return the number of expired rows deleted (0..{@code batchLimit})
     */
    int deleteExpired(Instant now, int batchLimit);

    /**
     * The replayable record for a previously-seen {@code (principal, key)}: the request fingerprint (for
     * 409 detection) and the {@code purchaseId} the body is re-projected from.
     */
    record StoredResponse(String requestHash, int responseStatus, UUID purchaseId) {}
}
