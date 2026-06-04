package com.wex.fx.application.error;

/**
 * A {@code POST} supplied a non-USD {@code currency} &rArr; {@code 422 CURRENCY_NOT_STORABLE}. Only USD
 * is ever stored; conversion is a read-time projection, never persisted (D-07).
 */
public class CurrencyNotStorableException extends RuntimeException {

    private final transient String currencyCode;

    public CurrencyNotStorableException(String currencyCode) {
        super("only USD purchases are storable");
        this.currencyCode = currencyCode;
    }

    public String currencyCode() {
        return currencyCode;
    }
}
