package com.wex.fx.adapter.web;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.wex.fx.application.ConvertPurchaseService;
import com.wex.fx.application.error.RateProviderUnavailableException;
import com.wex.fx.application.error.RateProviderUnavailableException.Reason;
import com.wex.fx.config.JacksonConfig;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Proves the 503 {@code Retry-After} is derived from {@code fx.rates.resilience.wait-duration-in-open-state}
 * (finding #9): with the breaker's open-state wait overridden to 45s, the advertised backoff follows to 45 —
 * so retuning the breaker can never let the advertised value silently drift.
 */
@WebMvcTest(ConversionController.class)
@Import({WebConfig.class, JacksonConfig.class})
@TestPropertySource(properties = "fx.rates.resilience.wait-duration-in-open-state=45s")
class ApiExceptionHandlerRetryAfterConfigTest {

    private static final UUID ID = UUID.fromString("0190f3e2-7e6a-7c3e-9b1a-2c4d6e8f0a11");

    @Autowired private MockMvc mvc;
    @MockitoBean private ConvertPurchaseService conversions;

    @Test
    void retry_after_tracks_the_configured_open_state_wait() throws Exception {
        when(conversions.convert(ID, "EUR"))
                .thenThrow(new RateProviderUnavailableException(Reason.CIRCUIT_OPEN, null));

        mvc.perform(get("/v1/purchases/{id}/conversions/{cc}", ID, "EUR"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "45"));
    }
}
