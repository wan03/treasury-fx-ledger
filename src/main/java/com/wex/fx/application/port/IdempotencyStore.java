package com.wex.fx.application.port;

import com.wex.fx.application.dto.PurchaseResponse;
import com.wex.fx.application.error.DuplicateIdempotencyKeyException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port for the {@code idempotency_keys} table (D-08/D-09). Stores, per client-supplied key,
 * the request fingerprint and the exact response to replay, so a retried {@code POST} is exactly-once.
 *
 * <p>The primary key on {@code key} is the concurrency guard: {@link #save} translates a unique-key
 * violation into {@link DuplicateIdempotencyKeyException}, which {@code StorePurchaseService} catches
 * to run the loser's replay path. The stored {@link PurchaseResponse} body is reconstructed on read,
 * so replay stays correct regardless of the wire-serialization policy.
 */
public interface IdempotencyStore {

    /** The stored response for a key, or empty if unseen. */
    Optional<StoredResponse> find(String key);

    /**
     * Persists the key + request hash + response, in the caller's transaction (atomic with the
     * purchase insert).
     *
     * @throws DuplicateIdempotencyKeyException if the key already exists (a concurrent winner)
     */
    void save(
            String key,
            String requestHash,
            UUID purchaseId,
            int responseStatus,
            PurchaseResponse responseBody,
            Instant expiresAt);

    /** The replayable record for a previously-seen idempotency key. */
    record StoredResponse(String requestHash, int responseStatus, PurchaseResponse responseBody) {}
}
