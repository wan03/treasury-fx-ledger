package com.wex.fx.adapter.treasury;

import com.wex.fx.domain.rate.ExchangeRate;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Local persistence for Treasury rates (the {@code exchange_rates} table, V3) behind providers
 * <strong>B (ingest)</strong> and <strong>C (hybrid)</strong>. Two operations:
 *
 * <ul>
 *   <li>{@link #findCandidates} — the in-window read that backs local selection. An index seek on
 *       {@code (country_currency_desc, effective_date DESC)}; returns the window so the caller still
 *       runs the pure {@link com.wex.fx.domain.rate.RateSelector} over it (the SQL is the optimization,
 *       the pure function is the spec — uniform with A0/A);</li>
 *   <li>{@link #upsertAll} — the idempotent reconcile, keyed on {@code (country_currency_desc,
 *       effective_date)}: a brand-new intra-quarter amendment inserts a row; a restated rate on an
 *       existing key updates it. Re-running a sync is therefore safe and convergent (F4/F8).</li>
 * </ul>
 *
 * <p>Direct {@link NamedParameterJdbcTemplate} (not Spring Data mapping) for the {@code ON CONFLICT}
 * upsert and a batched write. The {@code app} role holds {@code SELECT/INSERT/UPDATE} here (V3) — no
 * {@code DELETE}: rate history is append-and-amend, never purged. Registered unconditionally; it is
 * inert under A0/A (which never read it), so the bean is harmless on any provider profile.
 */
@Repository
class ExchangeRateStore {

    private static final String SELECT_WINDOW =
            """
            SELECT country_currency_desc, effective_date, record_date, exchange_rate
            FROM exchange_rates
            WHERE country_currency_desc = :desc
              AND effective_date <= :onOrBefore
              AND effective_date >= :onOrAfter
            ORDER BY effective_date DESC
            """;

    private static final String UPSERT =
            """
            INSERT INTO exchange_rates (country_currency_desc, effective_date, record_date, exchange_rate)
            VALUES (:desc, :effectiveDate, :recordDate, :rate)
            ON CONFLICT (country_currency_desc, effective_date)
            DO UPDATE SET record_date   = EXCLUDED.record_date,
                          exchange_rate = EXCLUDED.exchange_rate,
                          ingested_at   = now()
            """;

    private final NamedParameterJdbcTemplate jdbc;

    ExchangeRateStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** In-window candidate rows for one descriptor; the caller selects with {@code RateSelector}. */
    List<ExchangeRate> findCandidates(String descriptor, LocalDate onOrAfter, LocalDate onOrBefore) {
        var params = new MapSqlParameterSource()
                .addValue("desc", descriptor)
                .addValue("onOrBefore", onOrBefore)
                .addValue("onOrAfter", onOrAfter);
        return jdbc.query(SELECT_WINDOW, params, (rs, rowNum) -> new ExchangeRate(
                rs.getString("country_currency_desc"),
                rs.getObject("effective_date", LocalDate.class),
                rs.getObject("record_date", LocalDate.class),
                rs.getBigDecimal("exchange_rate")));
    }

    /** Idempotent batched upsert keyed on {@code (descriptor, effective_date)} — reconciles amendments. */
    void upsertAll(List<ExchangeRate> rates) {
        if (rates.isEmpty()) {
            return;
        }
        MapSqlParameterSource[] batch = rates.stream()
                .map(r -> new MapSqlParameterSource()
                        .addValue("desc", r.countryCurrencyDesc())
                        .addValue("effectiveDate", r.effectiveDate())
                        .addValue("recordDate", r.recordDate())
                        .addValue("rate", r.exchangeRate()))
                .toArray(MapSqlParameterSource[]::new);
        jdbc.batchUpdate(UPSERT, batch);
    }
}
