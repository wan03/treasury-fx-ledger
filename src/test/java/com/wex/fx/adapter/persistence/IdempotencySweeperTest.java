package com.wex.fx.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.wex.fx.application.port.IdempotencyStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Fast unit test for {@link IdempotencySweeper} (finding #1): the scheduled method delegates to the store
 * with the injected {@link Clock}'s {@code now} and the configured batch size, and loops (bounded deletes)
 * until a partial batch signals the backlog is drained. No Spring context, no database.
 */
class IdempotencySweeperTest {

    private static final Instant NOW = Instant.parse("2026-06-05T12:00:00Z");
    private static final Clock FIXED = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void sweep_delegates_with_clock_now_and_loops_until_a_partial_batch() {
        // Programmed to drain over two batches: a full 100, then a partial 40 (< limit) → stop.
        RecordingStore store = new RecordingStore(List.of(100, 40));
        IdempotencySweeper sweeper = new IdempotencySweeper(store, FIXED, /* batchSize */ 100);

        sweeper.sweep();

        // Two bounded deletes, both at the fixed clock's instant and the configured batch size.
        assertThat(store.calls).containsExactly(new Call(NOW, 100), new Call(NOW, 100));
    }

    @Test
    void sweep_stops_after_a_single_partial_batch() {
        RecordingStore store = new RecordingStore(List.of(7)); // fewer than the limit on the first pass
        IdempotencySweeper sweeper = new IdempotencySweeper(store, FIXED, /* batchSize */ 100);

        sweeper.sweep();

        assertThat(store.calls).containsExactly(new Call(NOW, 100));
    }

    private record Call(Instant now, int limit) {}

    /** Records each {@code deleteExpired} invocation and replays a programmed sequence of delete counts. */
    private static final class RecordingStore implements IdempotencyStore {
        private final List<Integer> counts;
        private final List<Call> calls = new ArrayList<>();
        private int index;

        RecordingStore(List<Integer> counts) {
            this.counts = counts;
        }

        @Override
        public int deleteExpired(Instant now, int batchLimit) {
            calls.add(new Call(now, batchLimit));
            return index < counts.size() ? counts.get(index++) : 0;
        }

        @Override
        public Optional<StoredResponse> find(String principal, String key) {
            return Optional.empty();
        }

        @Override
        public void save(String principal, String key, String requestHash, UUID purchaseId,
                int responseStatus, Instant expiresAt) {
            throw new UnsupportedOperationException();
        }
    }
}
