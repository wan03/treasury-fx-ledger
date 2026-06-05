package com.wex.fx.application.dto;

/**
 * The idempotency inputs for a create: the resolved caller {@code principal} (the scope the key is
 * unique within — finding #6), the client-supplied {@code Idempotency-Key}, and the SHA-256 fingerprint
 * of the canonical request body (computed at the web edge, where serialization lives). A {@code null}
 * {@link IdempotencyRequest} means the client sent no key and wants a plain create; {@code principal}
 * defaults to a sentinel when no auth gateway supplies one.
 */
public record IdempotencyRequest(String principal, String key, String requestHash) {}
