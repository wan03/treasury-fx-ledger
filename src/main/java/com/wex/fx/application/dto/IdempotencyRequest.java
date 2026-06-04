package com.wex.fx.application.dto;

/**
 * The idempotency inputs for a create: the client-supplied {@code Idempotency-Key} and the SHA-256
 * fingerprint of the canonical request body (computed at the web edge, where serialization lives). A
 * {@code null} value means the client sent no key and wants a plain create.
 */
public record IdempotencyRequest(String key, String requestHash) {}
