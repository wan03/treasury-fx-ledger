package com.wex.fx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Integration test for the app-datasource session guards (finding #1): a runaway statement is aborted by
 * {@code statement_timeout} so a stuck query can never pin a connection and cascade into pool exhaustion,
 * while Flyway (its own connection, no app timeouts) is unaffected. Crucially the timeout is a
 * <strong>role default</strong> ({@code ALTER ROLE app …} in db/init/00-roles.sql) that survives PgBouncer
 * pooling — proven here by reading it on a RAW connection that bypasses Hikari's connection-init-sql.
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
    void the_app_role_carries_the_timeout_as_a_durable_default() throws Exception {
        // RAW connection as the app role — NO Hikari connection-init-sql — so SHOW reflects only the
        // ALTER ROLE default from db/init/00-roles.sql, the guard that survives transaction pooling (#1).
        try (Connection c =
                        DriverManager.getConnection(POSTGRES.getJdbcUrl(), "app", "change-me-app");
                Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("SHOW statement_timeout")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("3s");
        }
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
