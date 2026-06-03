package com.wex.fx.domain.rate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the crown-jewel {@link RateSelector} (T2.5, rate-selection.md). Pure function over
 * candidate rows &mdash; the highest-value tests in the suite, so the mutation threshold (PIT) lands
 * here. Covers the Argentina intra-quarter amendment, the leap-year boundary, inclusivity, and the
 * deterministic tiebreak.
 */
class RateSelectorTest {

    private final RateSelector selector = RateSelector.withDefaultWindow();

    private static ExchangeRate rate(String effective, String value) {
        // recordDate defaults to effectiveDate unless a tiebreak case needs otherwise.
        return new ExchangeRate("Argentina-Peso", LocalDate.parse(effective), LocalDate.parse(effective),
                new BigDecimal(value));
    }

    private static ExchangeRate rate(String effective, String record, String value) {
        return new ExchangeRate("Argentina-Peso", LocalDate.parse(effective), LocalDate.parse(record),
                new BigDecimal(value));
    }

    // --- the headline fixture: intra-quarter amendment (AC-2.2) ----------------------------------

    @Test
    void argentina_amendment_selects_the_amended_rate_for_an_in_between_purchase() {
        List<ExchangeRate> candidates = List.of(
                rate("2025-03-31", "1093.0"),
                rate("2025-04-15", "1230.0"),   // the amendment
                rate("2025-06-30", "1205.0"));

        Optional<ExchangeRate> chosen = selector.select(candidates, LocalDate.parse("2025-05-01"));

        assertThat(chosen).isPresent();
        assertThat(chosen.get().exchangeRate()).isEqualByComparingTo("1230.0");  // not 1093, not 1205
    }

    @Test
    void later_purchase_excludes_a_not_yet_effective_amendment() {
        List<ExchangeRate> candidates = List.of(
                rate("2025-03-31", "1093.0"),
                rate("2025-04-15", "1230.0"),
                rate("2025-06-30", "1205.0"),
                rate("2025-08-31", "1345.0"));   // future amendment, not yet effective

        Optional<ExchangeRate> chosen = selector.select(candidates, LocalDate.parse("2025-07-15"));

        assertThat(chosen).map(ExchangeRate::exchangeRate)
                .contains(new BigDecimal("1205.0"));
    }

    // --- exact / latest-earlier selection --------------------------------------------------------

    @Test
    void exact_effective_date_match_is_selected() {
        List<ExchangeRate> candidates = List.of(rate("2025-04-15", "1230.0"), rate("2025-03-31", "1093.0"));
        assertThat(selector.select(candidates, LocalDate.parse("2025-04-15")))
                .map(ExchangeRate::exchangeRate).contains(new BigDecimal("1230.0"));
    }

    @Test
    void with_no_exact_match_the_latest_earlier_in_window_wins() {
        List<ExchangeRate> candidates = List.of(rate("2025-03-31", "1093.0"), rate("2025-04-15", "1230.0"));
        assertThat(selector.select(candidates, LocalDate.parse("2025-04-20")))
                .map(ExchangeRate::exchangeRate).contains(new BigDecimal("1230.0"));
    }

    // --- the 6-month boundary: inclusive, calendar-month, leap-year aware (AC-2.3) ---------------

    @Test
    void window_floor_uses_calendar_month_arithmetic_into_a_leap_day() {
        assertThat(selector.windowFloor(LocalDate.parse("2024-08-31")))
                .isEqualTo(LocalDate.parse("2024-02-29"));   // leap year
        assertThat(selector.windowFloor(LocalDate.parse("2023-08-31")))
                .isEqualTo(LocalDate.parse("2023-02-28"));   // non-leap year
    }

    @Test
    void a_rate_exactly_on_the_leap_day_floor_is_included() {
        List<ExchangeRate> candidates = List.of(rate("2024-02-29", "10.0"));
        assertThat(selector.select(candidates, LocalDate.parse("2024-08-31")))
                .map(ExchangeRate::exchangeRate).contains(new BigDecimal("10.0"));
    }

    @Test
    void a_rate_one_day_before_the_floor_is_excluded() {
        List<ExchangeRate> candidates = List.of(rate("2024-02-28", "10.0"));   // floor is 2024-02-29
        assertThat(selector.select(candidates, LocalDate.parse("2024-08-31"))).isEmpty();
    }

    @Test
    void non_leap_floor_is_inclusive_and_one_day_earlier_is_out() {
        // 2023-08-31 floor = 2023-02-28.
        assertThat(selector.select(List.of(rate("2023-02-28", "10.0")), LocalDate.parse("2023-08-31")))
                .isPresent();
        assertThat(selector.select(List.of(rate("2023-02-27", "10.0")), LocalDate.parse("2023-08-31")))
                .isEmpty();
    }

    // --- empty outcomes (a NORMAL business result -> 422, never an exception) ---------------------

    @Test
    void no_candidate_in_window_yields_empty() {
        List<ExchangeRate> candidates = List.of(rate("2020-01-01", "5.0"));
        assertThat(selector.select(candidates, LocalDate.parse("2025-05-01"))).isEmpty();
    }

    @Test
    void empty_and_null_candidate_lists_yield_empty() {
        assertThat(selector.select(List.of(), LocalDate.parse("2025-05-01"))).isEmpty();
        assertThat(selector.select(null, LocalDate.parse("2025-05-01"))).isEmpty();
    }

    // --- deterministic tiebreak for pathological duplicate effective_dates -----------------------

    @Test
    void duplicate_effective_dates_break_ties_by_record_date() {
        List<ExchangeRate> candidates = List.of(
                rate("2025-04-15", "2025-04-15", "1230.0"),
                rate("2025-04-15", "2025-04-20", "1240.0"));   // same effective, later record wins

        assertThat(selector.select(candidates, LocalDate.parse("2025-05-01")))
                .map(ExchangeRate::exchangeRate).contains(new BigDecimal("1240.0"));
    }

    // --- construction guard: a non-positive window is a misconfiguration, fail fast --------------

    @Test
    void constructor_rejects_a_non_positive_window() {
        assertThatThrownBy(() -> new RateSelector(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RateSelector(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
