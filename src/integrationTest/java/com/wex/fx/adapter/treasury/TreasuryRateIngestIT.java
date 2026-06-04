package com.wex.fx.adapter.treasury;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.wex.fx.AbstractPostgresIT;
import com.wex.fx.domain.currency.CurrencyMap;
import com.wex.fx.domain.rate.ExchangeRate;
import com.wex.fx.domain.rate.RateSelector;
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
import java.util.Map;
import java.util.Optional;
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
 * Integration test for the provider-B ingest path against real Postgres (the V3 {@code exchange_rates}
 * table, app-role DML) and a WireMock Treasury. Proves the three properties that make ingest correct:
 * the {@link RateSyncService} backfills the local table and {@link IngestExchangeRateProvider} then
 * selects the amendment <em>purely locally</em>; a re-sync <strong>reconciles idempotently</strong>
 * (new amendment rows insert, a restated rate updates, keyed on {@code (descriptor, effective_date)});
 * and provider C ({@link HybridExchangeRateProvider}) <strong>lazy-fills</strong> an empty window then
 * serves it locally with no further upstream call.
 *
 * <p>The {@code app} role has no {@code DELETE} on {@code exchange_rates}, so each test uses a unique
 * descriptor to stay isolated on the shared container instead of truncating.
 */
class TreasuryRateIngestIT extends AbstractPostgresIT {

    private static final String PATH = "/v1/accounting/od/rates_of_exchange";
    private static final LocalDate PURCHASE = LocalDate.parse("2025-05-01");
    // Window comfortably covers the 2025 fixture rows.
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
                new TreasuryRateFetcher(restClient()), retry(), breaker());
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    @Test
    void sync_backfills_then_provider_B_selects_the_amendment_locally() {
        String desc = "Ingest-Argentina-Peso";
        wireMock.stubFor(get(urlPathEqualTo(PATH)).willReturn(okJson(body(
                row(desc, "1093.0", "2025-03-31", "2025-03-31"),
                row(desc, "1230.0", "2025-04-15", "2025-04-30"),   // intra-quarter amendment
                row(desc, "1205.0", "2025-06-30", "2025-06-30")))));

        sync(desc).sync();

        // The whole window is now local…
        assertThat(store.findCandidates(desc, LocalDate.parse("2024-01-01"), LocalDate.parse("2025-12-31")))
                .hasSize(3);
        // …and B selects the amendment (1230) for a 2025-05-01 purchase, reading only the table.
        Optional<ExchangeRate> selected = ingestProvider().findRate(desc, PURCHASE);
        assertThat(selected).isPresent();
        assertThat(selected.get().exchangeRate()).isEqualByComparingTo("1230.0");
        assertThat(selected.get().effectiveDate()).isEqualTo(LocalDate.parse("2025-04-15"));
    }

    @Test
    void a_re_sync_reconciles_amendments_idempotently() {
        String desc = "Reconcile-Argentina-Peso";
        // First cycle: the Q1 base + the original amendment.
        wireMock.stubFor(get(urlPathEqualTo(PATH)).willReturn(okJson(body(
                row(desc, "1093.0", "2025-03-31", "2025-03-31"),
                row(desc, "1230.0", "2025-04-15", "2025-04-30")))));
        sync(desc).sync();
        assertThat(ingestProvider().findRate(desc, PURCHASE).orElseThrow().exchangeRate())
                .isEqualByComparingTo("1230.0");

        // Second cycle: the 2025-04-15 rate is RESTATED (update) and a new 2025-06-30 amendment arrives
        // (insert). Re-running the same sync must converge — no duplicate-key errors.
        wireMock.resetAll();
        wireMock.stubFor(get(urlPathEqualTo(PATH)).willReturn(okJson(body(
                row(desc, "1093.0", "2025-03-31", "2025-03-31"),
                row(desc, "1240.0", "2025-04-15", "2025-05-02"),   // restated → DO UPDATE on the same key
                row(desc, "1205.0", "2025-06-30", "2025-06-30"))))); // new effective_date → INSERT
        sync(desc).sync();

        assertThat(store.findCandidates(desc, LocalDate.parse("2024-01-01"), LocalDate.parse("2025-12-31")))
                .hasSize(3); // 0331 unchanged, 0415 updated in place, 0630 inserted — not 4
        // B now selects the restated amendment for the 2025-05-01 purchase.
        assertThat(ingestProvider().findRate(desc, PURCHASE).orElseThrow().exchangeRate())
                .isEqualByComparingTo("1240.0");
    }

    @Test
    void provider_C_lazy_fills_an_empty_window_then_serves_locally() {
        String desc = "Lazyfill-Argentina-Peso";
        wireMock.stubFor(get(urlPathEqualTo(PATH)).willReturn(okJson(body(
                row(desc, "1093.0", "2025-03-31", "2025-03-31"),
                row(desc, "1230.0", "2025-04-15", "2025-04-30")))));
        HybridExchangeRateProvider hybrid = new HybridExchangeRateProvider(store, fetcher, rateSelector);

        // Cold miss: the store has nothing for this descriptor → C lazy-fills from Treasury and answers.
        Optional<ExchangeRate> first = hybrid.findRate(desc, PURCHASE);
        assertThat(first).isPresent();
        assertThat(first.get().exchangeRate()).isEqualByComparingTo("1230.0");
        assertThat(store.findCandidates(desc, LocalDate.parse("2024-11-01"), PURCHASE)).isNotEmpty();

        // Self-healing: with Treasury now unreachable (all stubs removed → 404), the second read is a pure
        // local hit and still returns the rate — proving local-first, write-through behaviour.
        wireMock.resetAll();
        Optional<ExchangeRate> second = hybrid.findRate(desc, PURCHASE);
        assertThat(second).isPresent();
        assertThat(second.get().exchangeRate()).isEqualByComparingTo("1230.0");
    }

    // --- helpers ---------------------------------------------------------------------------------

    private RateSyncService sync(String descriptor) {
        return new RateSyncService(
                fetcher, store, new CurrencyMap(Map.of("ARS", descriptor)), CLOCK, /* windowMonths */ 24);
    }

    private IngestExchangeRateProvider ingestProvider() {
        return new IngestExchangeRateProvider(store, rateSelector);
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
        return Retry.of("ingest-test", RetryConfig.custom()
                .maxAttempts(2)
                .waitDuration(Duration.ofMillis(1))
                .retryExceptions(HttpServerErrorException.class, ResourceAccessException.class)
                .build());
    }

    private static CircuitBreaker breaker() {
        return CircuitBreaker.of("ingest-test", CircuitBreakerConfig.custom()
                .minimumNumberOfCalls(100) // effectively never opens during this happy-path suite
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
