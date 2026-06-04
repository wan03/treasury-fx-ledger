package com.wex.fx.application.dto;

import com.wex.fx.domain.purchase.Purchase;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The canonical stored-purchase view, shared by the {@code 201} create body, the {@code GET} body, and
 * the idempotency replay record (api-contract.md). Typed fields; the web layer's Jackson policy
 * renders money as a JSON string and dates as ISO-8601.
 */
public record PurchaseResponse(
        UUID id,
        String description,
        LocalDate transactionDate,
        BigDecimal amount,
        String currency,
        Instant createdAt) {

    public static PurchaseResponse from(Purchase purchase) {
        return new PurchaseResponse(
                purchase.id(),
                purchase.description(),
                purchase.transactionDate(),
                purchase.amount().amount(),
                purchase.amount().currencyCode(),
                purchase.createdAt());
    }
}
