package com.wex.fx.application.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The converted-purchase projection (R2, api-contract.md). Returns the brief's required fields plus
 * {@code rateEffectiveDate} + {@code rateSource} for auditability. Never persisted — computed per read.
 *
 * <p>For a USD target this is the in-app identity: {@code exchangeRate = 1.00}, converted = original,
 * {@code rateEffectiveDate} null (no upstream rate), and a source noting the identity (D-07).
 */
public record ConversionResponse(
        UUID purchaseId,
        String description,
        LocalDate transactionDate,
        BigDecimal originalAmount,
        String originalCurrency,
        String targetCurrency,
        BigDecimal exchangeRate,
        LocalDate rateEffectiveDate,
        BigDecimal convertedAmount,
        String rateSource) {

    /** Provenance string for a genuine Treasury conversion. */
    public static final String TREASURY_SOURCE = "U.S. Treasury Reporting Rates of Exchange";

    /** Provenance string for the USD identity (no upstream call). */
    public static final String IDENTITY_SOURCE = "In-app USD identity (no upstream rate)";
}
