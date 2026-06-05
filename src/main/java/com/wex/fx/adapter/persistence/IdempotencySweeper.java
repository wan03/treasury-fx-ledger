package com.wex.fx.adapter.persistence;

import com.wex.fx.application.port.IdempotencyStore;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Background sweep of expired {@code idempotency_keys} rows (finding #1). The TTL replay window
 * ({@code fx.idempotency.ttl}) only matters until it lapses; without this job the table grows unbounded
 * under the default {@code ondemand} provider (nothing else ever deletes from it — the V2 comment
 * "swept by the app" and the {@code DELETE} grant exist precisely for this).
 *
 * <p>Infrastructure, so it lives in the adapter (keeping the ArchUnit boundary clean) and runs on every
 * provider profile — its scheduling comes from a neutral {@code @EnableScheduling}
 * ({@code config.SchedulingConfig}), never the Treasury provider config. The cutoff is the injected
 * {@link Clock}'s {@code now} (deterministic in tests). The delete is bounded and looped so each
 * statement holds a short lock window; logs carry a count only — never a key (constitution §9).
 */
@Component
class IdempotencySweeper {

    private static final Logger log = LoggerFactory.getLogger(IdempotencySweeper.class);

    /** Cap the batches per run so a pathological backlog can't pin the scheduler thread indefinitely. */
    private static final int MAX_BATCHES_PER_RUN = 1000;

    private final IdempotencyStore store;
    private final Clock clock;
    private final int batchSize;

    IdempotencySweeper(
            IdempotencyStore store,
            Clock clock,
            @Value("${fx.idempotency.sweep-batch-size:1000}") int batchSize) {
        this.store = store;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    @Scheduled(
            fixedDelayString = "${fx.idempotency.sweep-interval:1h}",
            initialDelayString = "${fx.idempotency.sweep-interval:1h}")
    void sweep() {
        Instant now = clock.instant();
        long total = 0;
        for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
            int deleted = store.deleteExpired(now, batchSize);
            total += deleted;
            if (deleted < batchSize) {
                break; // a partial batch means the expired backlog is drained
            }
        }
        if (total > 0) {
            log.info("Idempotency sweep: deleted {} expired key(s)", total);
        }
    }
}
