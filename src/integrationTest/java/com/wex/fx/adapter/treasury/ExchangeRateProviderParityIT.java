package com.wex.fx.adapter.treasury;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.benmanes.caffeine.cache.Ticker;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.wex.fx.AbstractPostgresIT;
import com.wex.fx.application.port.ExchangeRateProvider;
import com.wex.fx.domain.rate.ExchangeRate;
import com.wex.fx.domain.rate.RateSelector;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Provider-parity (T4.6): the four {@link ExchangeRateProvider} strategies (D-03) — A0 passthrough,
 * A on-demand+cache, B ingest, C hybrid — must resolve the <em>same</em> rate for the same
 * {@code (descriptor, purchaseDate)}. That equivalence is the whole point of the port seam: switching
 * {@code fx.rates.provider} changes <em>how</em> a rate is sourced (live call, cache, local table,
 * lazy-fill) but never <em>which</em> rate is chosen, because every provider runs the one pure
 * {@link RateSelector} over its candidates. The Argentina fixture carries an intra-quarter amendment
 * (1230 effective 2025-04-15), so a correct provider must pick 1230 — not the 1093 base — for a
 * 2025-05-01 purchase, proving the parity is over the load-bearing case, not a trivial single row.
 *
 * <p>All four are fed from one WireMock fixture: A0/A/C fetch it live; B's local store is seeded from the
 * same window (fetch-window → upsert), so any divergence is a selection bug, not a data-source artefact.
 * Runs against real Postgres (app-role DML) on a unique descriptor — the {@code app} role has no DELETE,
 * so the shared container stays isolated without truncation.
 */
class ExchangeRateProviderParityIT extends AbstractPostgresIT {

    private static final String PATH = "/v1/accounting/od/rates_of_exchange";
    private static final String DESC = "Parity-Argentina-Peso";
    private static final LocalDate PURCHASE = LocalDate.parse("2025-05-01");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2025-07-01T00:00:00Z"), ZoneOffset.UTC);

    @Autowired
    ExchangeRateStore store;
    @Autowired
    RateSelector rateSelector;

    private WireMockServer wireMock;
    private RateFetcher fetcher;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
        fetcher = new ResilientRateFetcher(
                new TreasuryRateFetcher(restClient()), retry(), breaker(), Bulkhead.ofDefaults("test"));
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    @Test
    void all_four_providers_select_the_same_rate_for_a_fixture_date() {
        wireMock.stubFor(get(urlPathEqualTo(PATH)).willReturn(okJson(body(
                row(DESC, "1093.0", "2025-03-31", "2025-03-31"),
                row(DESC, "1230.0", "2025-04-15", "2025-04-30"),   // intra-quarter amendment — the answer
                row(DESC, "1205.0", "2025-06-30", "2025-06-30")))));

        // A0 passthrough and A (cache decorating A0) both source live from Treasury.
        ExchangeRateProvider a0 = new PassthroughExchangeRateProvider(fetcher, rateSelector);
        ExchangeRateProvider a = new CachingExchangeRateProvider(a0, CLOCK, cacheConfig(), Ticker.systemTicker());
        // C hybrid: local-first, lazy-filling from the same Treasury on the cold miss.
        ExchangeRateProvider c = new HybridExchangeRateProvider(store, fetcher, rateSelector);
        // B ingest: pure local reads — seed its store from the very same fixture window so parity is a
        // statement about selection, not about where the rows came from.
        store.upsertAll(fetcher.fetchWindow(DESC, rateSelector.windowFloor(PURCHASE), PURCHASE));
        ExchangeRateProvider b = new IngestExchangeRateProvider(store, rateSelector);

        Function<ExchangeRateProvider, ExchangeRate> resolve = p -> {
            Optional<ExchangeRate> r = p.findRate(DESC, PURCHASE);
            assertThat(r).isPresent();
            return r.get();
        };

        assertThat(List.of(resolve.apply(a0), resolve.apply(a), resolve.apply(b), resolve.apply(c)))
                .allSatisfy(rate -> {
                    assertThat(rate.exchangeRate()).isEqualByComparingTo("1230.0");
                    assertThat(rate.effectiveDate()).isEqualTo(LocalDate.parse("2025-04-15"));
                });
    }

    // --- helpers ---------------------------------------------------------------------------------

    private static RatesProperties.Cache cacheConfig() {
        return new RatesProperties.Cache(
                Duration.ofDays(30), Duration.ofMinutes(10), Duration.ofMinutes(1), 10_000);
    }

    private RestClient restClient() {
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(500))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(2));
        return RestClient.builder().baseUrl(wireMock.baseUrl()).requestFactory(factory).build();
    }

    private static Retry retry() {
        return Retry.of("parity-test", RetryConfig.custom()
                .maxAttempts(2)
                .waitDuration(Duration.ofMillis(1))
                .retryExceptions(HttpServerErrorException.class, ResourceAccessException.class)
                .build());
    }

    private static CircuitBreaker breaker() {
        return CircuitBreaker.of("parity-test", CircuitBreakerConfig.custom()
                .minimumNumberOfCalls(100) // effectively never opens during this happy-path test
                .recordExceptions(HttpServerErrorException.class, ResourceAccessException.class)
                .ignoreExceptions(HttpClientErrorException.class, TreasuryContractException.class)
                .build());
    }

    private static String body(String... rows) {
        return "{\"data\":[" + String.join(",", rows) + "]}";
    }

    private static String row(String desc, String rate, String effective, String record) {
        return "{\"country_currency_desc\":\"" + desc + "\",\"exchange_rate\":\"" + rate
                + "\",\"effective_date\":\"" + effective + "\",\"record_date\":\"" + record + "\"}";
    }
}
