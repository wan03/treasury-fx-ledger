package com.wex.fx.application.port;

import java.util.UUID;

/**
 * Outbound port for server-generated identifiers (D-08). Behind it, a UUIDv7 generator gives
 * time-ordered, index-local keys. Kept a port so tests can inject a deterministic generator and the
 * application stays free of the generator library.
 */
public interface IdGenerator {

    UUID newId();
}
