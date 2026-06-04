package com.wex.fx.adapter.treasury;

import com.wex.fx.domain.rate.ExchangeRate;
import java.time.LocalDate;
import java.util.List;

/**
 * Fetches candidate Treasury rate rows for one currency descriptor inside an {@code effective_date}
 * window. Implemented by the HTTP fetcher (F7 push-down) and wrapped by the resilience decorator;
 * the providers ({@code A0}/{@code A}) compose it with the pure {@code RateSelector}.
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
}
