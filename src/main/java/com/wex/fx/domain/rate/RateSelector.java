package com.wex.fx.domain.rate;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The crown jewel (D-02, rate-selection.md): a <strong>pure function</strong> that picks the
 * Treasury rate to apply to a purchase. No HTTP, no Spring, no {@code Clock} &mdash; deterministic
 * over the candidate rows, so it is exhaustively unit-testable.
 *
 * <p>Rule: choose the rate with the <strong>greatest {@code effectiveDate} that is
 * {@code <= purchaseDate}</strong> and {@code >=} {@code purchaseDate} minus the window
 * (default 6 <em>calendar</em> months, inclusive). An empty result is a normal business outcome
 * ({@code 422 NO_RATE_AVAILABLE}), not an exception.
 *
 * <p>Even when an adapter pushes the filter down to the server (F7), the caller should still run
 * this function over whatever rows come back &mdash; the push-down is an optimization, this is the
 * spec.
 */
public final class RateSelector {

    public static final int DEFAULT_WINDOW_MONTHS = 6;

    private final int windowMonths;

    public RateSelector(int windowMonths) {
        if (windowMonths <= 0) {
            throw new IllegalArgumentException("windowMonths must be positive, was: " + windowMonths);
        }
        this.windowMonths = windowMonths;
    }

    public static RateSelector withDefaultWindow() {
        return new RateSelector(DEFAULT_WINDOW_MONTHS);
    }

    /**
     * Select the applicable rate, or {@link Optional#empty()} if none falls in
     * {@code [windowFloor(purchaseDate), purchaseDate]}.
     */
    public Optional<ExchangeRate> select(List<ExchangeRate> candidates, LocalDate purchaseDate) {
        Objects.requireNonNull(purchaseDate, "purchaseDate must not be null");
        if (candidates == null || candidates.isEmpty()) {
            return Optional.empty();
        }
        LocalDate floor = windowFloor(purchaseDate);
        return candidates.stream()
                .filter(r -> !r.effectiveDate().isAfter(purchaseDate))  // effectiveDate <= purchaseDate
                .filter(r -> !r.effectiveDate().isBefore(floor))        // effectiveDate >= floor (inclusive)
                .max(Comparator.comparing(ExchangeRate::effectiveDate)  // latest effectiveDate wins
                        .thenComparing(ExchangeRate::recordDate));      // deterministic tiebreak (F8)
    }

    /**
     * Inclusive lower bound of the window: {@code purchaseDate} minus the configured months, using
     * calendar-month arithmetic (not 180 days). Also drives the adapter's push-down query (F7).
     */
    public LocalDate windowFloor(LocalDate purchaseDate) {
        return purchaseDate.minusMonths(windowMonths);
    }
}
