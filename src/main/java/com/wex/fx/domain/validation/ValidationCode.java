package com.wex.fx.domain.validation;

/**
 * Stable machine codes for edge-validation failures (api-contract.md error catalog). The enum
 * {@code name()} <strong>is</strong> the wire code, so the web layer maps these straight into the
 * RFC 9457 {@code errors[]} array without a translation table. All of these are {@code 400}
 * (malformed / cannot-process-as-written); the {@code 422} currency outcomes live with the currency
 * resolution, not here.
 */
public enum ValidationCode {
    DESCRIPTION_TOO_LONG,
    DESCRIPTION_INVALID,
    AMOUNT_PRECISION,
    AMOUNT_NOT_POSITIVE,
    AMOUNT_MALFORMED,
    DATE_INVALID,
    DATE_IN_FUTURE
}
