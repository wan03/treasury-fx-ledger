package com.wex.fx;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Integration test for the actuator surface (finding #10): only {@code /actuator/health} (and its
 * liveness/readiness probe groups, which the platform health check uses) is exposed — {@code /actuator/info}
 * was dropped, so it must now 404. Keeps the management surface minimal (constitution §9).
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
}
