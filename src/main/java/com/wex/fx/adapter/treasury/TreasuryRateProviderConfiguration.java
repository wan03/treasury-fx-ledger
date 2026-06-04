package com.wex.fx.adapter.treasury;

import com.github.benmanes.caffeine.cache.Ticker;
import com.wex.fx.application.port.ExchangeRateProvider;
import com.wex.fx.domain.rate.RateSelector;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.time.Clock;
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
 * {@code passthrough} → A0, {@code ondemand} → A (default). B/C ({@code ingest}/{@code hybrid}) are not
 * built yet, so an unrecognized value falls back to the no-rate provider and the context still boots.
 *
 * <p>Kept inside the adapter package so it can assemble the package-private fetcher/decorator while the
 * provider classes it exposes stay the only public surface. Resilience policy lives here: retries fire on
 * transient upstream failures only (5xx / timeout); a {@code 4xx} or a malformed body is neither retried
 * nor counted against the breaker (the breaker gauges upstream <em>health</em>, not our bad requests).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RatesProperties.class)
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

    /** B/C land later; until then an unrecognized {@code fx.rates.provider} degrades to "no rate". */
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
