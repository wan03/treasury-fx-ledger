package com.wex.fx.application.error;

import java.util.UUID;

/** No purchase exists for the given id &rArr; {@code 404 PURCHASE_NOT_FOUND}. Carries only the id (no PII). */
public class PurchaseNotFoundException extends RuntimeException {

    private final transient UUID purchaseId;

    public PurchaseNotFoundException(UUID purchaseId) {
        super("purchase not found: " + purchaseId);
        this.purchaseId = purchaseId;
    }

    public UUID purchaseId() {
        return purchaseId;
    }
}
