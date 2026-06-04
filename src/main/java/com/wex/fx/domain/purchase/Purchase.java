package com.wex.fx.domain.purchase;

import com.wex.fx.domain.money.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * A stored, immutable USD purchase record (R1). The domain aggregate — pure, framework-free, so the
 * append-only invariant and the USD-only rule live here, not in the persistence adapter.
 *
 * <p>{@code amount} is always USD: conversion is a read-time projection (R2) and is <em>never</em>
 * persisted, so the principal a purchase carries is the original USD value at cent precision.
 *
 * @param id              server-generated UUIDv7 (D-08), known before the DB round-trip
 * @param description     1&ndash;50 code points, already trimmed/validated at the edge
 * @param transactionDate the purchase date (may be old; never in the future)
 * @param amount          the USD principal at scale 2
 * @param createdAt       server ingest instant, sourced from the injected {@code Clock}
 */
public record Purchase(
        UUID id, String description, LocalDate transactionDate, Money amount, Instant createdAt) {

    public Purchase {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(transactionDate, "transactionDate");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(createdAt, "createdAt");
        if (!"USD".equals(amount.currencyCode())) {
            // Only USD is ever stored; a non-USD principal is a programming error, not bad input.
            throw new IllegalArgumentException("purchases store USD principal only, got " + amount.currencyCode());
        }
    }
}
