package com.wex.fx;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.wex.fx.application.dto.ConversionResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Full-stack E2E (T6.1): real HTTP over a random port → the wired application → a prod-parity Postgres
 * (the least-privilege role split from {@link AbstractPostgresIT}) → a WireMock standing in for Treasury.
 * Provider A ({@code ondemand}, the production default) is left in place, so the real fetch → resilience →
 * cache → {@code RateSelector} → web pipeline runs untouched. These are <em>wiring</em> tests — the edge
 * cases live in the unit/slice suites — so they assert the four properties that prove the seams connect:
 *
 * <ul>
 *   <li>golden path: POST a USD purchase, GET it converted (rate + converted amount, money as strings);
 *   <li>no rate in the 6-month window → {@code 422 NO_RATE_AVAILABLE};
 *   <li>the intra-quarter <strong>amendment</strong> (Argentina) is selected — {@code 1230}, not the
 *       quarter-base or the later out-of-window row (D-02/F8);
 *   <li>idempotent create: the same {@code Idempotency-Key} twice yields one purchase and the same body.
 * </ul>
 *
 * <p>Each test uses a distinct currency so provider A's process-wide cache never bleeds between cases;
 * WireMock stubs are scoped by the descriptor in the {@code filter} query for the same reason.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PurchaseConversionE2EIT extends AbstractPostgresIT {

    private static final String TREASURY_PATH = "/v1/accounting/od/rates_of_exchange";

    /** One WireMock for the suite; started before the context so the Treasury base-url binds to it. */
    private static final WireMockServer WIREMOCK =
            new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        WIREMOCK.start();
    }

    @DynamicPropertySource
    static void treasuryProperties(DynamicPropertyRegistry registry) {
        // Point the app's Treasury RestClient at WireMock; the fetcher appends TREASURY_PATH.
        registry.add("fx.rates.treasury.base-url", WIREMOCK::baseUrl);
    }

    @AfterAll
    static void stopWireMock() {
        WIREMOCK.stop();
    }

    @Autowired private TestRestTemplate rest;
    @Autowired private ObjectMapper json;

    @BeforeEach
    void resetStubs() {
        WIREMOCK.resetAll();
    }

    // --- golden path -----------------------------------------------------------------------------

    @Test
    void goldenPath_store_thenConvertToEur() throws Exception {
        stubRate("Euro Zone-Euro", row("Euro Zone-Euro", "0.924", "2025-04-01", "2025-04-01"));

        String id = createPurchase("Office supplies", "100.00", "2025-05-01");

        ResponseEntity<String> conv =
                rest.getForEntity("/v1/purchases/{id}/conversions/{cc}", String.class, id, "EUR");
        assertThat(conv.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode body = json.readTree(conv.getBody());
        assertThat(body.get("purchaseId").asText()).isEqualTo(id);
        assertThat(body.get("targetCurrency").asText()).isEqualTo("EUR");
        assertThat(body.get("originalAmount").asText()).isEqualTo("100.00");
        assertThat(body.get("exchangeRate").asText()).isEqualTo("0.924");
        assertThat(body.get("exchangeRate").isTextual()).isTrue(); // money/rate cross the wire as strings
        assertThat(body.get("rateEffectiveDate").asText()).isEqualTo("2025-04-01");
        assertThat(body.get("convertedAmount").asText()).isEqualTo("92.40"); // 100.00 × 0.924, HALF_UP 2dp
        assertThat(body.get("rateSource").asText()).isEqualTo(ConversionResponse.TREASURY_SOURCE);
    }

    // --- no rate in the window → 422 -------------------------------------------------------------

    @Test
    void convert_noRateInWindow_returns422() throws Exception {
        // A supported currency, but Treasury has nothing in the 6-month window → empty data.
        WIREMOCK.stubFor(get(urlPathEqualTo(TREASURY_PATH))
                .withQueryParam("filter", containing("United Kingdom-Pound"))
                .willReturn(okJson("{\"data\":[]}")));

        String id = createPurchase("Old ledger entry", "50.00", "2025-05-01");

        ResponseEntity<String> conv =
                rest.getForEntity("/v1/purchases/{id}/conversions/{cc}", String.class, id, "GBP");
        assertThat(conv.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        JsonNode body = json.readTree(conv.getBody());
        assertThat(body.get("code").asText()).isEqualTo("NO_RATE_AVAILABLE");
        assertThat(body.get("traceId").asText()).isNotBlank();
        assertThat(body.get("status").asInt()).isEqualTo(422); // status stays a JSON number
    }

    // --- intra-quarter amendment is selected (1230) ----------------------------------------------

    @Test
    void convert_selectsIntraQuarterAmendment_notBaseOrLaterRow() throws Exception {
        // Q1 base, the 2025-04-15 amendment, and a later row that is *after* the purchase date.
        stubRate("Argentina-Peso",
                row("Argentina-Peso", "1093.0", "2025-03-31", "2025-03-31"),
                row("Argentina-Peso", "1230.0", "2025-04-15", "2025-04-30"),
                row("Argentina-Peso", "1205.0", "2025-06-30", "2025-06-30"));

        String id = createPurchase("Buenos Aires trip", "100.00", "2025-05-01");

        ResponseEntity<String> conv =
                rest.getForEntity("/v1/purchases/{id}/conversions/{cc}", String.class, id, "ARS");
        assertThat(conv.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode body = json.readTree(conv.getBody());
        // max(effective_date) ≤ 2025-05-01 is the amendment, not 1093 (base) or 1205 (after the date).
        assertThat(body.get("exchangeRate").asText()).isEqualTo("1230.0");
        assertThat(body.get("rateEffectiveDate").asText()).isEqualTo("2025-04-15");
        assertThat(body.get("convertedAmount").asText()).isEqualTo("123000.00"); // 100.00 × 1230.0
    }

    // --- idempotent create -----------------------------------------------------------------------

    @Test
    void idempotentCreate_sameKeyTwice_oneRecord_sameBody() throws Exception {
        String requestBody = createBody("Recurring license", "250.00", "2025-05-01");
        HttpHeaders headers = jsonHeaders();
        headers.set("Idempotency-Key", "e2e-idem-key-1");
        HttpEntity<String> request = new HttpEntity<>(requestBody, headers);

        ResponseEntity<String> first = rest.postForEntity("/v1/purchases", request, String.class);
        ResponseEntity<String> second = rest.postForEntity("/v1/purchases", request, String.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        // The first is a genuine create; the second is a replay (same status, but flagged).
        assertThat(first.getHeaders().getFirst("Idempotency-Replayed")).isNull();
        assertThat(second.getHeaders().getFirst("Idempotency-Replayed")).isEqualTo("true");

        String firstId = json.readTree(first.getBody()).get("id").asText();
        String secondId = json.readTree(second.getBody()).get("id").asText();
        assertThat(secondId).isEqualTo(firstId); // one purchase, replayed — not a second insert

        // And the GET of that single id confirms it persisted exactly once with the stored values.
        ResponseEntity<String> fetched =
                rest.getForEntity("/v1/purchases/{id}", String.class, firstId);
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = json.readTree(fetched.getBody());
        assertThat(body.get("amount").asText()).isEqualTo("250.00");
        assertThat(body.get("currency").asText()).isEqualTo("USD");
    }

    // --- the interactive explorer is the front door ----------------------------------------------

    @Test
    void home_servesInteractiveExplorer_sameOrigin_withRelaxedCsp() {
        // GET / forwards to the self-contained explorer page — the recommended live experience.
        ResponseEntity<String> home = rest.getForEntity("/", String.class);

        assertThat(home.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(home.getHeaders().getContentType().isCompatibleWith(MediaType.TEXT_HTML)).isTrue();
        assertThat(home.getBody()).contains("WEX FX Ledger");

        // It is a real HTML page, so it carries the narrowly-relaxed CSP (inline assets + same-origin
        // fetch) — not the data plane's strict policy, and not the page-less Swagger surface.
        String csp = home.getHeaders().getFirst("Content-Security-Policy");
        assertThat(csp).isNotNull();
        assertThat(csp).contains("script-src 'self' 'unsafe-inline'").contains("connect-src 'self'");
        // Baseline transport/anti-framing headers are still present (constitution §9).
        assertThat(home.getHeaders().getFirst("X-Frame-Options")).isEqualTo("DENY");
        assertThat(home.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
    }

    // --- helpers ---------------------------------------------------------------------------------

    private String createPurchase(String description, String amount, String date) throws Exception {
        ResponseEntity<String> resp = rest.postForEntity(
                "/v1/purchases", new HttpEntity<>(createBody(description, amount, date), jsonHeaders()),
                String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getHeaders().getLocation()).isNotNull();
        return json.readTree(resp.getBody()).get("id").asText();
    }

    private static HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private static String createBody(String description, String amount, String date) {
        return "{\"description\":\"" + description + "\",\"transactionDate\":\"" + date
                + "\",\"amount\":\"" + amount + "\",\"currency\":\"USD\"}";
    }

    private static void stubRate(String descriptor, String... rows) {
        WIREMOCK.stubFor(get(urlPathEqualTo(TREASURY_PATH))
                .withQueryParam("filter", containing(descriptor))
                .willReturn(okJson("{\"data\":[" + String.join(",", rows) + "]}")));
    }

    private static String row(String desc, String rate, String effective, String record) {
        return "{\"country_currency_desc\":\"" + desc + "\",\"exchange_rate\":\"" + rate
                + "\",\"effective_date\":\"" + effective + "\",\"record_date\":\"" + record + "\"}";
    }
}
