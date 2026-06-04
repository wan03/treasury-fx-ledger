package com.wex.fx.application.error;

/**
 * The target is a well-formed ISO-4217 code but not in the curated map &rArr;
 * {@code 422 CURRENCY_UNSUPPORTED}. Well-formed but unfulfillable (we have no Treasury descriptor).
 */
public class UnsupportedCurrencyException extends RuntimeException {

    private final transient String currencyCode;

    public UnsupportedCurrencyException(String currencyCode) {
        super("currency not supported: " + currencyCode);
        this.currencyCode = currencyCode;
    }

    public String currencyCode() {
        return currencyCode;
    }
}
