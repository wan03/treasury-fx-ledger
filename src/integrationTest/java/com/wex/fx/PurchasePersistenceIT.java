package com.wex.fx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Date;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Phase-1 persistence slice (T1.3). Asserts the migration set applies cleanly,
 * money keeps its {@code NUMERIC(19,2)} scale across a round-trip, the DB CHECK
 * constraints mirror the edge validation, and the least-privilege {@code app}
 * role is genuinely fenced (no DDL, no UPDATE/DELETE on the append-only ledger).
 *
 * The autowired {@link JdbcTemplate} is backed by the {@code app}-role datasource
 * (see {@link AbstractPostgresIT}), so every assertion runs at the real boundary.
 */
class PurchasePersistenceIT extends AbstractPostgresIT {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void migrations_apply_and_expected_tables_exist() {
        // The app role deliberately can't read flyway_schema_history (it's owned by
        // `migration`) — that IS the least-privilege boundary working. So verify the
        // migrations ran by their effect: the three tables exist and are visible to app.
        Integer tables = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' "
                        + "AND table_name IN ('purchases','idempotency_keys','exchange_rates')",
                Integer.class);
        assertThat(tables).isEqualTo(3);
    }

    @Test
    void numeric_scale_is_preserved_on_round_trip() {
        UUID id = UUID.fromString("018f0000-0000-7000-8000-000000000010");
        jdbc.update("INSERT INTO purchases(id, description, transaction_date, amount) VALUES (?,?,?,?)",
                id, "scale check", Date.valueOf("2026-05-01"), new BigDecimal("12.30"));

        BigDecimal amount = jdbc.queryForObject(
                "SELECT amount FROM purchases WHERE id = ?", BigDecimal.class, id);

        assertThat(amount).isEqualByComparingTo("12.30");
        assertThat(amount.scale()).isEqualTo(2);                 // 12.30 must NOT collapse to 12.3
        assertThat(amount.toPlainString()).isEqualTo("12.30");
    }

    // NOTE on assertions below: Spring 6 no longer folds the JDBC cause into
    // DataAccessException.getMessage(), so we assert on the stack trace, which still
    // carries the underlying PostgreSQL error text (constraint name / "permission denied").

    @Test
    void check_constraints_reject_bad_rows() {
        assertThatThrownBy(() -> insert("ok", "2026-05-01", new BigDecimal("0")))
                .hasStackTraceContaining("purchases_amount_check");
        assertThatThrownBy(() -> insert("ok", "2026-05-01", new BigDecimal("-1")))
                .hasStackTraceContaining("purchases_amount_check");
        assertThatThrownBy(() -> insert("", "2026-05-01", new BigDecimal("5")))
                .hasStackTraceContaining("purchases_description_check");
    }

    @Test
    void app_role_is_denied_ddl() {
        assertThatThrownBy(() -> jdbc.execute("CREATE TABLE should_not_exist (x int)"))
                .hasStackTraceContaining("permission denied");
    }

    @Test
    void app_role_is_denied_mutation_on_append_only_ledger() {
        assertThatThrownBy(() -> jdbc.update("UPDATE purchases SET amount = amount"))
                .hasStackTraceContaining("permission denied");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM purchases"))
                .hasStackTraceContaining("permission denied");
    }

    private void insert(String description, String date, BigDecimal amount) {
        jdbc.update("INSERT INTO purchases(id, description, transaction_date, amount) VALUES (?,?,?,?)",
                UUID.randomUUID(), description, Date.valueOf(date), amount);
    }
}
