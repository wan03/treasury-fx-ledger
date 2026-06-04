package com.wex.fx.adapter.treasury;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.wex.fx.application.error.RateProviderUnavailableException;
import com.wex.fx.application.error.RateProviderUnavailableException.Reason;
import com.wex.fx.domain.rate.ExchangeRate;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Integration test for the resilience seam — the real {@link RestClient} (bounded timeouts) against a
 * WireMock Treasury, wrapped in the production {@link ResilientRateFetcher} over a Resilience4j
 * {@link Retry}/{@link CircuitBreaker}. Proves the four outcomes the constitution (§7) promises:
 * transient 5xx → bounded retries → breaker opens → fast-fail; a 4xx is neither retried nor counted
 * against the breaker; a read timeout maps to {@link Reason#TIMEOUT}; the happy path returns the row and
 * carries the F7 push-down query. No Spring context and no database — just the adapter under stub.
 */
class TreasuryRateProviderResilienceIT {

    private static final String PATH = "/v1/accounting/od/rates_of_exchange";
    private static final LocalDate LTE = LocalDate.parse("2025-05-01");
    private static final LocalDate GTE = LocalDate.parse("2024-11-01");

    private WireMockServer wireMock;
    private RestClient restClient;
    private CountingFetcher counting; // invocations our resilience layer makes of the real fetcher

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
        // A relaxed read timeout, comfortably above WireMock's cold-start latency: a tighter one races the
        // first request, surfaces a spurious HttpTimeoutException, and our (correct) retry policy retries it
        // — polluting the request-count assertions. The dedicated timeout test below uses its own tight
        // client. (We pin HTTP/1.1 for a deterministic transport — no HTTP/2 upgrade variance under test.)
        restClient = restClient(Duration.ofSeconds(2));
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    @Test
    void a_persistent_5xx_retries_then_trips_the_breaker_into_fast_fail() {
        wireMock.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(500)));
        // retry = 3 attempts; breaker opens after 6 recorded failures (= 2 fully-retried calls).
        Retry retry = retry(3);
        CircuitBreaker breaker = breaker(6, 6);
        RateFetcher fetcher = resilient(retry, breaker);

        // First two calls exhaust their retries and surface UPSTREAM_ERROR; the breaker is still closed
        // (it flips only at the end of the 6th recorded failure).
        assertUpstreamError(fetcher);
        assertUpstreamError(fetcher);
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // Third call never reaches Treasury — the open breaker fails fast.
        assertThatThrownBy(() -> fetcher.fetch("X", LTE, GTE))
                .isInstanceOfSatisfying(RateProviderUnavailableException.class,
                        ex -> assertThat(ex.reason()).isEqualTo(Reason.CIRCUIT_OPEN));

        // Exactly 6 fetches (2 calls × 3 retries); the open breaker short-circuited the 3rd call's supplier,
        // so it added none. Asserted on both our fetcher and the upstream stub.
        assertThat(counting.calls).isEqualTo(6);
        wireMock.verify(6, getRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    void a_4xx_is_not_retried_and_does_not_trip_the_breaker() {
        wireMock.stubFor(get(urlPathEqualTo(PATH)).willReturn(
                aResponse().withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"invalid filter\"}")));
        Retry retry = retry(3);
        CircuitBreaker breaker = breaker(6, 6);
        RateFetcher fetcher = resilient(retry, breaker);

        assertThatThrownBy(() -> fetcher.fetch("X", LTE, GTE))
                .isInstanceOfSatisfying(RateProviderUnavailableException.class,
                        ex -> assertThat(ex.reason()).isEqualTo(Reason.UPSTREAM_ERROR));

        // Our resilience layer invoked the fetcher exactly once — a 4xx is our bad request, not a transient
        // fault, so it is never retried.
        assertThat(counting.calls).isEqualTo(1);
        // And the breaker ignored the 4xx — not counted, stays closed (the breaker gauges upstream health).
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    void a_read_timeout_surfaces_as_TIMEOUT() {
        wireMock.stubFor(get(urlPathEqualTo(PATH)).willReturn(
                okJson("{\"data\":[]}").withFixedDelay(600))); // exceeds this test's tight read timeout
        restClient = restClient(Duration.ofMillis(250)); // tight, so the timeout fires fast
        RateFetcher fetcher = resilient(retry(1), breaker(6, 6)); // no retry — keep the timeout test quick

        assertThatThrownBy(() -> fetcher.fetch("X", LTE, GTE))
                .isInstanceOfSatisfying(RateProviderUnavailableException.class,
                        ex -> assertThat(ex.reason()).isEqualTo(Reason.TIMEOUT));
    }

    @Test
    void the_happy_path_returns_the_selected_row_and_pushes_the_query() {
        wireMock.stubFor(get(urlPathEqualTo(PATH)).willReturn(okJson(
                "{\"data\":[{\"country_currency_desc\":\"Argentina-Peso\",\"exchange_rate\":\"1230.0\","
                        + "\"effective_date\":\"2025-04-15\",\"record_date\":\"2025-04-30\"}]}")));
        RateFetcher fetcher = resilient(retry(3), breaker(6, 6));

        List<ExchangeRate> rates = fetcher.fetch("Argentina-Peso", LTE, GTE);

        assertThat(rates).singleElement().satisfies(r -> {
            assertThat(r.countryCurrencyDesc()).isEqualTo("Argentina-Peso");
            assertThat(r.exchangeRate()).isEqualByComparingTo("1230.0");
        });
        wireMock.verify(getRequestedFor(urlPathEqualTo(PATH))
                .withQueryParam("sort", equalTo("-effective_date"))
                .withQueryParam("page[size]", equalTo("1"))
                .withQueryParam("filter", containing("country_currency_desc:eq:Argentina-Peso")));
    }

    // --- helpers ---------------------------------------------------------------------------------

    /** A RestClient pinned to HTTP/1.1 (deterministic transport) with explicit connect/read timeouts. */
    private RestClient restClient(Duration readTimeout) {
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(500))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(readTimeout);
        return RestClient.builder().baseUrl(wireMock.baseUrl()).requestFactory(factory).build();
    }

    private RateFetcher resilient(Retry retry, CircuitBreaker breaker) {
        counting = new CountingFetcher(new TreasuryRateFetcher(restClient));
        return new ResilientRateFetcher(counting, retry, breaker);
    }

    /** Counts how many times the resilience layer actually invokes the underlying fetcher. */
    private static final class CountingFetcher implements RateFetcher {
        private final RateFetcher delegate;
        private int calls;

        CountingFetcher(RateFetcher delegate) {
            this.delegate = delegate;
        }

        @Override
        public List<ExchangeRate> fetch(String descriptor, LocalDate onOrBefore, LocalDate onOrAfter) {
            calls++;
            return delegate.fetch(descriptor, onOrBefore, onOrAfter);
        }

        @Override
        public List<ExchangeRate> fetchWindow(String descriptor, LocalDate from, LocalDate to) {
            calls++;
            return delegate.fetchWindow(descriptor, from, to);
        }
    }

    private static void assertUpstreamError(RateFetcher fetcher) {
        assertThatThrownBy(() -> fetcher.fetch("X", LTE, GTE))
                .isInstanceOfSatisfying(RateProviderUnavailableException.class,
                        ex -> assertThat(ex.reason()).isEqualTo(Reason.UPSTREAM_ERROR));
    }

    private static Retry retry(int maxAttempts) {
        return Retry.of("test", RetryConfig.custom()
                .maxAttempts(maxAttempts)
                .waitDuration(Duration.ofMillis(1))
                .retryExceptions(HttpServerErrorException.class, ResourceAccessException.class)
                .build());
    }

    private static CircuitBreaker breaker(int windowSize, int minimumCalls) {
        return CircuitBreaker.of("test", CircuitBreakerConfig.custom()
                .slidingWindowType(SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(windowSize)
                .minimumNumberOfCalls(minimumCalls)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(60)) // stay open for the assertion
                .permittedNumberOfCallsInHalfOpenState(3)
                .recordExceptions(HttpServerErrorException.class, ResourceAccessException.class)
                .ignoreExceptions(HttpClientErrorException.class, TreasuryContractException.class)
                .build());
    }
}
