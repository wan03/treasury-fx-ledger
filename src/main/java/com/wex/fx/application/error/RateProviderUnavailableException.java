package com.wex.fx.application.error;

/**
 * The exchange-rate provider could not produce an answer because the upstream dependency failed —
 * distinct from a successful "no rate in the 6-month window" (which is {@link NoRateAvailableException}
 * → {@code 422}). An <em>outage</em> is a resilience concern the web layer maps to {@code 502/503/504};
 * the two outcomes are never collapsed (rate-selection.md §Edge &amp; failure; constitution §7).
 *
 * <p>Carries only a coarse {@link Reason} (no upstream URL, body, or PII — constitution §9). The cause
 * is retained for server-side logging but never surfaced to the client.
 */
public final class RateProviderUnavailableException extends RuntimeException {

    /** Why the provider failed — drives the HTTP status the web layer returns. */
    public enum Reason {
        /** Upstream returned a server error (5xx) after the bounded retries were exhausted. → {@code 502}. */
        UPSTREAM_ERROR,
        /** Upstream did not respond within the read timeout. → {@code 504}. */
        TIMEOUT,
        /** The circuit breaker is open — we are failing fast without calling upstream. → {@code 503}. */
        CIRCUIT_OPEN,
        /** The concurrency bulkhead is saturated — too many in-flight upstream calls. → {@code 503}. */
        OVERLOADED
    }

    private final transient Reason reason;

    public RateProviderUnavailableException(Reason reason, Throwable cause) {
        super("exchange-rate provider unavailable: " + reason, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
