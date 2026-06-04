package com.wex.fx.adapter.treasury;

import com.github.benmanes.caffeine.cache.Ticker;
import com.wex.fx.application.port.ExchangeRateProvider;
import com.wex.fx.domain.currency.CurrencyMap;
import com.wex.fx.domain.rate.RateSelector;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
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
 * <p>{@link EnableScheduling} powers B/C's background reconcile; the only {@code @Scheduled} holder
 * ({@link RateSyncService}) is created only for {@code ingest}/{@code hybrid}, so on A0/A there are no
 * scheduled tasks and the annotation is inert.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RatesProperties.class)
@EnableScheduling
class TreasuryRateProviderConfiguration {

    @Bean
    RateSelector rateSelector(RatesProperties props) {
        return new RateSelector(props.windowMonths());
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

    @Bean
    CircuitBreaker treasuryCircuitBreaker(RatesProperties props) {
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
        return CircuitBreaker.of("treasury", config);
    }

    @Bean
    Retry treasuryRetry(RatesProperties props) {
        RatesProperties.Resilience r = props.resilience();
        RetryConfig config = RetryConfig.custom()
                .maxAttempts(r.maxAttempts())
                .waitDuration(r.retryBackoff())
                // Retry transient upstream failures only — never a 4xx or a malformed body.
                .retryExceptions(HttpServerErrorException.class, ResourceAccessException.class)
                .build();
        return Retry.of("treasury", config);
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
            RestClient treasuryRestClient, Retry treasuryRetry,
            CircuitBreaker treasuryCircuitBreaker, RateSelector rateSelector) {
        return new PassthroughExchangeRateProvider(
                resilientFetcher(treasuryRestClient, treasuryRetry, treasuryCircuitBreaker), rateSelector);
    }

    /** Provider A — on-demand + cache. The default ({@code matchIfMissing}). */
    @Bean
    @ConditionalOnProperty(name = "fx.rates.provider", havingValue = "ondemand", matchIfMissing = true)
    ExchangeRateProvider onDemandExchangeRateProvider(
            RestClient treasuryRestClient, Retry treasuryRetry, CircuitBreaker treasuryCircuitBreaker,
            RateSelector rateSelector, Clock clock, RatesProperties props, Ticker rateCacheTicker) {
        PassthroughExchangeRateProvider a0 = new PassthroughExchangeRateProvider(
                resilientFetcher(treasuryRestClient, treasuryRetry, treasuryCircuitBreaker), rateSelector);
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
            CircuitBreaker treasuryCircuitBreaker, RateSelector rateSelector) {
        return new HybridExchangeRateProvider(store,
                resilientFetcher(treasuryRestClient, treasuryRetry, treasuryCircuitBreaker), rateSelector);
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
            ExchangeRateStore store, CurrencyMap currencyMap, Clock clock, RatesProperties props) {
        return new RateSyncService(
                resilientFetcher(treasuryRestClient, treasuryRetry, treasuryCircuitBreaker),
                store, currencyMap, clock, props.sync().windowMonths());
    }

    /** Fallback: an unrecognized {@code fx.rates.provider} degrades to "no rate" so the context boots. */
    @Bean
    @ConditionalOnMissingBean(ExchangeRateProvider.class)
    ExchangeRateProvider unavailableExchangeRateProvider() {
        return new UnavailableExchangeRateProvider();
    }

    private static RateFetcher resilientFetcher(
            RestClient client, Retry retry, CircuitBreaker circuitBreaker) {
        return new ResilientRateFetcher(new TreasuryRateFetcher(client), retry, circuitBreaker);
    }
}
