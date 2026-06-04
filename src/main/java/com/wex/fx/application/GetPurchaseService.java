package com.wex.fx.application;

import com.wex.fx.application.dto.PurchaseResponse;
import com.wex.fx.application.error.PurchaseNotFoundException;
import com.wex.fx.application.port.PurchaseRepository;
import java.util.UUID;

/**
 * Use case — fetch a stored purchase by id (the USD view, R1's read side). A trivial read, but it
 * stays an application service rather than letting the web adapter reach the {@link PurchaseRepository}
 * port directly: the inbound adapter depends on a use case, never on an outbound port, so the hexagon's
 * dependency direction holds and "unknown id → 404" lives in one place.
 *
 * <p>Pure application service — no Spring annotations; assembled in {@code config.ApplicationWiring}.
 */
public class GetPurchaseService {

    private final PurchaseRepository purchases;

    public GetPurchaseService(PurchaseRepository purchases) {
        this.purchases = purchases;
    }

    /**
     * Returns the stored purchase as the canonical response view.
     *
     * @throws PurchaseNotFoundException if no purchase has that id (&rarr; 404)
     */
    public PurchaseResponse get(UUID id) {
        return purchases.findById(id)
                .map(PurchaseResponse::from)
                .orElseThrow(() -> new PurchaseNotFoundException(id));
    }
}
