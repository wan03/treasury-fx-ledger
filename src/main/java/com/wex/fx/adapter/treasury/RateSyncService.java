package com.wex.fx.adapter.treasury;

import com.wex.fx.application.error.RateProviderUnavailableException;
import com.wex.fx.domain.currency.CurrencyMap;
import com.wex.fx.domain.rate.ExchangeRate;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * The out-of-band sync that keeps the local {@code exchange_rates} table current for providers
 * <strong>B (ingest)</strong> and <strong>C (hybrid)</strong> (D-03). Two triggers:
 *
 * <ul>
 *   <li><strong>startup backfill</strong> ({@link ApplicationReadyEvent}) — a fresh instance pulls the
 *       recent window for every curated currency so local reads are immediately serviceable offline;</li>
 *   <li><strong>scheduled reconcile</strong> ({@link Scheduled}) — re-pulls on a cadence to catch
 *       intra-quarter amendments (F8). The upsert is idempotent on {@code (descriptor, effective_date)},
 *       so a re-sync converges: new amendment rows insert, restated rates update.</li>
 * </ul>
 *
 * <p>Sync is <strong>best-effort</strong>: a Treasury outage for one currency is caught, logged, and
 * skipped so it never crashes startup or aborts the other currencies — the resilient fetcher already
 * bounds retries and trips the shared breaker. Logs name only the public currency descriptor and coarse
 * counts/reason — never amounts or PII (constitution §9).
 */
class RateSyncService {

    private static final Logger log = LoggerFactory.getLogger(RateSyncService.class);

    private final RateFetcher fetcher;
    private final ExchangeRateStore store;
    private final CurrencyMap currencyMap;
    private final Clock clock;
    private final int windowMonths;

    RateSyncService(
            RateFetcher fetcher,
            ExchangeRateStore store,
            CurrencyMap currencyMap,
            Clock clock,
            int windowMonths) {
        this.fetcher = fetcher;
        this.store = store;
        this.currencyMap = currencyMap;
        this.clock = clock;
        this.windowMonths = windowMonths;
    }

    @EventListener(ApplicationReadyEvent.class)
    void backfillOnStartup() {
        log.info("Treasury rate ingest: startup backfill over {} currencies", currencyMap.asMap().size());
        sync();
    }

    @Scheduled(
            fixedDelayString = "${fx.rates.sync.interval}",
            initialDelayString = "${fx.rates.sync.interval}")
    void scheduledReconcile() {
        sync();
    }

    /**
     * Pull the recent window for every curated currency and upsert it. Idempotent and convergent;
     * re-running reconciles amendments. Visible for tests (drive a sync, then assert the table).
     */
    void sync() {
        LocalDate to = LocalDate.now(clock);
        LocalDate from = to.minusMonths(windowMonths);
        int currencies = 0;
        int rows = 0;
        for (String descriptor : currencyMap.asMap().values()) {
            try {
                List<ExchangeRate> fetched = fetcher.fetchWindow(descriptor, from, to);
                store.upsertAll(fetched);
                currencies++;
                rows += fetched.size();
            } catch (RateProviderUnavailableException e) {
                // Best-effort: a transient upstream failure must not crash startup or abort the cycle.
                log.warn("Treasury rate ingest: skipping '{}' this cycle ({})", descriptor, e.reason());
            }
        }
        log.info("Treasury rate ingest: synced {} rows across {} currencies", rows, currencies);
    }
}
