package com.wex.fx.application.support;

import com.wex.fx.application.error.DuplicateIdempotencyKeyException;
import com.wex.fx.application.port.IdempotencyStore;
import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * In-memory {@link IdempotencyStore} double covering the sequential idempotency paths (replay /
 * conflict): {@link #save} enforces the composite-key guard by throwing
 * {@link DuplicateIdempotencyKeyException} when a {@code (principal, key)} is reused. Keyed by
 * {@code (principal, key)} and storing only the {@code purchaseId} (no body), mirroring the real store.
 */
public final class InMemoryIdempotencyStore implements IdempotencyStore {

    private final Map<String, StoredResponse> byKey = new HashMap<>();
    private final Map<String, Instant> expiryByKey = new HashMap<>();

    // Composite map key; test principals/keys never contain a pipe, so this is unambiguous.
    private static String compositeKey(String principal, String key) {
        return principal + "|" + key;
    }

    @Override
    public Optional<StoredResponse> find(String principal, String key) {
        return Optional.ofNullable(byKey.get(compositeKey(principal, key)));
    }

    @Override
    public void save(
            String principal,
            String key,
            String requestHash,
            UUID purchaseId,
            int responseStatus,
            Instant expiresAt) {
        String compositeKey = compositeKey(principal, key);
        if (byKey.containsKey(compositeKey)) {
            throw new DuplicateIdempotencyKeyException(key, null);
        }
        byKey.put(compositeKey, new StoredResponse(requestHash, responseStatus, purchaseId));
        expiryByKey.put(compositeKey, expiresAt);
    }

    @Override
    public int deleteExpired(Instant now, int batchLimit) {
        int deleted = 0;
        for (Iterator<Map.Entry<String, Instant>> it = expiryByKey.entrySet().iterator();
                it.hasNext() && deleted < batchLimit; ) {
            Map.Entry<String, Instant> entry = it.next();
            if (entry.getValue().isBefore(now)) {
                byKey.remove(entry.getKey());
                it.remove();
                deleted++;
            }
        }
        return deleted;
    }
}
