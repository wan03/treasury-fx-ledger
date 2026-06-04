package com.wex.fx.adapter.treasury;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.benmanes.caffeine.cache.Ticker;
import com.wex.fx.application.error.RateProviderUnavailableException;
import com.wex.fx.application.error.RateProviderUnavailableException.Reason;
import com.wex.fx.application.port.ExchangeRateProvider;
import com.wex.fx.domain.rate.ExchangeRate;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * Fast unit test for {@link CachingExchangeRateProvider} (Provider A). A counting fake delegate proves hit
 * vs miss; a fixed {@link Clock} fixes "now" so the quarter decision is deterministic; a mutable
 * {@link Ticker} drives Caffeine's expiry without sleeping. Asserts the behaviours that make the cache
 * safe: amendment-correct keying ({@code (descriptor, purchaseDate)}), quarter-aware TTL, negative caching,
 * and — critically — that an upstream failure is never memoised.
 */
class CachingExchangeRateProviderTest {

    // Fixed wall clock: 2025-05-15 sits in Q2-2025.
    private static final Clock NOW = Clock.fixed(Instant.parse("2025-05-15T00:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate SETTLED = LocalDate.parse("2025-02-01"); // Q1-2025 → past, immutable
    private static final LocalDate CURRENT = LocalDate.parse("2025-05-01"); // Q2-2025 → still amendable
    private static final String DESC = "Argentina-Peso";

    private static final RatesProperties.Cache CONFIG = new RatesProperties.Cache(
            Duration.ofDays(30),    // settledTtl
            Duration.ofMinutes(10), // currentQuarterTtl
            Duration.ofMinutes(1),  // negativeTtl
            10_000);

    private final MutableTicker ticker = new MutableTicker();

    private CachingExchangeRateProvider cache(ExchangeRateProvider delegate) {
        return new CachingExchangeRateProvider(delegate, NOW, CONFIG, ticker);
    }

    @Test
    void a_repeated_lookup_is_served_from_cache_and_hits_the_delegate_once() {
        CountingProvider delegate = CountingProvider.returning(d -> Optional.of(rate("1230.0")));
        CachingExchangeRateProvider provider = cache(delegate);

        Optional<ExchangeRate> first = provider.findRate(DESC, SETTLED);
        Optional<ExchangeRate> second = provider.findRate(DESC, SETTLED);

        assertThat(first).isPresent();
        assertThat(second).isEqualTo(first);
        assertThat(delegate.calls).isEqualTo(1);
    }

    @Test
    void two_dates_in_the_same_quarter_are_cached_independently() {
        // The amendment-safety property: a (currency, quarter) key would wrongly share these. The Argentina
        // fixture (F8) has two same-quarter purchase dates resolving to DIFFERENT rates, so the key must be
        // the full (descriptor, purchaseDate).
        CountingProvider delegate = CountingProvider.returning(d -> Optional.of(rate("1230.0")));
        CachingExchangeRateProvider provider = cache(delegate);

        provider.findRate(DESC, LocalDate.parse("2025-02-01"));
        provider.findRate(DESC, LocalDate.parse("2025-02-20")); // same quarter, different date

        assertThat(delegate.calls).isEqualTo(2);
    }

    @Test
    void a_no_rate_outcome_is_cached_negatively() {
        CountingProvider delegate = CountingProvider.returning(d -> Optional.empty());
        CachingExchangeRateProvider provider = cache(delegate);

        assertThat(provider.findRate(DESC, SETTLED)).isEmpty();
        assertThat(provider.findRate(DESC, SETTLED)).isEmpty();
        assertThat(delegate.calls).isEqualTo(1); // the empty result was cached, not re-fetched
    }

    @Test
    void a_settled_entry_outlives_the_shorter_current_quarter_ttl() {
        CountingProvider delegate = CountingProvider.returning(d -> Optional.of(rate("1230.0")));
        CachingExchangeRateProvider provider = cache(delegate);

        provider.findRate(DESC, SETTLED);
        ticker.advance(Duration.ofHours(1)); // > currentQuarterTtl (10m), « settledTtl (30d)
        provider.findRate(DESC, SETTLED);

        assertThat(delegate.calls).isEqualTo(1); // still cached under the long settled TTL
    }

    @Test
    void a_current_quarter_entry_expires_after_its_shorter_ttl() {
        CountingProvider delegate = CountingProvider.returning(d -> Optional.of(rate("1230.0")));
        CachingExchangeRateProvider provider = cache(delegate);

        provider.findRate(DESC, CURRENT);
        ticker.advance(Duration.ofMinutes(10).plusSeconds(1)); // past currentQuarterTtl
        provider.findRate(DESC, CURRENT);

        assertThat(delegate.calls).isEqualTo(2); // re-fetched so a late amendment is picked up
    }

    @Test
    void a_negative_entry_expires_after_the_negative_ttl() {
        CountingProvider delegate = CountingProvider.returning(d -> Optional.empty());
        CachingExchangeRateProvider provider = cache(delegate);

        provider.findRate(DESC, SETTLED);
        ticker.advance(Duration.ofMinutes(1).plusSeconds(1)); // past negativeTtl
        provider.findRate(DESC, SETTLED);

        assertThat(delegate.calls).isEqualTo(2);
    }

    @Test
    void an_upstream_failure_is_never_cached() {
        // First call fails, the next would succeed. A poisoned cache would replay the failure; Caffeine does
        // not memoise a loader exception, so the retry must reach the delegate and return the live rate.
        CountingProvider delegate = CountingProvider.returning(d -> {
            throw new RateProviderUnavailableException(Reason.UPSTREAM_ERROR, null);
        });
        CachingExchangeRateProvider provider = cache(delegate);

        assertThatThrownBy(() -> provider.findRate(DESC, SETTLED))
                .isInstanceOf(RateProviderUnavailableException.class);

        delegate.behaviour = d -> Optional.of(rate("1230.0")); // upstream recovers
        assertThat(provider.findRate(DESC, SETTLED)).isPresent();
        assertThat(delegate.calls).isEqualTo(2); // the failure was NOT cached
    }

    // --- fakes -----------------------------------------------------------------------------------

    private static ExchangeRate rate(String value) {
        return new ExchangeRate(DESC, LocalDate.parse("2025-02-01"), LocalDate.parse("2025-02-01"),
                new BigDecimal(value));
    }

    private static final class CountingProvider implements ExchangeRateProvider {
        private Function<LocalDate, Optional<ExchangeRate>> behaviour;
        private int calls;

        static CountingProvider returning(Function<LocalDate, Optional<ExchangeRate>> behaviour) {
            CountingProvider p = new CountingProvider();
            p.behaviour = behaviour;
            return p;
        }

        @Override
        public Optional<ExchangeRate> findRate(String countryCurrencyDesc, LocalDate purchaseDate) {
            calls++;
            return behaviour.apply(purchaseDate);
        }
    }

    /** A hand-cranked Caffeine {@link Ticker}; expiry advances only when we say so. */
    private static final class MutableTicker implements Ticker {
        private long nanos;

        @Override
        public long read() {
            return nanos;
        }

        void advance(Duration duration) {
            nanos += duration.toNanos();
        }
    }
}
