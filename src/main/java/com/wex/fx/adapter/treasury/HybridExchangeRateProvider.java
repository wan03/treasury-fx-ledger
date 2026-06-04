package com.wex.fx.adapter.treasury;

import com.wex.fx.application.port.ExchangeRateProvider;
import com.wex.fx.domain.rate.ExchangeRate;
import com.wex.fx.domain.rate.RateSelector;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Provider <strong>C</strong> — hybrid (D-03): local-first over B's {@code exchange_rates} table, with
 * <em>lazy fill on miss</em>. Reads serve from the local store (fast, decoupled); only when the window
 * holds no row does it fall through to Treasury, persist what it pulled (self-healing), and answer. The
 * current-quarter freshness it needs comes from the shared {@link RateSyncService} background refresh, so
 * the per-request path stays local-first and cheap.
 *
 * <p>Critically, a lazy-fill that finds Treasury <em>down</em> propagates the
 * {@link com.wex.fx.application.error.RateProviderUnavailableException} (→ {@code 502/503/504}); it is
 * never collapsed into an empty "no rate" ({@code 422}). Only a fill that <em>succeeds</em> yet yields no
 * in-window row is a true {@code Optional.empty()}. The lazy fill writes through every fetched row so the
 * next read for the same window is a pure local hit.
 */
public final class HybridExchangeRateProvider implements ExchangeRateProvider {

    private final ExchangeRateStore store;
    private final RateFetcher fetcher;
    private final RateSelector selector;

    public HybridExchangeRateProvider(
            ExchangeRateStore store, RateFetcher fetcher, RateSelector selector) {
        this.store = store;
        this.fetcher = fetcher;
        this.selector = selector;
    }

    @Override
    public Optional<ExchangeRate> findRate(String countryCurrencyDesc, LocalDate purchaseDate) {
        LocalDate floor = selector.windowFloor(purchaseDate);

        Optional<ExchangeRate> local =
                selector.select(store.findCandidates(countryCurrencyDesc, floor, purchaseDate), purchaseDate);
        if (local.isPresent()) {
            return local;   // local hit — no upstream call
        }

        // Miss: lazy-fill the window from Treasury (may throw on a genuine outage — that must surface as
        // an availability error, not a false "no rate"), write it through, and select over what we pulled.
        List<ExchangeRate> fetched = fetcher.fetchWindow(countryCurrencyDesc, floor, purchaseDate);
        store.upsertAll(fetched);
        return selector.select(fetched, purchaseDate);
    }
}
