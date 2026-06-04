package com.wex.fx.adapter.treasury;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.Ticker;
import com.wex.fx.application.port.ExchangeRateProvider;
import com.wex.fx.domain.rate.ExchangeRate;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.IsoFields;
import java.util.Optional;

/**
 * Provider <strong>A</strong> — the default (D-03): an on-demand cache <em>decorator</em> over A0. Keyed
 * on {@code (descriptor, purchaseDate)} so it is correct under intra-quarter amendments (two dates in the
 * same quarter can resolve to different rates — F8 / the Argentina fixture), which a coarser
 * {@code (currency, quarter)} key would mis-share. The read pattern (re-converting a stored purchase, whose
 * {@code transactionDate} is fixed) gives a high hit rate even with the finer key.
 *
 * <p><strong>Quarter-aware TTL.</strong> Historical-rate immutability nullifies A's usual staleness
 * weakness: a purchase in a settled past quarter caches for {@code settledTtl}; one in the current,
 * still-amendable quarter caches only briefly ({@code currentQuarterTtl}) so a late amendment is picked
 * up; a "no rate" result caches for {@code negativeTtl}. <strong>Upstream failures are never cached</strong>
 * — Caffeine does not memoize an exception thrown by the loader, so a transient outage can't poison the
 * cache. {@code Clock} drives the quarter decision and an injectable {@link Ticker} makes expiry testable.
 */
public final class CachingExchangeRateProvider implements ExchangeRateProvider {

    private final ExchangeRateProvider delegate;
    private final Cache<Key, Optional<ExchangeRate>> cache;

    public CachingExchangeRateProvider(
            ExchangeRateProvider delegate, Clock clock, RatesProperties.Cache config, Ticker ticker) {
        this.delegate = delegate;
        this.cache = Caffeine.newBuilder()
                .maximumSize(config.maximumSize())
                .ticker(ticker)
                .expireAfter(new QuarterAwareExpiry(clock, config))
                .build();
    }

    @Override
    public Optional<ExchangeRate> findRate(String countryCurrencyDesc, LocalDate purchaseDate) {
        return cache.get(
                new Key(countryCurrencyDesc, purchaseDate),
                k -> delegate.findRate(k.descriptor(), k.purchaseDate()));
    }

    private record Key(String descriptor, LocalDate purchaseDate) {}

    /** Per-entry TTL: settled vs current-quarter vs negative. Never extends on read/update. */
    private static final class QuarterAwareExpiry implements Expiry<Key, Optional<ExchangeRate>> {
        private final Clock clock;
        private final RatesProperties.Cache config;

        QuarterAwareExpiry(Clock clock, RatesProperties.Cache config) {
            this.clock = clock;
            this.config = config;
        }

        @Override
        public long expireAfterCreate(Key key, Optional<ExchangeRate> value, long currentTime) {
            if (value.isEmpty()) {
                return config.negativeTtl().toNanos();
            }
            return isCurrentQuarter(key.purchaseDate())
                    ? config.currentQuarterTtl().toNanos()
                    : config.settledTtl().toNanos();
        }

        @Override
        public long expireAfterUpdate(
                Key key, Optional<ExchangeRate> value, long currentTime, long currentDuration) {
            return currentDuration;
        }

        @Override
        public long expireAfterRead(
                Key key, Optional<ExchangeRate> value, long currentTime, long currentDuration) {
            return currentDuration;
        }

        private boolean isCurrentQuarter(LocalDate purchaseDate) {
            return quarterIndex(purchaseDate) >= quarterIndex(LocalDate.now(clock));
        }

        private static int quarterIndex(LocalDate date) {
            return date.getYear() * 4 + date.get(IsoFields.QUARTER_OF_YEAR) - 1;
        }
    }
}
