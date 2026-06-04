package com.wex.fx.application.dto;

/**
 * Raw create-purchase inputs as received at the edge — strings, deliberately unparsed, so the domain
 * validator owns every parse/shape decision (reject-not-round amounts, strict ISO dates).
 *
 * @param description     raw description (validated: 1&ndash;50 code points, trimmed, no control chars)
 * @param amount          raw decimal string (validated: {@code ^\d{1,17}(\.\d{1,2})?$}, &gt; 0, within cap)
 * @param transactionDate raw ISO date string (validated: strict {@code uuuu-MM-dd}, not future)
 * @param currency        optional ISO code; {@code null} defaults to USD; non-USD &rArr; not storable
 */
public record StorePurchaseCommand(
        String description, String amount, String transactionDate, String currency) {

    /** The only currency a purchase may be stored in (D-07). */
    public static final String DEFAULT_CURRENCY = "USD";

    /** The effective currency: the supplied one, or USD when omitted. */
    public String currencyOrDefault() {
        return currency == null || currency.isBlank() ? DEFAULT_CURRENCY : currency;
    }
}
