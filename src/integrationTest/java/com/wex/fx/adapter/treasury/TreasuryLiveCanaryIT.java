package com.wex.fx.adapter.treasury;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.wex.fx.domain.currency.CurrencyMap;
import com.wex.fx.domain.rate.ExchangeRate;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * The live Treasury <strong>canary</strong> (T6.2, test-strategy §4) — the one test that touches the real
 * <em>Reporting Rates of Exchange</em> API. It is <strong>{@code @Tag("live")} and never gates a PR</strong>
 * (constitution §10: the gating suite makes zero real network calls); {@code build.gradle.kts} excludes the
 * {@code live} tag by default and opts it in with {@code -Plive} for nightly/manual runs. It exists to catch
 * the failures a mocked Treasury <em>cannot</em>: an upstream that silently renames a field, restructures the
 * dataset, or drops a currency our curated map still points at — drift our fixtures would happily reproduce.
 *
 * <p>Three properties, each a contract we depend on and cannot mock:
 * <ul>
 *   <li><b>Fields still present (F1/F2).</b> A known descriptor still returns a row whose four fields parse —
 *       proving {@code country_currency_desc}, {@code exchange_rate} (a <em>string</em>), {@code effective_date}
 *       and {@code record_date} are intact. A rename would throw {@link TreasuryContractException} here.
 *   <li><b>Every mapped descriptor still resolves.</b> Iterating {@link CurrencyMap#asMap()} (USD excluded by
 *       design), each curated {@code country_currency_desc} must still match ≥1 live row — the check that the
 *       map hasn't drifted away from the dataset.
 *   <li><b>{@code XOF ≠ XAF} (F5/F9).</b> The two "Cfa Franc" descriptors resolve to <em>different</em> live
 *       rates — the trap that proves the map distinguishes them rather than collapsing on the shared words.
 * </ul>
 *
 * <p>Plain JUnit: no Spring context, no container. It builds a bounded-timeout {@link RestClient} straight at
 * the production base URL and the package-private {@link TreasuryRateFetcher}. A generous 24-month lookback
 * (wider than the 6-month <em>selection</em> rule, which the fixtures test) keeps it from flaking on a
 * currency whose most recent quarterly rate is simply a little stale.
 */
@Tag("live")
class TreasuryLiveCanaryIT {

    private static final String BASE_URL =
            "https://api.fiscaldata.treasury.gov/services/api/fiscal_service";
    private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);
    private static final LocalDate WINDOW_START = TODAY.minusMonths(24);

    private static final CurrencyMap MAP = CurrencyMap.loadDefault();
    private final RateFetcher fetcher = new TreasuryRateFetcher(restClient());

    @Test
    void liveDataset_stillReturnsTheFourExpectedFields() {
        String euro = MAP.asMap().get("EUR");
        assertThat(euro).as("EUR must be in the curated map").isNotNull();

        // toDomain() parses every field and the ExchangeRate constructor enforces non-null + positive,
        // so a single live row reaching us intact proves F1/F2: the field names and the string-typed rate.
        assertThatCode(() -> {
            List<ExchangeRate> rows = fetcher.fetch(euro, TODAY, WINDOW_START);
            assertThat(rows).as("live rate for %s within 24 months", euro).isNotEmpty();
            ExchangeRate r = rows.get(0);
            assertThat(r.countryCurrencyDesc()).isEqualTo(euro);
            assertThat(r.exchangeRate().signum()).isPositive();
            assertThat(r.effectiveDate()).isBeforeOrEqualTo(TODAY);
            assertThat(r.recordDate()).isNotNull();
        }).doesNotThrowAnyException();
    }

    @Test
    void everyMappedDescriptor_stillResolvesToALiveRow() {
        // One pass, collect every miss, then fail once with the full list — so a quarterly rename shows
        // ALL affected currencies at a glance instead of failing on the first.
        List<String> unresolved = new ArrayList<>();
        for (Map.Entry<String, String> entry : MAP.asMap().entrySet()) {
            List<ExchangeRate> rows = fetcher.fetch(entry.getValue(), TODAY, WINDOW_START);
            if (rows.isEmpty()) {
                unresolved.add(entry.getKey() + " -> '" + entry.getValue() + "'");
            }
        }
        assertThat(unresolved)
                .as("curated descriptors with no live rate in the last 24 months (map has drifted)")
                .isEmpty();
    }

    @Test
    void xof_isNotXaf_differentLiveRates() {
        String xof = MAP.asMap().get("XOF"); // Senegal-Cfa Franc
        String xaf = MAP.asMap().get("XAF"); // Cameroon-Cfa Franc
        assertThat(xof).isNotNull();
        assertThat(xaf).isNotNull();
        assertThat(xof).as("the two Cfa Franc descriptors must be distinct strings").isNotEqualTo(xaf);

        ExchangeRate xofRate = first(fetcher.fetch(xof, TODAY, WINDOW_START), xof);
        ExchangeRate xafRate = first(fetcher.fetch(xaf, TODAY, WINDOW_START), xaf);

        // Same words ("Cfa Franc"), genuinely different currencies → different live rates (F5/F9).
        assertThat(xofRate.exchangeRate())
                .as("XOF (%s) and XAF (%s) must price differently", xof, xaf)
                .isNotEqualByComparingTo(xafRate.exchangeRate());
    }

    private static ExchangeRate first(List<ExchangeRate> rows, String descriptor) {
        assertThat(rows).as("live rate for %s within 24 months", descriptor).isNotEmpty();
        return rows.get(0);
    }

    /** A bounded-timeout client straight at production Treasury — no resilience/cache (this is a probe). */
    private static RestClient restClient() {
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(10));
        return RestClient.builder().baseUrl(BASE_URL).requestFactory(factory).build();
    }
}
