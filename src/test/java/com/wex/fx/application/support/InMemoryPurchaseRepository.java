package com.wex.fx.application.support;

import com.wex.fx.application.port.PurchaseRepository;
import com.wex.fx.domain.purchase.Purchase;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** In-memory {@link PurchaseRepository} test double. {@code stored} is exposed for assertions. */
public final class InMemoryPurchaseRepository implements PurchaseRepository {

    public final Map<UUID, Purchase> stored = new LinkedHashMap<>();

    @Override
    public Purchase save(Purchase purchase) {
        stored.put(purchase.id(), purchase);
        return purchase;
    }

    @Override
    public Optional<Purchase> findById(UUID id) {
        return Optional.ofNullable(stored.get(id));
    }
}
