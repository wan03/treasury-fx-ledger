package com.wex.fx.domain.validation;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The parsed, validated result of a store-purchase request: trimmed {@code description}, the
 * {@code amount} as a {@link BigDecimal} (cent-precise, &le; 2 dp, positive), and a strictly-parsed
 * {@code transactionDate}. Currency is implicitly USD (the only storable currency, D-05).
 */
public record ValidatedPurchase(String description, BigDecimal amount, LocalDate transactionDate) {
}
