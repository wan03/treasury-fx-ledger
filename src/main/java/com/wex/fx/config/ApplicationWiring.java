package com.wex.fx.config;

import com.wex.fx.adapter.treasury.UnavailableExchangeRateProvider;
import com.wex.fx.application.ConvertPurchaseService;
import com.wex.fx.application.StorePurchaseService;
import com.wex.fx.application.port.ExchangeRateProvider;
import com.wex.fx.application.port.IdGenerator;
import com.wex.fx.application.port.IdempotencyStore;
import com.wex.fx.application.port.PurchaseRepository;
import com.wex.fx.application.port.Transactor;
import com.wex.fx.domain.currency.CurrencyMap;
import com.wex.fx.domain.validation.PurchaseValidator;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;

/**
 * Composition root for the framework-free domain + application layers. Those classes carry no Spring
 * annotations (so ArchUnit can prove they're framework-free); this configuration is where they are
 * assembled from the adapter beans and the injected {@link Clock}.
 */
@Configuration(proxyBeanMethods = false)
class ApplicationWiring {

    /** The single curated ISO&rarr;Treasury descriptor map (currency-mapping.md), loaded once. */
    @Bean
    CurrencyMap currencyMap() {
        return CurrencyMap.loadDefault();
    }

    /** Edge validation policy, sharing the application {@link Clock} so future-date checks stay deterministic. */
    @Bean
    PurchaseValidator purchaseValidator(Clock clock) {
        return PurchaseValidator.withDefaults(clock);
    }

    /**
     * Phase-3 fallback rate provider (D-03). {@code @ConditionalOnMissingBean} means the first real
     * Treasury adapter in Phase 4 transparently replaces it.
     */
    @Bean
    @ConditionalOnMissingBean(ExchangeRateProvider.class)
    ExchangeRateProvider exchangeRateProvider() {
        return new UnavailableExchangeRateProvider();
    }

    @Bean
    StorePurchaseService storePurchaseService(
            PurchaseValidator validator,
            PurchaseRepository purchases,
            IdempotencyStore idempotency,
            IdGenerator ids,
            Transactor transactor,
            Clock clock,
            @Value("${fx.idempotency.ttl:24h}") Duration idempotencyTtl) {
        return new StorePurchaseService(
                validator, purchases, idempotency, ids, transactor, clock, idempotencyTtl);
    }

    @Bean
    ConvertPurchaseService convertPurchaseService(
            PurchaseRepository purchases, CurrencyMap currencyMap, ExchangeRateProvider rates) {
        return new ConvertPurchaseService(purchases, currencyMap, rates);
    }
}
