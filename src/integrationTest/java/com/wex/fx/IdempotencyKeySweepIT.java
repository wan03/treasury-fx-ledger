package com.wex.fx;

import static org.assertj.core.api.Assertions.assertThat;

import com.wex.fx.application.dto.PurchaseResponse;
import com.wex.fx.application.port.IdempotencyStore;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Integration test for the idempotency TTL sweep (finding #1) against real Postgres. Proves
 * {@link IdempotencyStore#deleteExpired} removes only rows past the cutoff, retains fresh ones, returns the
 * exact deleted count, and honours the batch bound. A fixed cutoff in the distant past keeps the test
 * isolated on the shared container: only the rows this test inserts are "expired" relative to it.
 */
class IdempotencyKeySweepIT extends AbstractPostgresIT {

    // A cutoff far in the past, so no other test's (future-dated) idempotency rows count as expired here.
    private static final Instant CUTOFF = Instant.parse("2020-06-01T00:00:00Z");

    @Autowired
    IdempotencyStore store;
    @Autowired
    DataSource dataSource;

    @Test
    void sweeps_only_expired_rows_and_returns_their_count() {
        UUID purchaseId = insertPurchase();
        save("sweep-expired-1", purchaseId, CUTOFF.minusSeconds(86_400)); // before cutoff → swept
        save("sweep-expired-2", purchaseId, CUTOFF.minusSeconds(1));      // before cutoff → swept
        save("sweep-fresh", purchaseId, CUTOFF.plusSeconds(86_400));      // after cutoff  → kept

        int deleted = store.deleteExpired(CUTOFF, 1000);

        assertThat(deleted).isEqualTo(2);
        assertThat(store.find("sweep-expired-1")).isEmpty();
        assertThat(store.find("sweep-expired-2")).isEmpty();
        assertThat(store.find("sweep-fresh")).isPresent();
    }

    @Test
    void deletes_are_bounded_by_the_batch_limit() {
        UUID purchaseId = insertPurchase();
        save("sweep-batch-1", purchaseId, CUTOFF.minusSeconds(3));
        save("sweep-batch-2", purchaseId, CUTOFF.minusSeconds(2));
        save("sweep-batch-3", purchaseId, CUTOFF.minusSeconds(1));

        // A limit of 2 caps the first pass; the loop's remainder is drained on the next.
        assertThat(store.deleteExpired(CUTOFF, 2)).isEqualTo(2);
        assertThat(store.deleteExpired(CUTOFF, 2)).isEqualTo(1);
        assertThat(store.deleteExpired(CUTOFF, 2)).isZero();
    }

    private UUID insertPurchase() {
        UUID id = UUID.randomUUID();
        new JdbcTemplate(dataSource).update(
                "INSERT INTO purchases (id, description, transaction_date, amount, currency)"
                        + " VALUES (?, ?, ?, ?, ?)",
                id, "sweep fixture", Date.valueOf("2025-05-01"), new BigDecimal("10.00"), "USD");
        return id;
    }

    private void save(String key, UUID purchaseId, Instant expiresAt) {
        PurchaseResponse body = new PurchaseResponse(
                purchaseId, "sweep fixture", LocalDate.parse("2025-05-01"),
                new BigDecimal("10.00"), "USD", CUTOFF);
        store.save(key, "hash-" + key, purchaseId, 201, body, expiresAt);
    }
}
