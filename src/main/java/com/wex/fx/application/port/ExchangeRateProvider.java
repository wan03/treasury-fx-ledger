package com.wex.fx.application.port;

import com.wex.fx.domain.rate.ExchangeRate;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Outbound port for Treasury rate lookup (D-03 — the headline seam). One method, four
 * config-selectable adapters behind it (A0 passthrough, A on-demand+cache, B ingest, C hybrid). The
 * adapter owns the selection rule (effective_date &le; purchaseDate, within the 6-month window): A0/A
 * push it to Treasury via the server-side filter (F7); B/C apply {@code RateSelector} over the local
 * table. Either way the port returns the <em>already-selected</em> single rate.
 *
 * <p>An empty result is a <strong>normal business outcome</strong> (no rate in window), which the
 * application maps to {@code 422 NO_RATE_AVAILABLE} — never an exception at this seam.
 *
 * @param countryCurrencyDesc the resolved Treasury {@code country_currency_desc} (e.g. "Euro Zone-Euro")
 * @param purchaseDate        the date the rate must be active on/before
 */
public interface ExchangeRateProvider {

    Optional<ExchangeRate> findRate(String countryCurrencyDesc, LocalDate purchaseDate);
}
