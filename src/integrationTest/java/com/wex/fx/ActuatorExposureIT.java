package com.wex.fx;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Integration test for the actuator surface. {@code /actuator/health} (+ its liveness/readiness probe
 * groups) gates the platform health check; {@code /actuator/info} is unexposed → 404 (constitution §9);
 * and {@code /actuator/prometheus} (finding #4) serves JVM + the manually-built resilience4j
 * breaker/retry/bulkhead meters, tagged with the application name, on the main port (so Render's
 * single-port health check is unaffected).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ActuatorExposureIT extends AbstractPostgresIT {

    @Autowired
    private TestRestTemplate rest;

    @Test
    void health_is_exposed_and_up() {
        ResponseEntity<String> health = rest.getForEntity("/actuator/health", String.class);
        assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(health.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    void liveness_and_readiness_probe_groups_are_up() {
        assertThat(rest.getForEntity("/actuator/health/liveness", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(rest.getForEntity("/actuator/health/readiness", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void info_is_no_longer_exposed() {
        ResponseEntity<String> info = rest.getForEntity("/actuator/info", String.class);
        assertThat(info.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void prometheus_scrape_exposes_jvm_and_bound_resilience_metrics() {
        ResponseEntity<String> prom = rest.getForEntity("/actuator/prometheus", String.class);
        assertThat(prom.getStatusCode()).isEqualTo(HttpStatus.OK);

        String body = prom.getBody();
        assertThat(body)
                .contains("jvm_memory_used_bytes")               // JVM binder is live
                // The manually-built breaker/retry/bulkhead are bound (finding #4) — gauges present
                // before any call, tagged with our named instance and the application common tag.
                .contains("resilience4j_circuitbreaker_state")
                .contains("resilience4j_bulkhead_available_concurrent_calls")
                .contains("name=\"treasury\"")
                .contains("application=\"currency-ledger\"");
    }
}
