package com.wex.fx.adapter.treasury;

/**
 * The upstream returned a {@code 2xx} whose body we could not map to the rate contract — a missing
 * critical field or an unparseable rate/date (schema drift). This is <strong>not</strong> transient:
 * retrying an identical request would fail identically, so the resilience layer must not retry it and
 * must not let it trip the circuit breaker (which gauges upstream <em>health</em>). It maps to a
 * {@code 502} at the edge. The message names the offending field only — never the value (constitution §9).
 */
final class TreasuryContractException extends RuntimeException {

    TreasuryContractException(String message, Throwable cause) {
        super(message, cause);
    }
}
