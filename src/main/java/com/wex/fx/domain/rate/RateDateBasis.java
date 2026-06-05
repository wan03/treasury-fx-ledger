package com.wex.fx.domain.rate;

import java.time.LocalDate;
import java.util.function.Function;

/**
 * Which Treasury date governs rate selection — the one genuinely ambiguous reading the brief leaves open
 * (D-02). Both readings share the same machinery (window, bounds, "latest wins"); they differ only in
 * <em>which</em> date the rule is measured on.
 *
 * <ul>
 *   <li>{@link #EFFECTIVE_DATE} — the default and the authoritative reading (F4/F8, source-quoted):
 *       Treasury issues an intra-quarter <em>amendment</em> as an extra row sharing a {@code recordDate}
 *       but carrying a new, later {@code effectiveDate}, and that amendment governs transactions dated
 *       on/after it. Selecting on {@code effectiveDate} is the only reading that rates a post-amendment
 *       purchase correctly.</li>
 *   <li>{@link #RECORD_DATE} — the literal wording of the brief ("a rate whose {@code record_date} is
 *       ≤ purchase date"). Provided so the naive reading is a one-line config flip, not a fork.</li>
 * </ul>
 *
 * <p>The two bases return <strong>identical</strong> results for every non-amended currency (where
 * {@code recordDate == effectiveDate}); they diverge only across an amendment.
 */
public enum RateDateBasis {
    EFFECTIVE_DATE(ExchangeRate::effectiveDate, ExchangeRate::recordDate),
    RECORD_DATE(ExchangeRate::recordDate, ExchangeRate::effectiveDate);

    private final Function<ExchangeRate, LocalDate> primary;
    private final Function<ExchangeRate, LocalDate> tiebreak;

    RateDateBasis(Function<ExchangeRate, LocalDate> primary, Function<ExchangeRate, LocalDate> tiebreak) {
        this.primary = primary;
        this.tiebreak = tiebreak;
    }

    /** The date this basis selects on (filtered against the window and maximised). */
    public LocalDate of(ExchangeRate rate) {
        return primary.apply(rate);
    }

    /** The other date, used only as a deterministic tiebreak when two rows share a primary date (F8). */
    public LocalDate tiebreak(ExchangeRate rate) {
        return tiebreak.apply(rate);
    }
}
