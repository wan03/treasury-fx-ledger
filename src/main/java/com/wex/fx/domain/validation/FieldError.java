package com.wex.fx.domain.validation;

/**
 * One field-level validation failure: which {@code field}, a stable machine {@code code}, and a
 * human {@code message} that <strong>describes the rule, never echoes the value</strong> (no
 * amounts or {@code description} text &mdash; constitution &sect;5, CC-2). Surfaces in the RFC 9457
 * {@code errors[]} array.
 */
public record FieldError(String field, ValidationCode code, String message) {
}
