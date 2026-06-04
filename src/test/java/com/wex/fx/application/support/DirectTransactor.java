package com.wex.fx.application.support;

import com.wex.fx.application.port.Transactor;
import java.util.function.Supplier;

/**
 * {@link Transactor} double that runs work inline with no real transaction. It deliberately does
 * <em>not</em> model rollback — atomicity is proven against a real Postgres in the integration slice;
 * the unit tests use this only to drive the service's control flow (including the race-resolution path).
 */
public final class DirectTransactor implements Transactor {

    @Override
    public <T> T required(Supplier<T> work) {
        return work.get();
    }

    @Override
    public <T> T requiresNew(Supplier<T> work) {
        return work.get();
    }
}
