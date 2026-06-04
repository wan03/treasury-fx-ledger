package com.wex.fx.application.error;

/**
 * The same {@code Idempotency-Key} was reused with a <em>different</em> request payload &rArr;
 * {@code 409 IDEMPOTENCY_CONFLICT}. The key is bound to its first request's fingerprint; a mismatch is
 * a client error, never a silent overwrite.
 */
public class IdempotencyConflictException extends RuntimeException {

    private final transient String key;

    public IdempotencyConflictException(String key) {
        super("idempotency key reused with a different payload");
        this.key = key;
    }

    public String key() {
        return key;
    }
}
