package com.wex.fx.adapter.treasury;

import com.wex.fx.application.port.ExchangeRateProvider;
import com.wex.fx.domain.rate.ExchangeRate;
import com.wex.fx.domain.rate.RateSelector;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Provider <strong>A0</strong> (D-03): one Treasury call per request, no cache. It composes the shared
 * {@link RateFetcher} (which pushes the whole rule to the server, F7) with the pure {@link RateSelector}
 * — because the server filter is an <em>optimization</em>, the pure function is the spec, so we still run
 * {@code select} over whatever rows come back (rate-selection.md). The simplest variant; its weakness is
 * full availability/latency coupling to Treasury. A (caching) decorates this.
 */
public final class PassthroughExchangeRateProvider implements ExchangeRateProvider {

    private final RateFetcher fetcher;
    private final RateSelector selector;

    public PassthroughExchangeRateProvider(RateFetcher fetcher, RateSelector selector) {
        this.fetcher = fetcher;
        this.selector = selector;
    }

    @Override
    public Optional<ExchangeRate> findRate(String countryCurrencyDesc, LocalDate purchaseDate) {
        LocalDate floor = selector.windowFloor(purchaseDate);
        List<ExchangeRate> candidates = fetcher.fetch(countryCurrencyDesc, purchaseDate, floor);
        return selector.select(candidates, purchaseDate);
    }
}
