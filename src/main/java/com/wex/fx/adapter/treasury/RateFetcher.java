package com.wex.fx.adapter.treasury;

import com.wex.fx.domain.rate.ExchangeRate;
import java.time.LocalDate;
import java.util.List;

/**
 * Fetches Treasury rate rows for one currency descriptor inside an {@code effective_date} window.
 * Implemented by the HTTP fetcher and wrapped by the resilience decorator. Two access shapes, both
 * over the same endpoint and tolerant mapping:
 *
 * <ul>
 *   <li>{@link #fetch} — the F7 single-row push-down (sort + {@code page[size]=1}); the providers
 *       {@code A0}/{@code A} compose it with the pure {@code RateSelector} per request;</li>
 *   <li>{@link #fetchWindow} — every row in the window (no {@code LIMIT}), so providers {@code B}/{@code C}
 *       can backfill / lazy-fill the local {@code exchange_rates} table and reconcile amendments.</li>
 * </ul>
 *
 * <p>Contract: an empty list is a <em>normal</em> "no rate in window" outcome (→ {@code 422}); an
 * upstream failure is a {@link com.wex.fx.application.error.RateProviderUnavailableException}
 * (→ {@code 502/503/504}) — the two are never collapsed (rate-selection.md §Edge &amp; failure).
 */
interface RateFetcher {

    /**
     * @param descriptor            Treasury {@code country_currency_desc} (already ISO→descriptor resolved)
     * @param effectiveOnOrBefore   upper bound — {@code effective_date <= purchaseDate}
     * @param effectiveOnOrAfter    lower bound — {@code effective_date >= purchaseDate − window}
     * @return the candidate rows (possibly empty); the caller still runs {@code RateSelector} over them
     */
    List<ExchangeRate> fetch(String descriptor, LocalDate effectiveOnOrBefore, LocalDate effectiveOnOrAfter);

    /**
     * All rows for {@code descriptor} with {@code effective_date} in {@code [from, to]} (no single-row
     * cap), so intra-quarter amendments are captured for local ingest/reconciliation. The bounds are
     * inclusive; ordering is unspecified (the caller upserts or runs {@code RateSelector}).
     *
     * @param descriptor Treasury {@code country_currency_desc}
     * @param from       inclusive lower bound on {@code effective_date}
     * @param to         inclusive upper bound on {@code effective_date}
     */
    List<ExchangeRate> fetchWindow(String descriptor, LocalDate from, LocalDate to);
}
