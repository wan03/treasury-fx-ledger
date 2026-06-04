package com.wex.fx.adapter.persistence;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Spring Data JDBC row mapping for {@code purchases}. Kept separate from the pure-domain
 * {@code Purchase} so the framework annotations never leak inward (ArchUnit boundary). Money is stored
 * split as {@code amount}{@code NUMERIC(19,2)} + {@code currency}, and {@code created_at} as
 * {@code timestamptz} via {@link OffsetDateTime} (UTC) for a clean, drift-free round-trip.
 */
@Table("purchases")
record PurchaseRow(
        @Id UUID id,
        String description,
        LocalDate transactionDate,
        BigDecimal amount,
        String currency,
        OffsetDateTime createdAt) {}
