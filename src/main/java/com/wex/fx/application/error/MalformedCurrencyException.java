package com.wex.fx.application.error;

/**
 * The target currency token is not {@code ^[A-Z]{3}$} (lowercase, wrong length, digits) &rArr;
 * {@code 400 CURRENCY_CODE_MALFORMED}. Malformed input — distinct from a well-formed-but-unsupported code.
 */
public class MalformedCurrencyException extends RuntimeException {

    private final transient String currencyCode;

    public MalformedCurrencyException(String currencyCode) {
        super("malformed currency code");
        this.currencyCode = currencyCode;
    }

    public String currencyCode() {
        return currencyCode;
    }
}
