package com.wex.fx.application.error;

/**
 * Internal signal: an idempotency-row insert hit the primary-key guard because a concurrent request
 * already committed the same key. The JDBC adapter translates the DB unique-violation into this
 * (keeping Spring's {@code DuplicateKeyException} out of the application), and
 * {@code StorePurchaseService} catches it to run the loser's replay-read. Not mapped to an HTTP status.
 */
public class DuplicateIdempotencyKeyException extends RuntimeException {

    private final transient String key;

    public DuplicateIdempotencyKeyException(String key, Throwable cause) {
        super("idempotency key already present: " + key, cause);
        this.key = key;
    }

    public String key() {
        return key;
    }
}
