package com.wex.fx.application;

import com.wex.fx.application.dto.IdempotencyRequest;
import com.wex.fx.application.dto.PurchaseResponse;
import com.wex.fx.application.dto.StoreOutcome;
import com.wex.fx.application.dto.StorePurchaseCommand;
import com.wex.fx.application.error.CurrencyNotStorableException;
import com.wex.fx.application.error.DuplicateIdempotencyKeyException;
import com.wex.fx.application.error.IdempotencyConflictException;
import com.wex.fx.application.port.IdGenerator;
import com.wex.fx.application.port.IdempotencyStore;
import com.wex.fx.application.port.IdempotencyStore.StoredResponse;
import com.wex.fx.application.port.PurchaseRepository;
import com.wex.fx.application.port.Transactor;
import com.wex.fx.domain.money.Money;
import com.wex.fx.domain.purchase.Purchase;
import com.wex.fx.domain.validation.PurchaseValidator;
import com.wex.fx.domain.validation.ValidatedPurchase;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

/**
 * Use case R1 — store a purchase. Validates at the edge, assigns a server UUIDv7, and persists the
 * USD principal append-only. When the client supplies an {@code Idempotency-Key}, the create and its
 * replay record commit in <strong>one transaction</strong>, and concurrent duplicate retries are made
 * safe by the {@code idempotency_keys} primary key (data-model.md): the loser's unique-violation is
 * caught and turned into a replay of the winner's stored response.
 *
 * <p>Pure application service — no Spring annotations. The transactional boundary is expressed through
 * the {@link Transactor} port and the bean is assembled in {@code config.ApplicationWiring}.
 */
public class StorePurchaseService {

    /** The status replayed for an idempotent create (the original {@code 201}). */
    private static final int CREATED_STATUS = 201;

    private final PurchaseValidator validator;
    private final PurchaseRepository purchases;
    private final IdempotencyStore idempotency;
    private final IdGenerator ids;
    private final Transactor transactor;
    private final Clock clock;
    private final Duration idempotencyTtl;

    public StorePurchaseService(
            PurchaseValidator validator,
            PurchaseRepository purchases,
            IdempotencyStore idempotency,
            IdGenerator ids,
            Transactor transactor,
            Clock clock,
            Duration idempotencyTtl) {
        this.validator = validator;
        this.purchases = purchases;
        this.idempotency = idempotency;
        this.ids = ids;
        this.transactor = transactor;
        this.clock = clock;
        this.idempotencyTtl = idempotencyTtl;
    }

    /**
     * Validates and stores a purchase. With no idempotency key it is a plain create; with one it is
     * exactly-once.
     *
     * @throws com.wex.fx.domain.validation.ValidationException on any field error (&rarr; 400)
     * @throws CurrencyNotStorableException                     on a non-USD currency (&rarr; 422)
     * @throws IdempotencyConflictException                     on key reuse with a different payload (&rarr; 409)
     */
    public StoreOutcome store(StorePurchaseCommand command, IdempotencyRequest idem) {
        ValidatedPurchase validated =
                validator.validate(command.description(), command.amount(), command.transactionDate());
        requireStorableCurrency(command.currencyOrDefault());

        if (idem == null) {
            return transactor.required(() -> StoreOutcome.created(persistNew(validated, null)));
        }
        return storeIdempotent(validated, idem);
    }

    private StoreOutcome storeIdempotent(ValidatedPurchase validated, IdempotencyRequest idem) {
        try {
            return transactor.required(() -> {
                Optional<StoredResponse> existing = idempotency.find(idem.key());
                return existing
                        .map(stored -> StoreOutcome.replayed(verifyAndReplay(stored, idem)))
                        .orElseGet(() -> StoreOutcome.created(persistNew(validated, idem)));
            });
        } catch (DuplicateIdempotencyKeyException race) {
            // A concurrent winner committed our key between our find and our insert; our transaction
            // rolled back, so no duplicate purchase was created. Read the winner in a fresh transaction
            // and replay it (or 409 if the winner's payload differs).
            StoredResponse winner = transactor.requiresNew(() -> idempotency.find(idem.key()))
                    .orElseThrow(() -> race);
            return StoreOutcome.replayed(verifyAndReplay(winner, idem));
        }
    }

    private PurchaseResponse persistNew(ValidatedPurchase validated, IdempotencyRequest idem) {
        UUID id = ids.newId();
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        Money principal = Money.of(validated.amount(), StorePurchaseCommand.DEFAULT_CURRENCY);
        Purchase purchase = new Purchase(id, validated.description(), validated.transactionDate(), principal, now);

        purchases.save(purchase);
        PurchaseResponse response = PurchaseResponse.from(purchase);

        if (idem != null) {
            idempotency.save(
                    idem.key(), idem.requestHash(), id, CREATED_STATUS, response, now.plus(idempotencyTtl));
        }
        return response;
    }

    private PurchaseResponse verifyAndReplay(StoredResponse stored, IdempotencyRequest idem) {
        if (!stored.requestHash().equals(idem.requestHash())) {
            throw new IdempotencyConflictException(idem.key());
        }
        return stored.responseBody();
    }

    private void requireStorableCurrency(String currency) {
        if (!StorePurchaseCommand.DEFAULT_CURRENCY.equals(currency)) {
            throw new CurrencyNotStorableException(currency);
        }
    }
}
