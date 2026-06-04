package com.wex.fx.application.support;

import com.wex.fx.application.port.IdGenerator;
import java.util.UUID;

/** Deterministic {@link IdGenerator} double — always returns the supplied id, so tests are stable. */
public final class FixedIdGenerator implements IdGenerator {

    private final UUID id;

    public FixedIdGenerator(UUID id) {
        this.id = id;
    }

    @Override
    public UUID newId() {
        return id;
    }
}
