package com.wex.fx;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Integration test for request correlation (finding #5): an inbound W3C {@code traceparent} is continued
 * by Micrometer Tracing, and the RFC 9457 problem body's {@code traceId} is the <em>active trace id</em> —
 * so the error correlates to that trace (and to every log line for the request), not a random per-error
 * UUID that points at nothing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TracingCorrelationIT extends AbstractPostgresIT {

    @Autowired private TestRestTemplate rest;
    @Autowired private ObjectMapper json;

    @Test
    void inbound_traceparent_is_continued_and_becomes_the_problem_traceId() throws Exception {
        String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";              // 32-hex W3C trace id
        HttpHeaders headers = new HttpHeaders();
        headers.set("traceparent", "00-" + traceId + "-00f067aa0ba902b7-01");

        // An unknown id deterministically produces a 404 problem+json — no DB state needed.
        ResponseEntity<String> resp = rest.exchange(
                "/v1/purchases/{id}", HttpMethod.GET, new HttpEntity<>(headers), String.class,
                "0190f3e2-7e6a-7c3e-9b1a-2c4d6e8f0a11");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        JsonNode body = json.readTree(resp.getBody());
        assertThat(body.get("code").asText()).isEqualTo("PURCHASE_NOT_FOUND");
        // The problem traceId is the continued inbound trace id, not a fresh random one.
        assertThat(body.get("traceId").asText()).isEqualTo(traceId);
    }
}
