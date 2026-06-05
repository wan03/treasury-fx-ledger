package com.wex.fx.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.wex.fx.application.dto.IdempotencyRequest;
import com.wex.fx.application.dto.PurchaseResponse;
import com.wex.fx.application.dto.StoreOutcome;
import com.wex.fx.application.dto.StorePurchaseCommand;
import com.wex.fx.application.error.CurrencyNotStorableException;
import com.wex.fx.application.error.DuplicateIdempotencyKeyException;
import com.wex.fx.application.error.IdempotencyConflictException;
import com.wex.fx.application.port.IdempotencyStore;
import com.wex.fx.application.support.DirectTransactor;
import com.wex.fx.application.support.FixedIdGenerator;
import com.wex.fx.application.support.InMemoryIdempotencyStore;
import com.wex.fx.application.support.InMemoryPurchaseRepository;
import com.wex.fx.domain.money.Money;
import com.wex.fx.domain.purchase.Purchase;
import com.wex.fx.domain.validation.PurchaseValidator;
import com.wex.fx.domain.validation.ValidationException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link StorePurchaseService} (T3.2) with hand-written fakes — no Spring, fixed
 * {@link Clock}. Locks the R1 use case: server id + clock timestamp, USD-only, and the three
 * idempotency paths (replay, conflict, and the concurrent-loser race resolution).
 */
class StorePurchaseServiceTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-06-03T12:00:00Z"), ZoneOffset.UTC);
    private static final UUID SERVER_ID = UUID.fromString("0190a000-0000-7000-8000-000000000001");
    private static final Duration TTL = Duration.ofHours(24);

    private final InMemoryPurchaseRepository purchases = new InMemoryPurchaseRepository();
    private final PurchaseValidator validator = PurchaseValidator.withDefaults(FIXED);

    private StorePurchaseService serviceWith(IdempotencyStore idempotency) {
        return new StorePurchaseService(
                validator, purchases, idempotency, new FixedIdGenerator(SERVER_ID),
                new DirectTransactor(), FIXED, TTL);
    }

    private static StorePurchaseCommand command(String currency) {
        return new StorePurchaseCommand("Office supplies", "12.34", "2026-03-15", currency);
    }

    @Test
    void stores_a_purchase_with_server_id_usd_and_the_clock_timestamp() {
        StoreOutcome outcome = serviceWith(new InMemoryIdempotencyStore()).store(command(null), null);

        assertThat(outcome.replayed()).isFalse();
        PurchaseResponse response = outcome.response();
        assertThat(response.id()).isEqualTo(SERVER_ID);
        assertThat(response.description()).isEqualTo("Office supplies");
        assertThat(response.transactionDate()).isEqualTo(LocalDate.parse("2026-03-15"));
        assertThat(response.amount()).isEqualByComparingTo("12.34");
        assertThat(response.amount().scale()).isEqualTo(2);          // normalized to cents
        assertThat(response.currency()).isEqualTo("USD");
        assertThat(response.createdAt()).isEqualTo(Instant.parse("2026-06-03T12:00:00Z"));
        assertThat(purchases.stored).containsKey(SERVER_ID);
    }

    @Test
    void same_key_same_payload_replays_the_stored_response_without_a_second_insert() {
        StorePurchaseService service = serviceWith(new InMemoryIdempotencyStore());
        IdempotencyRequest idem = new IdempotencyRequest("anonymous", "key-1", "hash-abc");

        StoreOutcome first = service.store(command(null), idem);
        StoreOutcome second = service.store(command(null), idem);

        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isTrue();
        // Replay is re-projected from the persisted purchase (finding #8) — byte-identical to the first.
        assertThat(second.response()).isEqualTo(first.response());
        assertThat(purchases.stored).hasSize(1);                     // not inserted twice
    }

    @Test
    void same_key_different_payload_is_a_conflict() {
        StorePurchaseService service = serviceWith(new InMemoryIdempotencyStore());
        service.store(command(null), new IdempotencyRequest("anonymous", "key-1", "hash-abc"));

        assertThatThrownBy(() -> service.store(command(null),
                        new IdempotencyRequest("anonymous", "key-1", "hash-DIFFERENT")))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void same_key_from_two_principals_does_not_cross_replay() {
        // Distinct ids per create so two real purchases are distinguishable (finding #6).
        StorePurchaseService service = new StorePurchaseService(
                validator, purchases, new InMemoryIdempotencyStore(), UUID::randomUUID,
                new DirectTransactor(), FIXED, TTL);

        StoreOutcome alice = service.store(command(null), new IdempotencyRequest("alice", "key-1", "hash-abc"));
        StoreOutcome bob = service.store(command(null), new IdempotencyRequest("bob", "key-1", "hash-abc"));

        assertThat(alice.replayed()).isFalse();
        assertThat(bob.replayed()).isFalse();                        // bob's key-1 is independent of alice's
        assertThat(alice.response().id()).isNotEqualTo(bob.response().id());
        assertThat(purchases.stored).hasSize(2);                     // two real purchases, no cross-replay
    }

    @Test
    void a_non_usd_currency_is_not_storable() {
        assertThatThrownBy(() -> serviceWith(new InMemoryIdempotencyStore()).store(command("EUR"), null))
                .isInstanceOf(CurrencyNotStorableException.class);
    }

    @Test
    void field_validation_errors_propagate() {
        StorePurchaseCommand badAmount = new StorePurchaseCommand("ok", "12.345", "2026-03-15", null);
        assertThatThrownBy(() -> serviceWith(new InMemoryIdempotencyStore()).store(badAmount, null))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void a_concurrent_duplicate_key_is_resolved_by_replaying_the_winner() {
        // The winner's purchase is already persisted (its tx committed); replay re-projects from it.
        UUID winnerId = UUID.fromString("0190a000-0000-7000-8000-0000000000ff");
        Purchase winner = persistPurchase(winnerId, "Office supplies", "12.34");
        RacingIdempotencyStore racing =
                new RacingIdempotencyStore(new IdempotencyStore.StoredResponse("hash-abc", 201, winnerId));

        StoreOutcome outcome = serviceWith(racing)
                .store(command(null), new IdempotencyRequest("anonymous", "key-1", "hash-abc"));

        assertThat(outcome.replayed()).isTrue();
        assertThat(outcome.response()).isEqualTo(PurchaseResponse.from(winner)); // re-projected winner
    }

    @Test
    void a_concurrent_duplicate_key_with_a_different_payload_is_a_conflict() {
        // Hash mismatch is detected before any re-projection, so the winner purchase need not exist.
        RacingIdempotencyStore racing = new RacingIdempotencyStore(new IdempotencyStore.StoredResponse(
                "hash-WINNER", 201, UUID.fromString("0190a000-0000-7000-8000-0000000000ff")));

        assertThatThrownBy(() -> serviceWith(racing)
                        .store(command(null), new IdempotencyRequest("anonymous", "key-1", "hash-loser")))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    private Purchase persistPurchase(UUID id, String description, String amount) {
        Purchase p = new Purchase(id, description, LocalDate.parse("2026-03-15"),
                Money.of(new BigDecimal(amount), "USD"), Instant.parse("2026-06-03T12:00:00Z"));
        return purchases.save(p);
    }

    /**
     * Models the lost race: the key is invisible to {@code find} until a {@code save} collides with the
     * already-committed winner, after which the winner becomes readable (as a fresh transaction would
     * see it).
     */
    private static final class RacingIdempotencyStore implements IdempotencyStore {
        private final StoredResponse winner;
        private boolean committed = false;

        RacingIdempotencyStore(StoredResponse winner) {
            this.winner = winner;
        }

        @Override
        public Optional<StoredResponse> find(String principal, String key) {
            return committed ? Optional.of(winner) : Optional.empty();
        }

        @Override
        public void save(String principal, String key, String requestHash, UUID purchaseId,
                int responseStatus, Instant expiresAt) {
            committed = true;
            throw new DuplicateIdempotencyKeyException(key, null);
        }

        @Override
        public int deleteExpired(Instant now, int batchLimit) {
            return 0; // not exercised by the race path
        }
    }
}
