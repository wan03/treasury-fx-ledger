package com.wex.fx.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.wex.fx.application.dto.ConversionResponse;
import com.wex.fx.application.error.MalformedCurrencyException;
import com.wex.fx.application.error.NoRateAvailableException;
import com.wex.fx.application.error.PurchaseNotFoundException;
import com.wex.fx.application.error.UnsupportedCurrencyException;
import com.wex.fx.application.port.ExchangeRateProvider;
import com.wex.fx.application.support.InMemoryPurchaseRepository;
import com.wex.fx.domain.currency.CurrencyMap;
import com.wex.fx.domain.money.Money;
import com.wex.fx.domain.purchase.Purchase;
import com.wex.fx.domain.rate.ExchangeRate;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ConvertPurchaseService} (T3.3). Exercises the four sealed currency-resolution
 * branches (supported / identity / unsupported / malformed), the no-rate outcome, and that the USD
 * identity never touches the provider — all with a stub rate provider.
 */
class ConvertPurchaseServiceTest {

    private static final UUID PURCHASE_ID = UUID.fromString("0190a000-0000-7000-8000-000000000001");

    private final InMemoryPurchaseRepository purchases = new InMemoryPurchaseRepository();
    private final CurrencyMap currencyMap = CurrencyMap.loadDefault();

    ConvertPurchaseServiceTest() {
        purchases.save(new Purchase(
                PURCHASE_ID, "Office supplies", LocalDate.parse("2026-03-15"),
                Money.usd("12.34"), Instant.parse("2026-06-03T12:00:00Z")));
    }

    private ConvertPurchaseService serviceWith(ExchangeRateProvider provider) {
        return new ConvertPurchaseService(purchases, currencyMap, provider);
    }

    @Test
    void a_supported_currency_converts_at_the_selected_rate() {
        ExchangeRate rate = new ExchangeRate(
                "Euro Zone-Euro", LocalDate.parse("2025-03-31"), LocalDate.parse("2025-03-31"),
                new BigDecimal("0.924"));
        StubProvider provider = new StubProvider(Optional.of(rate));

        ConversionResponse response = serviceWith(provider).convert(PURCHASE_ID, "EUR");

        assertThat(response.purchaseId()).isEqualTo(PURCHASE_ID);
        assertThat(response.originalAmount()).isEqualByComparingTo("12.34");
        assertThat(response.originalCurrency()).isEqualTo("USD");
        assertThat(response.targetCurrency()).isEqualTo("EUR");
        assertThat(response.exchangeRate()).isEqualByComparingTo("0.924");
        assertThat(response.rateEffectiveDate()).isEqualTo(LocalDate.parse("2025-03-31"));
        assertThat(response.convertedAmount()).isEqualByComparingTo("11.40");   // 12.34*0.924=11.40216 -> 11.40
        assertThat(response.convertedAmount().scale()).isEqualTo(2);
        assertThat(response.rateSource()).isEqualTo(ConversionResponse.TREASURY_SOURCE);
        // the service resolved ISO->descriptor and queried for the purchase date:
        assertThat(provider.calls).isEqualTo(1);
        assertThat(provider.lastDescriptor).isEqualTo("Euro Zone-Euro");
        assertThat(provider.lastDate).isEqualTo(LocalDate.parse("2026-03-15"));
    }

    @Test
    void usd_target_is_an_in_app_identity_with_no_provider_call() {
        StubProvider provider = new StubProvider(Optional.empty());

        ConversionResponse response = serviceWith(provider).convert(PURCHASE_ID, "USD");

        assertThat(response.targetCurrency()).isEqualTo("USD");
        assertThat(response.exchangeRate()).isEqualByComparingTo("1.00");
        assertThat(response.exchangeRate().scale()).isEqualTo(2);
        assertThat(response.convertedAmount()).isEqualByComparingTo("12.34");   // unchanged
        assertThat(response.rateEffectiveDate()).isNull();
        assertThat(response.rateSource()).isEqualTo(ConversionResponse.IDENTITY_SOURCE);
        assertThat(provider.calls).isZero();                                    // no upstream call (D-07)
    }

    @Test
    void an_unknown_purchase_is_not_found() {
        assertThatThrownBy(() -> serviceWith(new StubProvider(Optional.empty()))
                        .convert(UUID.randomUUID(), "EUR"))
                .isInstanceOf(PurchaseNotFoundException.class);
    }

    @Test
    void an_iso_valid_but_uncurated_currency_is_unsupported() {
        assertThatThrownBy(() -> serviceWith(new StubProvider(Optional.empty()))
                        .convert(PURCHASE_ID, "ZZZ"))
                .isInstanceOf(UnsupportedCurrencyException.class);
    }

    @Test
    void a_malformed_currency_token_is_rejected() {
        assertThatThrownBy(() -> serviceWith(new StubProvider(Optional.empty()))
                        .convert(PURCHASE_ID, "eur"))   // lowercase
                .isInstanceOf(MalformedCurrencyException.class);
    }

    @Test
    void no_rate_in_window_yields_no_rate_available() {
        assertThatThrownBy(() -> serviceWith(new StubProvider(Optional.empty()))
                        .convert(PURCHASE_ID, "EUR"))
                .isInstanceOf(NoRateAvailableException.class);
    }

    /** Records its single call so identity-short-circuit and descriptor resolution can be asserted. */
    private static final class StubProvider implements ExchangeRateProvider {
        private final Optional<ExchangeRate> result;
        int calls = 0;
        String lastDescriptor;
        LocalDate lastDate;

        StubProvider(Optional<ExchangeRate> result) {
            this.result = result;
        }

        @Override
        public Optional<ExchangeRate> findRate(String countryCurrencyDesc, LocalDate purchaseDate) {
            calls++;
            lastDescriptor = countryCurrencyDesc;
            lastDate = purchaseDate;
            return result;
        }
    }
}
