package com.wex.fx.adapter.treasury;

import com.wex.fx.application.port.ExchangeRateProvider;
import com.wex.fx.domain.rate.ExchangeRate;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Phase-3 placeholder for the {@link ExchangeRateProvider} seam (D-03). Returns no rate, so every
 * conversion of a non-USD currency surfaces as {@code 422 NO_RATE_AVAILABLE} until the real Treasury
 * adapters (A0 passthrough / A on-demand+cache / B ingest / C hybrid) land in Phase 4.
 *
 * <p>Registered via {@code ApplicationWiring} with {@code @ConditionalOnMissingBean}, so the first real
 * provider bean automatically supersedes it — no wiring change needed when Phase 4 arrives.
 */
public class UnavailableExchangeRateProvider implements ExchangeRateProvider {

    @Override
    public Optional<ExchangeRate> findRate(String countryCurrencyDesc, LocalDate purchaseDate) {
        return Optional.empty();
    }
}
