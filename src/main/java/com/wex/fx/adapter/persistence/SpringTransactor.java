package com.wex.fx.adapter.persistence;

import com.wex.fx.application.port.Transactor;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Spring-backed {@link Transactor}: keeps the {@code @Transactional} machinery in the adapter ring so
 * the application layer stays framework-free (ArchUnit-enforced). Holds two pre-built templates —
 * {@code REQUIRED} for the atomic create and {@code REQUIRES_NEW} for the idempotency loser's
 * replay-read after its own transaction has been rolled back.
 */
@Component
class SpringTransactor implements Transactor {

    private final TransactionTemplate required;
    private final TransactionTemplate requiresNew;

    SpringTransactor(PlatformTransactionManager transactionManager) {
        this.required = new TransactionTemplate(transactionManager);
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public <T> T required(Supplier<T> work) {
        return required.execute(status -> work.get());
    }

    @Override
    public <T> T requiresNew(Supplier<T> work) {
        return requiresNew.execute(status -> work.get());
    }
}
