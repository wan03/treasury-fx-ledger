package com.wex.fx.application.port;

import java.util.function.Supplier;

/**
 * Outbound port for transaction control, so the application owns the transactional boundary (plan.md)
 * without importing Spring. The JDBC adapter implements it over {@code PlatformTransactionManager}.
 *
 * <p>Two propagations, both load-bearing for the idempotency race (data-model.md): {@link #required}
 * wraps the create as one atomic unit (the purchase row and the idempotency row commit together or
 * not at all); {@link #requiresNew} runs the loser's replay-read in a <em>fresh</em> transaction after
 * the original was rolled back by the unique-key violation.
 */
public interface Transactor {

    /** Runs {@code work} in a transaction, joining an existing one if present. */
    <T> T required(Supplier<T> work);

    /** Runs {@code work} in a brand-new, independent transaction. */
    <T> T requiresNew(Supplier<T> work);
}
