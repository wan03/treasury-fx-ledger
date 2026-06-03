package com.wex.fx.domain.rate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * A single Treasury <em>Reporting Rates of Exchange</em> row, reduced to the four fields the
 * selection rule needs (rate-selection.md). Keyed conceptually by
 * {@code (countryCurrencyDesc, effectiveDate)}.
 *
 * <p>{@code effectiveDate} is authoritative for selection (D-02, F4/F8): Treasury issues
 * intra-quarter <em>amendments</em> as extra rows that share a {@code recordDate} but carry a new,
 * later {@code effectiveDate}. {@code recordDate} is retained only as a deterministic tiebreak.
 *
 * <p>{@code exchangeRate} is parsed from Treasury's <strong>string</strong> form into
 * {@link BigDecimal} with no assumed scale (observed 2&ndash;4 dp, F2).
 */
public record ExchangeRate(
        String countryCurrencyDesc,
        LocalDate effectiveDate,
        LocalDate recordDate,
        BigDecimal exchangeRate) {

    public ExchangeRate {
        Objects.requireNonNull(countryCurrencyDesc, "countryCurrencyDesc must not be null");
        Objects.requireNonNull(effectiveDate, "effectiveDate must not be null");
        Objects.requireNonNull(recordDate, "recordDate must not be null");
        Objects.requireNonNull(exchangeRate, "exchangeRate must not be null");
        if (exchangeRate.signum() <= 0) {
            throw new IllegalArgumentException("exchangeRate must be positive, was: " + exchangeRate);
        }
    }
}
