package com.wex.fx.adapter.web;

/**
 * The request could not be processed as written for a reason the bean-validated body shape doesn't
 * cover — an unreadable/Jackson-rejected body, or an out-of-bounds protocol header (e.g. an
 * over-long {@code Idempotency-Key}). Maps to {@code 400 MALFORMED_REQUEST}.
 *
 * <p>The message states the rule, never echoes the offending value, so it is safe to surface as the
 * problem {@code detail} and to log (constitution §5).
 */
public final class MalformedRequestException extends RuntimeException {

    public MalformedRequestException(String message) {
        super(message);
    }
}
