package com.wex.fx.application.dto;

/**
 * The result of a create-purchase attempt. {@code response} is the body to return either way; the
 * {@code replayed} flag distinguishes a fresh create from an idempotent replay so the web layer can
 * surface it (e.g. an {@code Idempotency-Replayed} header) — the status stays {@code 201} for both.
 */
public record StoreOutcome(PurchaseResponse response, boolean replayed) {

    public static StoreOutcome created(PurchaseResponse response) {
        return new StoreOutcome(response, false);
    }

    public static StoreOutcome replayed(PurchaseResponse response) {
        return new StoreOutcome(response, true);
    }
}
