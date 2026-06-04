package com.wex.fx.application.error;

import java.time.LocalDate;
import java.util.UUID;

/**
 * No Treasury rate is active on/before the purchase date within the 6-month window &rArr;
 * {@code 422 NO_RATE_AVAILABLE} (R2's mandated error). A normal, well-formed-but-unfulfillable
 * outcome: the {@code RateSelector}/provider returns empty and the application raises this at the
 * service boundary (matching the existing {@code ValidationException} convention).
 */
public class NoRateAvailableException extends RuntimeException {

    private final transient UUID purchaseId;
    private final transient String targetCurrency;
    private final transient LocalDate purchaseDate;

    public NoRateAvailableException(UUID purchaseId, String targetCurrency, LocalDate purchaseDate) {
        super("no rate within 6 months for " + targetCurrency + " on/before " + purchaseDate);
        this.purchaseId = purchaseId;
        this.targetCurrency = targetCurrency;
        this.purchaseDate = purchaseDate;
    }

    public UUID purchaseId() {
        return purchaseId;
    }

    public String targetCurrency() {
        return targetCurrency;
    }

    public LocalDate purchaseDate() {
        return purchaseDate;
    }
}
