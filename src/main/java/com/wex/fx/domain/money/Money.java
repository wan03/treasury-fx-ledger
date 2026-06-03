package com.wex.fx.domain.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable money value object: a {@link BigDecimal} amount paired with an ISO-4217
 * currency code. It is the single guardian of the money path (constitution &sect;1, D-04):
 *
 * <ul>
 *   <li>amounts are always {@code BigDecimal} &mdash; never {@code float}/{@code double};</li>
 *   <li>every {@code Money} is normalized to <strong>scale 2, HALF_UP</strong> on construction,
 *       so the system rounds <em>exactly once</em>: for an already cent-precise principal this
 *       only pads ({@code 12.3 -> 12.30}); for a conversion product it is the final rounding;</li>
 *   <li>arithmetic across currencies is forbidden ({@link #compareTo} throws);</li>
 *   <li>value comparison uses {@code compareTo}, never the scale-sensitive {@code equals} &mdash;
 *       though normalization to scale 2 makes the record's generated {@code equals} consistent
 *       with {@code compareTo} anyway (equal values always share scale 2).</li>
 * </ul>
 *
 * <p>Deliberately tiny &mdash; no heavy money library (constitution &sect;1). The only operation the
 * domain needs is {@link #convertedAt}; addition/subtraction are intentionally absent.
 */
public record Money(BigDecimal amount, String currencyCode) implements Comparable<Money> {

    /** Output scale for all monetary values (the brief mandates 2 dp; D-04). */
    public static final int SCALE = 2;

    /** The one rounding mode in the money path (D-04). */
    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private static final Pattern ISO_4217 = Pattern.compile("[A-Z]{3}");

    public Money {
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(currencyCode, "currencyCode must not be null");
        if (!ISO_4217.matcher(currencyCode).matches()) {
            throw new IllegalArgumentException("currencyCode must match [A-Z]{3}, was: " + currencyCode);
        }
        // The ONE rounding in the money path (D-04). Pads a cent-precise principal; performs
        // the single, final rounding for a conversion product. Never pre-round a rate/intermediate.
        amount = amount.setScale(SCALE, ROUNDING);
    }

    public static Money of(BigDecimal amount, String currencyCode) {
        return new Money(amount, currencyCode);
    }

    public static Money of(String amount, String currencyCode) {
        return new Money(new BigDecimal(amount), currencyCode);
    }

    public static Money usd(String amount) {
        return of(amount, "USD");
    }

    /**
     * Convert into {@code targetCurrencyCode} at {@code rate} (foreign units per 1 unit of this
     * currency &mdash; Treasury's direction, F2, so we multiply). The product is computed at
     * <strong>full precision</strong>; the {@code Money} constructor then rounds <strong>exactly
     * once</strong> (HALF_UP, scale 2). The rate is never pre-rounded (D-04).
     */
    public Money convertedAt(BigDecimal rate, String targetCurrencyCode) {
        Objects.requireNonNull(rate, "rate must not be null");
        return new Money(amount.multiply(rate), targetCurrencyCode);
    }

    /** Same numeric value within the same currency, using {@code compareTo} (not {@code equals}). */
    public boolean isSameValueAs(Money other) {
        return currencyCode.equals(other.currencyCode) && amount.compareTo(other.amount) == 0;
    }

    /** Orders by amount within one currency; throws on a cross-currency comparison (no implicit FX). */
    @Override
    public int compareTo(Money other) {
        if (!currencyCode.equals(other.currencyCode)) {
            throw new IllegalArgumentException(
                    "cannot compare across currencies: " + currencyCode + " vs " + other.currencyCode);
        }
        return amount.compareTo(other.amount);
    }
}
