package com.wex.fx.adapter.treasury;

import com.wex.fx.application.port.ExchangeRateProvider;
import com.wex.fx.domain.rate.ExchangeRate;
import com.wex.fx.domain.rate.RateSelector;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Provider <strong>B</strong> — ingest (D-03). Reads <em>purely</em> from the local
 * {@code exchange_rates} table; it never touches Treasury on the request path, so runtime availability
 * and latency are fully decoupled from the upstream (the scale/SLA posture — plan.md). A separate
 * {@link RateSyncService} backfills and reconciles the table out of band.
 *
 * <p>Like A0/A it fetches the in-window candidates and runs the pure {@link RateSelector} over them —
 * the indexed query is the optimization, the pure function is the spec (so all four providers share one
 * selection rule). A local miss is a genuine "no rate in window" → {@code Optional.empty()} → {@code 422};
 * because there is no upstream call, B can never raise an availability error from a read.
 */
public final class IngestExchangeRateProvider implements ExchangeRateProvider {

    private final ExchangeRateStore store;
    private final RateSelector selector;

    public IngestExchangeRateProvider(ExchangeRateStore store, RateSelector selector) {
        this.store = store;
        this.selector = selector;
    }

    @Override
    public Optional<ExchangeRate> findRate(String countryCurrencyDesc, LocalDate purchaseDate) {
        LocalDate floor = selector.windowFloor(purchaseDate);
        List<ExchangeRate> candidates = store.findCandidates(countryCurrencyDesc, floor, purchaseDate);
        return selector.select(candidates, purchaseDate);
    }
}
