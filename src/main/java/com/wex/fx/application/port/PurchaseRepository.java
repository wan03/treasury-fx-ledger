package com.wex.fx.application.port;

import com.wex.fx.domain.purchase.Purchase;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port for the append-only purchase ledger. The application depends on this interface, never
 * on Spring Data — the JDBC adapter implements it (plan.md, hexagonal boundary). Append-only by
 * design: there is deliberately no {@code update} or {@code delete}.
 */
public interface PurchaseRepository {

    /** Inserts a new purchase (its id is already assigned). Returns the persisted aggregate. */
    Purchase save(Purchase purchase);

    /** Loads a purchase by id, or empty if no such id exists. */
    Optional<Purchase> findById(UUID id);
}
