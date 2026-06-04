package com.wex.fx.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.wex.fx.application.GetPurchaseService;
import com.wex.fx.application.StorePurchaseService;
import com.wex.fx.application.dto.IdempotencyRequest;
import com.wex.fx.application.dto.PurchaseResponse;
import com.wex.fx.application.dto.StoreOutcome;
import com.wex.fx.application.error.CurrencyNotStorableException;
import com.wex.fx.application.error.IdempotencyConflictException;
import com.wex.fx.application.error.PurchaseNotFoundException;
import com.wex.fx.config.JacksonConfig;
import com.wex.fx.domain.validation.FieldError;
import com.wex.fx.domain.validation.ValidationCode;
import com.wex.fx.domain.validation.ValidationException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Web slice for {@link PurchaseController} (T5.4): asserts the HTTP contract in isolation from the
 * domain — status discipline, the RFC 9457 body shape ({@code code}/{@code traceId}/{@code errors[]}),
 * money rendered as JSON <em>strings</em>, the create/replay headers, conditional-GET, the security
 * headers, and the append-only {@code 405}. The use cases are mocked; mappings live in
 * {@link ApiExceptionHandler} and {@link WebConfig}, both pulled into the slice.
 */
@WebMvcTest(PurchaseController.class)
@Import({WebConfig.class, JacksonConfig.class})
class PurchaseControllerTest {

    private static final UUID ID = UUID.fromString("0190f3e2-7e6a-7c3e-9b1a-2c4d6e8f0a11");
    private static final String VALID_BODY =
            """
            {"description":"Office supplies","transactionDate":"2026-03-15","amount":"12.34","currency":"USD"}
            """;

    @Autowired private MockMvc mvc;
    @MockitoBean private StorePurchaseService storePurchases;
    @MockitoBean private GetPurchaseService getPurchases;

    private static PurchaseResponse sampleResponse() {
        return new PurchaseResponse(
                ID,
                "Office supplies",
                LocalDate.parse("2026-03-15"),
                new BigDecimal("12.34"),
                "USD",
                Instant.parse("2026-06-03T09:35:12Z"));
    }

    // --- POST /v1/purchases ----------------------------------------------------------------------

    @Test
    void create_returns201_withLocation_andMoneyAsString() throws Exception {
        when(storePurchases.store(any(), isNull())).thenReturn(StoreOutcome.created(sampleResponse()));

        mvc.perform(post("/v1/purchases").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/v1/purchases/" + ID))
                .andExpect(header().doesNotExist("Idempotency-Replayed"))
                .andExpect(jsonPath("$.id").value(ID.toString()))
                .andExpect(jsonPath("$.amount").value("12.34"))
                .andExpect(jsonPath("$.amount").isString()) // contract: money is a JSON string
                .andExpect(jsonPath("$.currency").value("USD"));
    }

    @Test
    void create_withIdempotencyKey_replay_setsHeader_andFingerprintsRequest() throws Exception {
        when(storePurchases.store(any(), any(IdempotencyRequest.class)))
                .thenReturn(StoreOutcome.replayed(sampleResponse()));

        mvc.perform(post("/v1/purchases")
                        .header("Idempotency-Key", "abc-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotency-Replayed", "true"));

        ArgumentCaptor<IdempotencyRequest> captor = ArgumentCaptor.forClass(IdempotencyRequest.class);
        verify(storePurchases).store(any(), captor.capture());
        assertThat(captor.getValue().key()).isEqualTo("abc-123");
        assertThat(captor.getValue().requestHash()).hasSize(64); // SHA-256 hex
    }

    @Test
    void create_overLongIdempotencyKey_returns400_beforeService() throws Exception {
        mvc.perform(post("/v1/purchases")
                        .header("Idempotency-Key", "k".repeat(256))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.traceId").exists());
    }

    @Test
    void create_validationFailure_returns400_withErrorsArray() throws Exception {
        when(storePurchases.store(any(), isNull()))
                .thenThrow(new ValidationException(List.of(
                        new FieldError("amount", ValidationCode.AMOUNT_PRECISION,
                                "amount must have at most 2 decimal places"))));

        mvc.perform(post("/v1/purchases").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("AMOUNT_PRECISION"))
                .andExpect(jsonPath("$.status").value(400)) // status stays a JSON number
                .andExpect(jsonPath("$.traceId").exists())
                .andExpect(jsonPath("$.errors[0].field").value("amount"))
                .andExpect(jsonPath("$.errors[0].code").value("AMOUNT_PRECISION"))
                .andExpect(jsonPath("$.errors[0].message").value("amount must have at most 2 decimal places"));
    }

    @Test
    void create_nonUsdCurrency_returns422() throws Exception {
        when(storePurchases.store(any(), isNull()))
                .thenThrow(new CurrencyNotStorableException("EUR"));

        mvc.perform(post("/v1/purchases").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CURRENCY_NOT_STORABLE"))
                .andExpect(jsonPath("$.detail", containsString("EUR")));
    }

    @Test
    void create_idempotencyConflict_returns409_withoutEchoingKey() throws Exception {
        when(storePurchases.store(any(), any(IdempotencyRequest.class)))
                .thenThrow(new IdempotencyConflictException("secret-key-value"));

        mvc.perform(post("/v1/purchases")
                        .header("Idempotency-Key", "secret-key-value")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"))
                .andExpect(jsonPath("$.detail", not(containsString("secret-key-value"))));
    }

    @Test
    void create_unknownProperty_returns400Malformed() throws Exception {
        String body =
                """
                {"description":"x","transactionDate":"2026-03-15","amount":"12.34","bogus":"y"}
                """;

        mvc.perform(post("/v1/purchases").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.traceId").exists());
    }

    // --- GET /v1/purchases/{id} ------------------------------------------------------------------

    @Test
    void get_returns200_withCacheControl_etag_andSecurityHeaders() throws Exception {
        when(getPurchases.get(ID)).thenReturn(sampleResponse());

        mvc.perform(get("/v1/purchases/{id}", ID))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("max-age=60")))
                .andExpect(header().string("Cache-Control", containsString("private")))
                .andExpect(header().exists("ETag"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().exists("Strict-Transport-Security"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("Content-Security-Policy", containsString("default-src 'none'")))
                .andExpect(jsonPath("$.id").value(ID.toString()))
                .andExpect(jsonPath("$.amount").isString());
    }

    @Test
    void get_conditional_ifNoneMatch_returns304() throws Exception {
        when(getPurchases.get(ID)).thenReturn(sampleResponse());

        MvcResult first = mvc.perform(get("/v1/purchases/{id}", ID)).andReturn();
        String etag = first.getResponse().getHeader("ETag");

        mvc.perform(get("/v1/purchases/{id}", ID).header("If-None-Match", etag))
                .andExpect(status().isNotModified());
    }

    @Test
    void get_unknownId_returns404() throws Exception {
        when(getPurchases.get(ID)).thenThrow(new PurchaseNotFoundException(ID));

        mvc.perform(get("/v1/purchases/{id}", ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PURCHASE_NOT_FOUND"));
    }

    @Test
    void get_malformedId_returns404_notProtocolError() throws Exception {
        mvc.perform(get("/v1/purchases/{id}", "not-a-uuid"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PURCHASE_NOT_FOUND"));
    }

    // --- append-only (D-09): no PUT/PATCH/DELETE handler exists → framework 405 -------------------

    @Test
    void put_isMethodNotAllowed_provingAppendOnly() throws Exception {
        mvc.perform(put("/v1/purchases/{id}", ID).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.traceId").exists());
    }
}
