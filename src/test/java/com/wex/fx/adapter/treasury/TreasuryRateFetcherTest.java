package com.wex.fx.adapter.treasury;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.wex.fx.domain.rate.ExchangeRate;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Fast unit test for {@link TreasuryRateFetcher} (no network): {@link MockRestServiceServer} intercepts
 * the {@link RestClient} so we can both (a) assert the outgoing request is the full F7 push-down query and
 * (b) feed captured-shape Treasury JSON back through the real Jackson mapping. Covers the query contract,
 * the "no rate" outcomes (empty / absent {@code data}), schema tolerance, and the contract-violation
 * mapping to {@link TreasuryContractException}.
 */
class TreasuryRateFetcherTest {

    private static final LocalDate LTE = LocalDate.parse("2025-05-01"); // purchaseDate
    private static final LocalDate GTE = LocalDate.parse("2024-11-01"); // 6-month window floor

    private MockRestServiceServer server;
    private TreasuryRateFetcher fetcher;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://treasury.test");
        server = MockRestServiceServer.bindTo(builder).build();
        fetcher = new TreasuryRateFetcher(builder.build());
    }

    @Test
    void pushes_the_entire_selection_rule_into_one_query() {
        server.expect(request -> {
            assertThat(request.getURI().getPath()).isEqualTo(TreasuryRateFetcher.PATH);
            String query = request.getURI().getQuery(); // java.net.URI#getQuery is already decoded
            assertThat(query)
                    .contains("fields=country_currency_desc,exchange_rate,effective_date,record_date")
                    .contains("country_currency_desc:eq:Argentina-Peso")
                    .contains("effective_date:lte:2025-05-01")
                    .contains("effective_date:gte:2024-11-01")
                    .contains("sort=-effective_date")
                    .contains("page[size]=1")
                    .contains("format=json");
        }).andRespond(withSuccess(
                body(row("Argentina-Peso", "1230.0", "2025-04-15", "2025-04-30")),
                MediaType.APPLICATION_JSON));

        List<ExchangeRate> rates = fetcher.fetch("Argentina-Peso", LTE, GTE);

        assertThat(rates).singleElement().satisfies(r -> {
            assertThat(r.countryCurrencyDesc()).isEqualTo("Argentina-Peso");
            assertThat(r.exchangeRate()).isEqualByComparingTo("1230.0");
            assertThat(r.effectiveDate()).isEqualTo(LocalDate.parse("2025-04-15"));
            assertThat(r.recordDate()).isEqualTo(LocalDate.parse("2025-04-30"));
        });
        server.verify();
    }

    @Test
    void an_empty_data_array_is_a_normal_no_rate_not_a_failure() {
        server.expect(request -> {}).andRespond(withSuccess("{\"data\":[]}", MediaType.APPLICATION_JSON));
        assertThat(fetcher.fetch("Argentina-Peso", LTE, GTE)).isEmpty();
        server.verify();
    }

    @Test
    void a_body_without_a_data_field_is_a_normal_no_rate() {
        server.expect(request -> {})
                .andRespond(withSuccess("{\"meta\":{\"count\":0}}", MediaType.APPLICATION_JSON));
        assertThat(fetcher.fetch("Argentina-Peso", LTE, GTE)).isEmpty();
        server.verify();
    }

    @Test
    void tolerates_unknown_fields_at_both_the_envelope_and_row_level() {
        String json = "{\"data\":[{"
                + "\"country_currency_desc\":\"Argentina-Peso\",\"exchange_rate\":\"1230.0\","
                + "\"effective_date\":\"2025-04-15\",\"record_date\":\"2025-04-30\","
                + "\"src_currency\":\"ARS\",\"future_field\":42}],"
                + "\"meta\":{\"count\":1,\"labels\":{}},\"links\":{\"self\":\"/x\"}}";
        server.expect(request -> {}).andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        assertThat(fetcher.fetch("Argentina-Peso", LTE, GTE))
                .singleElement()
                .extracting(ExchangeRate::exchangeRate, ExchangeRate::effectiveDate)
                .containsExactly(new java.math.BigDecimal("1230.0"), LocalDate.parse("2025-04-15"));
        server.verify();
    }

    @Test
    void a_row_missing_a_critical_field_is_a_contract_violation() {
        // exchange_rate absent — a 2xx with a structurally broken body is OUR contract problem, not a
        // business "no rate". It must surface as TreasuryContractException (→ 502), naming only the field.
        String json = "{\"data\":[{\"country_currency_desc\":\"Argentina-Peso\","
                + "\"effective_date\":\"2025-04-15\",\"record_date\":\"2025-04-30\"}]}";
        server.expect(request -> {}).andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fetcher.fetch("Argentina-Peso", LTE, GTE))
                .isInstanceOf(TreasuryContractException.class)
                .hasMessageContaining("exchange_rate")
                .hasMessageNotContaining("Argentina"); // never echo row content
        server.verify();
    }

    @Test
    void a_non_numeric_exchange_rate_is_a_contract_violation() {
        server.expect(request -> {}).andRespond(withSuccess(
                body(row("Argentina-Peso", "not-a-number", "2025-04-15", "2025-04-30")),
                MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fetcher.fetch("Argentina-Peso", LTE, GTE))
                .isInstanceOf(TreasuryContractException.class)
                .hasMessageContaining("exchange_rate")
                .hasMessageNotContaining("not-a-number"); // the bad value never leaks into the message
        server.verify();
    }

    @Test
    void a_non_iso_effective_date_is_a_contract_violation() {
        server.expect(request -> {}).andRespond(withSuccess(
                body(row("Argentina-Peso", "1230.0", "15-04-2025", "2025-04-30")),
                MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fetcher.fetch("Argentina-Peso", LTE, GTE))
                .isInstanceOf(TreasuryContractException.class)
                .hasMessageContaining("effective_date");
        server.verify();
    }

    // --- JSON fixtures (captured Treasury shape) -------------------------------------------------

    private static String body(String... rows) {
        return "{\"data\":[" + String.join(",", rows) + "],\"meta\":{\"count\":" + rows.length + "}}";
    }

    private static String row(String desc, String rate, String effective, String record) {
        return "{\"country_currency_desc\":\"" + desc + "\",\"exchange_rate\":\"" + rate
                + "\",\"effective_date\":\"" + effective + "\",\"record_date\":\"" + record + "\"}";
    }
}
