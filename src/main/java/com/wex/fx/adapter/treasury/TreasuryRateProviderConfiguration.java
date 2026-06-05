package com.wex.fx.adapter.treasury;

import com.github.benmanes.caffeine.cache.Ticker;
import com.wex.fx.application.port.ExchangeRateProvider;
import com.wex.fx.domain.currency.CurrencyMap;
import com.wex.fx.domain.rate.RateSelector;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.micrometer.tagged.TaggedBulkheadMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedRetryMetrics;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Composition root for the Treasury rate providers (D-03). Builds the shared, resilience-wrapped HTTP
 * fetcher and selects the active {@link ExchangeRateProvider} from {@code fx.rates.provider}:
 * {@code passthrough} → A0, {@code ondemand} → A (default), {@code ingest} → B, {@code hybrid} → C. An
 * unrecognized value falls back to the no-rate provider so the context still boots.
 *
 * <p>Kept inside the adapter package so it can assemble the package-private fetcher/decorator/store while
 * the provider classes it exposes stay the only public surface. Resilience policy lives here: retries fire
 * on transient upstream failures only (5xx / timeout); a {@code 4xx} or a malformed body is neither retried
 * nor counted against the breaker (the breaker gauges upstream <em>health</em>, not our bad requests).
 *
 * <p>Scheduling is enabled app-wide by the neutral {@code config.SchedulingConfig}; B/C's background
 * reconcile ({@link RateSyncService}) is the only {@code @Scheduled} holder here, created solely for
 * {@code ingest}/{@code hybrid}, so on A0/A there are no scheduled tasks from this config.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RatesProperties.class)
class TreasuryRateProviderConfiguration {

    @Bean
    RateSelector rateSelector(RatesProperties props) {
        return new RateSelector(props.windowMonths(), props.rateDateBasis());
    }

    @Bean
    RestClient treasuryRestClient(RatesProperties props) {
        RatesProperties.Treasury t = props.treasury();
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(t.connectTimeout())
                .withReadTimeout(t.readTimeout());
        return RestClient.builder()
                .baseUrl(t.baseUrl())
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings))
                .build();
    }

    // Breaker/retry/bulkhead are created from their Resilience4j *registries* (not the bare *.of(...))
    // so the TaggedXxxMetrics binders below can discover them and publish to /actuator/prometheus
    // (finding #4). The registry instance carries the same config a direct .of(...) would.

    @Bean
    CircuitBreakerRegistry treasuryCircuitBreakerRegistry(RatesProperties props) {
        RatesProperties.Resilience r = props.resilience();
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(r.slidingWindowSize())
                // Must be set explicitly: the library default (100) exceeds our window, so the breaker
                // would never accumulate enough calls to compute a failure rate and could never open.
                .minimumNumberOfCalls(r.minimumNumberOfCalls())
                .failureRateThreshold(r.failureRateThreshold())
                .waitDurationInOpenState(r.waitDurationInOpenState())
                .permittedNumberOfCallsInHalfOpenState(r.permittedCallsInHalfOpenState())
                // Only genuine upstream-health failures trip the breaker…
                .recordExceptions(HttpServerErrorException.class, ResourceAccessException.class)
                // …a 4xx (our bad request) or a contract violation must not.
                .ignoreExceptions(HttpClientErrorException.class, TreasuryContractException.class)
                .build();
        return CircuitBreakerRegistry.of(config);
    }

    @Bean
    CircuitBreaker treasuryCircuitBreaker(CircuitBreakerRegistry registry) {
        return registry.circuitBreaker("treasury");
    }

    @Bean
    RetryRegistry treasuryRetryRegistry(RatesProperties props) {
        RatesProperties.Resilience r = props.resilience();
        RetryConfig config = RetryConfig.custom()
                .maxAttempts(r.maxAttempts())
                // Exponential backoff WITH jitter (constitution §7): a fixed wait re-synchronizes
                // retry waves on recovery — every caller re-hits Treasury on the same tick. The
                // randomization factor spreads them out. `intervalFunction` and `waitDuration` are
                // mutually exclusive in RetryConfig, so the interval function is the only wait knob.
                .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(
                        r.retryBackoff(), r.retryMultiplier(), r.retryRandomizationFactor()))
                // Retry transient upstream failures only — never a 4xx or a malformed body.
                .retryExceptions(HttpServerErrorException.class, ResourceAccessException.class)
                .build();
        return RetryRegistry.of(config);
    }

    @Bean
    Retry treasuryRetry(RetryRegistry registry) {
        return registry.retry("treasury");
    }

    @Bean
    BulkheadRegistry treasuryBulkheadRegistry(RatesProperties props) {
        RatesProperties.Bulkhead b = props.bulkhead();
        BulkheadConfig config = BulkheadConfig.custom()
                .maxConcurrentCalls(b.maxConcurrentCalls())
                .maxWaitDuration(b.maxWaitDuration())   // 0 = fail fast; saturation surfaces as a clean 503
                .build();
        return BulkheadRegistry.of(config);
    }

    @Bean
    Bulkhead treasuryBulkhead(BulkheadRegistry registry) {
        return registry.bulkhead("treasury");
    }

    /**
     * Bind the manually-built breaker/retry/bulkhead to Micrometer so {@code /actuator/prometheus}
     * exposes {@code resilience4j_circuitbreaker_state{name="treasury"}} (+ retry/bulkhead). These
     * instances live outside the Spring-managed Resilience4j registries the starter's binders watch, so
     * we register them explicitly. Spring Boot applies every {@link MeterBinder} bean to the registry.
     */
    @Bean
    MeterBinder treasuryResilienceMetrics(
            CircuitBreakerRegistry circuitBreakerRegistry,
            RetryRegistry retryRegistry,
            BulkheadRegistry bulkheadRegistry) {
        return registry -> {
            TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(circuitBreakerRegistry).bindTo(registry);
            TaggedRetryMetrics.ofRetryRegistry(retryRegistry).bindTo(registry);
            TaggedBulkheadMetrics.ofBulkheadRegistry(bulkheadRegistry).bindTo(registry);
        };
    }

    /** Caffeine wall-clock ticker (overridden in tests to make TTL expiry deterministic). */
    @Bean
    Ticker rateCacheTicker() {
        return Ticker.systemTicker();
    }

    /** Provider A0 — passthrough, no cache. */
    @Bean
    @ConditionalOnProperty(name = "fx.rates.provider", havingValue = "passthrough")
    ExchangeRateProvider passthroughExchangeRateProvider(
            RestClient treasuryRestClient, Retry treasuryRetry, CircuitBreaker treasuryCircuitBreaker,
            Bulkhead treasuryBulkhead, RateSelector rateSelector, RatesProperties props) {
        return new PassthroughExchangeRateProvider(
                resilientFetcher(treasuryRestClient, treasuryRetry, treasuryCircuitBreaker,
                        treasuryBulkhead, props),
                rateSelector);
    }

    /** Provider A — on-demand + cache. The default ({@code matchIfMissing}). */
    @Bean
    @ConditionalOnProperty(name = "fx.rates.provider", havingValue = "ondemand", matchIfMissing = true)
    ExchangeRateProvider onDemandExchangeRateProvider(
            RestClient treasuryRestClient, Retry treasuryRetry, CircuitBreaker treasuryCircuitBreaker,
            Bulkhead treasuryBulkhead, RateSelector rateSelector, Clock clock, RatesProperties props,
            Ticker rateCacheTicker) {
        PassthroughExchangeRateProvider a0 = new PassthroughExchangeRateProvider(
                resilientFetcher(treasuryRestClient, treasuryRetry, treasuryCircuitBreaker,
                        treasuryBulkhead, props),
                rateSelector);
        return new CachingExchangeRateProvider(a0, clock, props.cache(), rateCacheTicker);
    }

    /** Provider B — ingest. Pure local reads over {@code exchange_rates}; the sync keeps it current. */
    @Bean
    @ConditionalOnProperty(name = "fx.rates.provider", havingValue = "ingest")
    ExchangeRateProvider ingestExchangeRateProvider(ExchangeRateStore store, RateSelector rateSelector) {
        return new IngestExchangeRateProvider(store, rateSelector);
    }

    /** Provider C — hybrid. Local-first over the store, lazy-filling from Treasury on a miss. */
    @Bean
    @ConditionalOnProperty(name = "fx.rates.provider", havingValue = "hybrid")
    ExchangeRateProvider hybridExchangeRateProvider(
            ExchangeRateStore store, RestClient treasuryRestClient, Retry treasuryRetry,
            CircuitBreaker treasuryCircuitBreaker, Bulkhead treasuryBulkhead, RateSelector rateSelector,
            RatesProperties props) {
        return new HybridExchangeRateProvider(store,
                resilientFetcher(treasuryRestClient, treasuryRetry, treasuryCircuitBreaker,
                        treasuryBulkhead, props),
                rateSelector);
    }

    /**
     * The ingest sync (startup backfill + scheduled reconcile) — present for {@code ingest} and
     * {@code hybrid} only. {@code @ConditionalOnExpression} because B and C both need it but
     * {@code @ConditionalOnProperty} matches a single value.
     */
    @Bean
    @ConditionalOnExpression(
            "'${fx.rates.provider:ondemand}' == 'ingest' or '${fx.rates.provider:ondemand}' == 'hybrid'")
    RateSyncService rateSyncService(
            RestClient treasuryRestClient, Retry treasuryRetry, CircuitBreaker treasuryCircuitBreaker,
            Bulkhead treasuryBulkhead, ExchangeRateStore store, CurrencyMap currencyMap, Clock clock,
            RatesProperties props) {
        return new RateSyncService(
                resilientFetcher(treasuryRestClient, treasuryRetry, treasuryCircuitBreaker,
                        treasuryBulkhead, props),
                store, currencyMap, clock, props.sync().windowMonths());
    }

    /** Fallback: an unrecognized {@code fx.rates.provider} degrades to "no rate" so the context boots. */
    @Bean
    @ConditionalOnMissingBean(ExchangeRateProvider.class)
    ExchangeRateProvider unavailableExchangeRateProvider() {
        return new UnavailableExchangeRateProvider();
    }

    private static RateFetcher resilientFetcher(
            RestClient client, Retry retry, CircuitBreaker circuitBreaker, Bulkhead bulkhead,
            RatesProperties props) {
        return new ResilientRateFetcher(
                new TreasuryRateFetcher(client, wireDateField(props)), retry, circuitBreaker, bulkhead);
    }

    /** Treasury column for the active {@link com.wex.fx.domain.rate.RateDateBasis} (D-02). */
    private static String wireDateField(RatesProperties props) {
        return switch (props.rateDateBasis()) {
            case EFFECTIVE_DATE -> "effective_date";
            case RECORD_DATE -> "record_date";
        };
    }
}
