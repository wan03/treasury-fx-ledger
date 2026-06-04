package com.wex.fx.adapter.persistence;

import com.wex.fx.application.port.PurchaseRepository;
import com.wex.fx.domain.money.Money;
import com.wex.fx.domain.purchase.Purchase;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jdbc.core.JdbcAggregateTemplate;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JDBC adapter for {@link PurchaseRepository}. Uses {@link JdbcAggregateTemplate#insert}
 * explicitly (never {@code save}) because the id is application-assigned: {@code save} would see a
 * non-null {@code @Id}, assume an existing row, and emit an {@code UPDATE} that matches nothing. An
 * explicit insert also matches the append-only contract — there is no update path by construction.
 *
 * <p>The {@code Money} &harr; {@code NUMERIC(19,2)} translation lives here (split amount + currency
 * columns); reading back through {@link Money#of} re-asserts scale 2, so {@code 12.30} never collapses.
 */
@Repository
class JdbcPurchaseRepository implements PurchaseRepository {

    private final JdbcAggregateTemplate aggregateTemplate;

    JdbcPurchaseRepository(JdbcAggregateTemplate aggregateTemplate) {
        this.aggregateTemplate = aggregateTemplate;
    }

    @Override
    public Purchase save(Purchase purchase) {
        aggregateTemplate.insert(toRow(purchase));
        return purchase;
    }

    @Override
    public Optional<Purchase> findById(UUID id) {
        return Optional.ofNullable(aggregateTemplate.findById(id, PurchaseRow.class))
                .map(JdbcPurchaseRepository::toDomain);
    }

    private static PurchaseRow toRow(Purchase p) {
        return new PurchaseRow(
                p.id(),
                p.description(),
                p.transactionDate(),
                p.amount().amount(),
                p.amount().currencyCode(),
                p.createdAt().atOffset(ZoneOffset.UTC));
    }

    private static Purchase toDomain(PurchaseRow r) {
        return new Purchase(
                r.id(),
                r.description(),
                r.transactionDate(),
                Money.of(r.amount(), r.currency()),
                r.createdAt().toInstant());
    }
}
