package com.wex.fx.adapter.web;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.wex.fx.application.ConvertPurchaseService;
import com.wex.fx.application.dto.ConversionResponse;
import com.wex.fx.application.error.MalformedCurrencyException;
import com.wex.fx.application.error.NoRateAvailableException;
import com.wex.fx.application.error.PurchaseNotFoundException;
import com.wex.fx.application.error.RateProviderUnavailableException;
import com.wex.fx.application.error.RateProviderUnavailableException.Reason;
import com.wex.fx.application.error.UnsupportedCurrencyException;
import com.wex.fx.config.JacksonConfig;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web slice for {@link ConversionController} (T5.4): the R2 projection's HTTP contract — the Treasury
 * conversion body (money + rate as JSON strings, {@code rateEffectiveDate}, {@code rateSource}), the
 * USD identity body, the 1-hour {@code private} cache, and the full error fan-out: malformed {@code 400},
 * unsupported / no-rate {@code 422}, unknown id {@code 404}, and the upstream {@code 502/503/504} with a
 * {@code Retry-After} only on the open-circuit {@code 503}. The use case is mocked.
 */
@WebMvcTest(ConversionController.class)
@Import({WebConfig.class, JacksonConfig.class})
class ConversionControllerTest {

    private static final UUID ID = UUID.fromString("0190f3e2-7e6a-7c3e-9b1a-2c4d6e8f0a11");

    @Autowired private MockMvc mvc;
    @MockitoBean private ConvertPurchaseService conversions;

    private static ConversionResponse treasury() {
        return new ConversionResponse(
                ID,
                "Office supplies",
                LocalDate.parse("2026-03-15"),
                new BigDecimal("12.34"),
                "USD",
                "EUR",
                new BigDecimal("0.924"),
                LocalDate.parse("2026-03-01"),
                new BigDecimal("11.40"),
                ConversionResponse.TREASURY_SOURCE);
    }

    private static ConversionResponse identity() {
        return new ConversionResponse(
                ID,
                "Office supplies",
                LocalDate.parse("2026-03-15"),
                new BigDecimal("12.34"),
                "USD",
                "USD",
                new BigDecimal("1.00"),
                null, // no upstream rate for the identity
                new BigDecimal("12.34"),
                ConversionResponse.IDENTITY_SOURCE);
    }

    // --- success ---------------------------------------------------------------------------------

    @Test
    void convert_treasury_returns200_withRateAndCache() throws Exception {
        when(conversions.convert(ID, "EUR")).thenReturn(treasury());

        mvc.perform(get("/v1/purchases/{id}/conversions/{cc}", ID, "EUR"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("max-age=3600")))
                .andExpect(header().string("Cache-Control", containsString("private")))
                .andExpect(header().exists("ETag"))
                .andExpect(header().string("Content-Security-Policy", containsString("default-src 'none'")))
                .andExpect(jsonPath("$.targetCurrency").value("EUR"))
                .andExpect(jsonPath("$.originalAmount").value("12.34"))
                .andExpect(jsonPath("$.exchangeRate").value("0.924"))
                .andExpect(jsonPath("$.exchangeRate").isString()) // rate is a JSON string too
                .andExpect(jsonPath("$.convertedAmount").value("11.40"))
                .andExpect(jsonPath("$.rateEffectiveDate").value("2026-03-01"))
                .andExpect(jsonPath("$.rateSource").value(ConversionResponse.TREASURY_SOURCE));
    }

    @Test
    void convert_usdIdentity_returns200_rateOne_noEffectiveDate() throws Exception {
        when(conversions.convert(ID, "USD")).thenReturn(identity());

        mvc.perform(get("/v1/purchases/{id}/conversions/{cc}", ID, "USD"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exchangeRate").value("1.00"))
                .andExpect(jsonPath("$.convertedAmount").value("12.34"))
                .andExpect(jsonPath("$.rateEffectiveDate").doesNotExist()) // null dropped (non_null)
                .andExpect(jsonPath("$.rateSource").value(ConversionResponse.IDENTITY_SOURCE));
    }

    // --- errors ----------------------------------------------------------------------------------

    @Test
    void convert_malformedCurrency_returns400() throws Exception {
        when(conversions.convert(ID, "eur")).thenThrow(new MalformedCurrencyException("eur"));

        mvc.perform(get("/v1/purchases/{id}/conversions/{cc}", ID, "eur"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("CURRENCY_CODE_MALFORMED"))
                .andExpect(jsonPath("$.traceId").exists());
    }

    @Test
    void convert_unsupportedCurrency_returns422() throws Exception {
        when(conversions.convert(ID, "ZZZ")).thenThrow(new UnsupportedCurrencyException("ZZZ"));

        mvc.perform(get("/v1/purchases/{id}/conversions/{cc}", ID, "ZZZ"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CURRENCY_UNSUPPORTED"))
                .andExpect(jsonPath("$.detail", containsString("ZZZ")));
    }

    @Test
    void convert_noRate_returns422_namingPairAndDate() throws Exception {
        when(conversions.convert(ID, "JPY"))
                .thenThrow(new NoRateAvailableException(ID, "JPY", LocalDate.parse("2026-03-15")));

        mvc.perform(get("/v1/purchases/{id}/conversions/{cc}", ID, "JPY"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("NO_RATE_AVAILABLE"))
                .andExpect(jsonPath("$.detail", allOf(
                        containsString("USD to JPY"), containsString("2026-03-15"))));
    }

    @Test
    void convert_unknownPurchase_returns404() throws Exception {
        when(conversions.convert(ID, "EUR")).thenThrow(new PurchaseNotFoundException(ID));

        mvc.perform(get("/v1/purchases/{id}/conversions/{cc}", ID, "EUR"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PURCHASE_NOT_FOUND"));
    }

    // --- upstream outage: 502 / 504 / 503 --------------------------------------------------------

    @Test
    void convert_upstreamError_returns502() throws Exception {
        when(conversions.convert(ID, "EUR"))
                .thenThrow(new RateProviderUnavailableException(Reason.UPSTREAM_ERROR, null));

        mvc.perform(get("/v1/purchases/{id}/conversions/{cc}", ID, "EUR"))
                .andExpect(status().isBadGateway())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UPSTREAM_BAD_GATEWAY"));
    }

    @Test
    void convert_upstreamTimeout_returns504() throws Exception {
        when(conversions.convert(ID, "EUR"))
                .thenThrow(new RateProviderUnavailableException(Reason.TIMEOUT, null));

        mvc.perform(get("/v1/purchases/{id}/conversions/{cc}", ID, "EUR"))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.code").value("UPSTREAM_TIMEOUT"));
    }

    @Test
    void convert_circuitOpen_returns503_withRetryAfter() throws Exception {
        when(conversions.convert(ID, "EUR"))
                .thenThrow(new RateProviderUnavailableException(Reason.CIRCUIT_OPEN, null));

        mvc.perform(get("/v1/purchases/{id}/conversions/{cc}", ID, "EUR"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "30"))
                .andExpect(jsonPath("$.code").value("UPSTREAM_UNAVAILABLE"));
    }
}
