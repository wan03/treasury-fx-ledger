package com.wex.fx.application.support;

import com.wex.fx.application.dto.PurchaseResponse;
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
 * conflict): {@link #save} enforces the primary-key guard by throwing
 * {@link DuplicateIdempotencyKeyException} when a key is reused. (The concurrent-race path is
 * exercised with a purpose-built double in the service test.)
 */
public final class InMemoryIdempotencyStore implements IdempotencyStore {

    private final Map<String, StoredResponse> byKey = new HashMap<>();
    private final Map<String, Instant> expiryByKey = new HashMap<>();

    @Override
    public Optional<StoredResponse> find(String key) {
        return Optional.ofNullable(byKey.get(key));
    }

    @Override
    public void save(
            String key,
            String requestHash,
            UUID purchaseId,
            int responseStatus,
            PurchaseResponse responseBody,
            Instant expiresAt) {
        if (byKey.containsKey(key)) {
            throw new DuplicateIdempotencyKeyException(key, null);
        }
        byKey.put(key, new StoredResponse(requestHash, responseStatus, responseBody));
        expiryByKey.put(key, expiresAt);
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
