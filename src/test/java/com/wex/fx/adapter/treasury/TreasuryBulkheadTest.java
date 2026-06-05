package com.wex.fx.adapter.treasury;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.wex.fx.application.error.RateProviderUnavailableException;
import com.wex.fx.application.error.RateProviderUnavailableException.Reason;
import com.wex.fx.domain.rate.ExchangeRate;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Fast unit test for the Treasury concurrency bulkhead (finding #3). Proves (a) the bulkhead is the
 * OUTERMOST decorator so a saturating call fails fast as {@link Reason#OVERLOADED} (→ 503, not a queue or
 * an extra upstream connection), (b) a call within the limit passes through, and (c) the config bean
 * carries the configured permit count.
 */
class TreasuryBulkheadTest {

    private static final LocalDate LTE = LocalDate.parse("2025-05-01");
    private static final LocalDate GTE = LocalDate.parse("2024-11-01");

    private static ResilientRateFetcher fetcherWith(RateFetcher delegate, Bulkhead bulkhead) {
        return new ResilientRateFetcher(
                delegate, Retry.ofDefaults("t"), CircuitBreaker.ofDefaults("t"), bulkhead);
    }

    @Test
    void a_saturated_bulkhead_fails_fast_as_overloaded() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        RateFetcher blocking = new RateFetcher() {
            @Override
            public List<ExchangeRate> fetch(String d, LocalDate before, LocalDate after) {
                inside.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return List.of();
            }

            @Override
            public List<ExchangeRate> fetchWindow(String d, LocalDate from, LocalDate to) {
                return List.of();
            }
        };
        // One permit, no wait → the second concurrent caller must fail fast, not block.
        Bulkhead bulkhead = Bulkhead.of("test", BulkheadConfig.custom()
                .maxConcurrentCalls(1).maxWaitDuration(Duration.ZERO).build());
        ResilientRateFetcher fetcher = fetcherWith(blocking, bulkhead);

        Thread holder = new Thread(() -> fetcher.fetch("X", LTE, GTE));
        holder.start();
        assertThat(inside.await(2, TimeUnit.SECONDS)).isTrue();   // the single permit is now held

        assertThatThrownBy(() -> fetcher.fetch("X", LTE, GTE))
                .isInstanceOfSatisfying(RateProviderUnavailableException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.OVERLOADED));

        release.countDown();
        holder.join(2000);
    }

    @Test
    void a_call_within_the_limit_passes_through() {
        RateFetcher ok = new RateFetcher() {
            @Override
            public List<ExchangeRate> fetch(String d, LocalDate before, LocalDate after) {
                return List.of();
            }

            @Override
            public List<ExchangeRate> fetchWindow(String d, LocalDate from, LocalDate to) {
                return List.of();
            }
        };
        ResilientRateFetcher fetcher = fetcherWith(ok,
                Bulkhead.of("test", BulkheadConfig.custom().maxConcurrentCalls(1).build()));

        assertThat(fetcher.fetch("X", LTE, GTE)).isEmpty();   // permit acquired + released cleanly
    }

    @Test
    void the_configured_bulkhead_carries_the_permit_count() {
        RatesProperties props = new RatesProperties("ondemand", null, 6, null, null, null,
                new RatesProperties.Bulkhead(16, Duration.ZERO), null);
        TreasuryRateProviderConfiguration cfg = new TreasuryRateProviderConfiguration();

        Bulkhead bulkhead = cfg.treasuryBulkhead(cfg.treasuryBulkheadRegistry(props));

        assertThat(bulkhead.getBulkheadConfig().getMaxConcurrentCalls()).isEqualTo(16);
    }
}
