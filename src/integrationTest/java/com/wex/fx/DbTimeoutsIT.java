package com.wex.fx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Integration test for the app-datasource session guards (finding #2): the {@code statement_timeout} set
 * via Hikari {@code connection-init-sql} must abort a runaway statement on a pooled connection so a stuck
 * query can never pin a connection and cascade into pool exhaustion — while Flyway (its own connection,
 * no app timeouts) stays unaffected, which the migrated schema being present already proves.
 */
class DbTimeoutsIT extends AbstractPostgresIT {

    @Autowired
    DataSource dataSource;

    @Test
    void a_statement_exceeding_statement_timeout_is_aborted_by_postgres() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        // statement_timeout is 3s on the app datasource; sleeping 5s must be canceled, not hang.
        assertThatThrownBy(() -> jdbc.queryForObject("SELECT pg_sleep(5)", Void.class))
                .hasMessageContaining("statement timeout");
    }

    @Test
    void flyway_migrations_applied_despite_the_app_timeouts() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        // The migrated tables exist and are queryable through the app role — Flyway ran on its own
        // connection, untouched by the app session timeouts. A fast PK/aggregate query is well under 3s.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM purchases", Long.class)).isNotNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_keys", Long.class)).isNotNull();
    }
}
