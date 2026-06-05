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
        IdempotencyRequest idem = new IdempotencyRequest("key-1", "hash-abc");

        StoreOutcome first = service.store(command(null), idem);
        StoreOutcome second = service.store(command(null), idem);

        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isTrue();
        assertThat(second.response()).isEqualTo(first.response());
        assertThat(purchases.stored).hasSize(1);                     // not inserted twice
    }

    @Test
    void same_key_different_payload_is_a_conflict() {
        StorePurchaseService service = serviceWith(new InMemoryIdempotencyStore());
        service.store(command(null), new IdempotencyRequest("key-1", "hash-abc"));

        assertThatThrownBy(() ->
                        service.store(command(null), new IdempotencyRequest("key-1", "hash-DIFFERENT")))
                .isInstanceOf(IdempotencyConflictException.class);
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
        PurchaseResponse winnerBody = new PurchaseResponse(
                UUID.fromString("0190a000-0000-7000-8000-0000000000ff"),
                "Office supplies", LocalDate.parse("2026-03-15"),
                new BigDecimal("12.34"), "USD", Instant.parse("2026-06-03T12:00:00Z"));
        RacingIdempotencyStore racing =
                new RacingIdempotencyStore(new IdempotencyStore.StoredResponse("hash-abc", 201, winnerBody));

        StoreOutcome outcome =
                serviceWith(racing).store(command(null), new IdempotencyRequest("key-1", "hash-abc"));

        assertThat(outcome.replayed()).isTrue();
        assertThat(outcome.response()).isEqualTo(winnerBody);        // the committed winner's body
    }

    @Test
    void a_concurrent_duplicate_key_with_a_different_payload_is_a_conflict() {
        PurchaseResponse winnerBody = new PurchaseResponse(
                UUID.fromString("0190a000-0000-7000-8000-0000000000ff"),
                "Other", LocalDate.parse("2026-03-15"),
                new BigDecimal("99.99"), "USD", Instant.parse("2026-06-03T12:00:00Z"));
        RacingIdempotencyStore racing =
                new RacingIdempotencyStore(new IdempotencyStore.StoredResponse("hash-WINNER", 201, winnerBody));

        assertThatThrownBy(() ->
                        serviceWith(racing).store(command(null), new IdempotencyRequest("key-1", "hash-loser")))
                .isInstanceOf(IdempotencyConflictException.class);
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
        public Optional<StoredResponse> find(String key) {
            return committed ? Optional.of(winner) : Optional.empty();
        }

        @Override
        public void save(String key, String requestHash, UUID purchaseId, int responseStatus,
                PurchaseResponse responseBody, Instant expiresAt) {
            committed = true;
            throw new DuplicateIdempotencyKeyException(key, null);
        }

        @Override
        public int deleteExpired(Instant now, int batchLimit) {
            return 0; // not exercised by the race path
        }
    }
}
