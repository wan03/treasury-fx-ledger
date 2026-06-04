package com.wex.fx.adapter.persistence;

import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.NoArgGenerator;
import com.wex.fx.application.port.IdGenerator;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * UUIDv7 adapter for {@link IdGenerator} (D-08). Time-ordered (Unix-epoch-millis prefix) so primary
 * keys cluster by insertion time — good B-tree locality and no random-UUID page churn. Thread-safe:
 * the FasterXML generator guards its monotonic counter and shared {@code SecureRandom}.
 */
@Component
class Uuid7IdGenerator implements IdGenerator {

    private final NoArgGenerator generator = Generators.timeBasedEpochGenerator();

    @Override
    public UUID newId() {
        return generator.generate();
    }
}
