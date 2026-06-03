package com.wex.fx.domain.validation;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Thrown when one or more edge-validation rules fail. Carries the full {@link FieldError} list so
 * the web layer can render an RFC 9457 body with a populated {@code errors[]} array.
 *
 * <p>The exception <em>message</em> is intentionally PII-free &mdash; only {@code field:CODE} pairs,
 * never the offending value &mdash; so it is safe to log (constitution &sect;5, CC-2).
 */
public final class ValidationException extends RuntimeException {

    private final transient List<FieldError> errors;

    public ValidationException(List<FieldError> errors) {
        super(summarize(errors));
        this.errors = List.copyOf(errors);
    }

    public ValidationException(FieldError error) {
        this(List.of(error));
    }

    public List<FieldError> errors() {
        return errors;
    }

    private static String summarize(List<FieldError> errors) {
        return errors.stream()
                .map(e -> e.field() + ":" + e.code())
                .collect(Collectors.joining(", ", "validation failed [", "]"));
    }
}
