package com.wex.fx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.wex.fx.application.StorePurchaseService;
import com.wex.fx.application.dto.IdempotencyRequest;
import com.wex.fx.application.dto.PurchaseResponse;
import com.wex.fx.application.dto.StoreOutcome;
import com.wex.fx.application.dto.StorePurchaseCommand;
import com.wex.fx.application.error.DuplicateIdempotencyKeyException;
import com.wex.fx.application.port.IdempotencyStore;
import com.wex.fx.application.port.PurchaseRepository;
import com.wex.fx.application.port.Transactor;
import com.wex.fx.domain.money.Money;
import com.wex.fx.domain.purchase.Purchase;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Phase-3 persistence slice (T3.4). Drives the real {@code app}-role adapters through their ports
 * against a prod-parity Postgres, proving the things the fast unit suite deliberately cannot:
 *
 * <ul>
 *   <li><strong>Round-trip fidelity</strong> — {@code NUMERIC(19,2)} keeps scale 2 ({@code 12.30}
 *       never collapses to {@code 12.3}) and {@code TIMESTAMPTZ} preserves the {@code Instant};</li>
 *   <li><strong>Re-projection replay fidelity</strong> — the replay body is re-projected from the
 *       persisted {@code purchases} row (finding #8: the idempotency table stores no body), yet equals
 *       the original {@link PurchaseResponse} field-for-field, scale and {@code Instant} included;</li>
 *   <li><strong>The PK-violation primitive</strong> — a reused idempotency key surfaces as the
 *       domain {@link DuplicateIdempotencyKeyException}, the signal the concurrent-loser race relies
 *       on;</li>
 *   <li><strong>Atomicity</strong> — the purchase + idempotency-key dual insert commits or rolls
 *       back as one unit under the real {@link Transactor} (the unit suite's {@code DirectTransactor}
 *       models no rollback, so this is the only place atomicity is actually proven).</li>
 * </ul>
 */
class StorePurchasePersistenceIT extends AbstractPostgresIT {

    @Autowired
    StorePurchaseService service;

    @Autowired
    PurchaseRepository purchases;

    @Autowired
    IdempotencyStore idempotency;

    @Autowired
    Transactor transactor;

    // request_hash is CHAR(64) — exact width for a SHA-256 hex digest. Use a realistic 64-char
    // hash so the stored value isn't space-padded; a shorter token would be padded by CHAR(n) and
    // break the equality-based replay check (the prod hash is always exactly 64 chars, so it never
    // pads in practice — data-model.md).
    private static final String HASH = "0123456789abcdef".repeat(4);   // 64 hex chars

    private static StorePurchaseCommand command() {
        return new StorePurchaseCommand("Office supplies", "12.30", "2026-03-15", null);
    }

    @Test
    void service_stores_a_purchase_and_it_round_trips_with_scale_and_timestamp_intact() {
        StoreOutcome outcome = service.store(command(), null);
        assertThat(outcome.replayed()).isFalse();
        PurchaseResponse body = outcome.response();

        Purchase reloaded = purchases.findById(body.id()).orElseThrow();
        assertThat(reloaded.description()).isEqualTo("Office supplies");
        assertThat(reloaded.transactionDate()).isEqualTo(LocalDate.parse("2026-03-15"));
        assertThat(reloaded.amount().currencyCode()).isEqualTo("USD");
        assertThat(reloaded.amount().amount()).isEqualByComparingTo("12.30");
        assertThat(reloaded.amount().amount().scale()).isEqualTo(2);     // 12.30 must NOT collapse to 12.3
        assertThat(reloaded.createdAt()).isEqualTo(body.createdAt());    // Instant survives timestamptz
    }

    @Test
    void same_key_replays_the_reprojected_body_and_mints_no_new_id() {
        IdempotencyRequest idem = new IdempotencyRequest("anonymous", "it-key-replay", HASH);

        StoreOutcome first = service.store(command(), idem);
        StoreOutcome second = service.store(command(), idem);

        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isTrue();
        // The replay is re-projected from the persisted purchase (no stored body), yet equals the
        // original field-for-field — BigDecimal scale and the Instant included.
        assertThat(second.response()).isEqualTo(first.response());
        assertThat(second.response().id()).isEqualTo(first.response().id());   // no second insert
        assertThat(second.response().amount().scale()).isEqualTo(2);
    }

    @Test
    void a_reused_idempotency_key_surfaces_as_a_duplicate_key_exception() {
        // A real purchase first, so the idempotency_keys.purchase_id FK is satisfiable.
        Purchase purchase = purchases.save(new Purchase(
                UUID.fromString("0190a000-0000-7000-8000-0000000000a1"),
                "fk anchor", LocalDate.parse("2026-03-15"),
                Money.usd("5.00"), Instant.now().truncatedTo(ChronoUnit.MICROS)));
        Instant expires = Instant.now().plus(24, ChronoUnit.HOURS);

        idempotency.save("anonymous", "it-key-dup", HASH, purchase.id(), 201, expires);

        assertThatThrownBy(() ->
                        idempotency.save("anonymous", "it-key-dup", HASH, purchase.id(), 201, expires))
                .isInstanceOf(DuplicateIdempotencyKeyException.class);
    }

    @Test
    void the_dual_insert_is_atomic_a_failure_rolls_back_both_rows() {
        UUID id = UUID.fromString("0190a000-0000-7000-8000-0000000000a2");
        Purchase purchase = new Purchase(
                id, "rollback probe", LocalDate.parse("2026-03-15"),
                Money.usd("7.77"), Instant.now().truncatedTo(ChronoUnit.MICROS));
        Instant expires = Instant.now().plus(24, ChronoUnit.HOURS);

        // One transaction inserts the purchase AND its idempotency key, then fails. With real
        // REQUIRED semantics both inserts must be undone — neither row may survive.
        assertThatThrownBy(() -> transactor.required(() -> {
                    purchases.save(purchase);
                    idempotency.save("anonymous", "it-key-atomic", HASH, id, 201, expires);
                    throw new IllegalStateException("force rollback");
                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(purchases.findById(id)).isEmpty();                                // purchase rolled back
        assertThat(idempotency.find("anonymous", "it-key-atomic")).isEqualTo(Optional.empty()); // key rolled back
    }
}
