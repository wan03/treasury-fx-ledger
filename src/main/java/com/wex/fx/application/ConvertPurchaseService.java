package com.wex.fx.application;

import com.wex.fx.application.dto.ConversionResponse;
import com.wex.fx.application.error.MalformedCurrencyException;
import com.wex.fx.application.error.NoRateAvailableException;
import com.wex.fx.application.error.PurchaseNotFoundException;
import com.wex.fx.application.error.UnsupportedCurrencyException;
import com.wex.fx.application.port.ExchangeRateProvider;
import com.wex.fx.application.port.PurchaseRepository;
import com.wex.fx.domain.currency.CurrencyMap;
import com.wex.fx.domain.currency.CurrencyResolution;
import com.wex.fx.domain.money.Money;
import com.wex.fx.domain.purchase.Purchase;
import com.wex.fx.domain.rate.ExchangeRate;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Use case R2 — convert a stored USD purchase into a target currency at the rate active on/before its
 * transaction date (rate-selection.md, currency-mapping.md). Pure application service; the
 * {@code ExchangeRateProvider} port hides which adapter (A0/A/B/C) actually fetches the rate.
 *
 * <p>The ISO-4217 input is resolved through the curated map and dispatched over a sealed result, so the
 * four outcomes are exhaustive and explicit: identity (USD, no upstream call), supported (fetch +
 * convert), unsupported (422), malformed (400).
 */
public class ConvertPurchaseService {

    /** USD identity rate, rendered at scale 2 per the contract ({@code "1.00"}). */
    private static final BigDecimal IDENTITY_RATE = new BigDecimal("1.00");

    private final PurchaseRepository purchases;
    private final CurrencyMap currencyMap;
    private final ExchangeRateProvider rates;

    public ConvertPurchaseService(
            PurchaseRepository purchases, CurrencyMap currencyMap, ExchangeRateProvider rates) {
        this.purchases = purchases;
        this.currencyMap = currencyMap;
        this.rates = rates;
    }

    /**
     * Converts purchase {@code purchaseId} into {@code targetCurrencyCode}.
     *
     * @throws PurchaseNotFoundException     unknown id (&rarr; 404)
     * @throws MalformedCurrencyException    target not {@code ^[A-Z]{3}$} (&rarr; 400)
     * @throws UnsupportedCurrencyException  ISO-valid but uncurated (&rarr; 422)
     * @throws NoRateAvailableException      no rate within the 6-month window (&rarr; 422)
     */
    public ConversionResponse convert(UUID purchaseId, String targetCurrencyCode) {
        Purchase purchase = purchases.findById(purchaseId)
                .orElseThrow(() -> new PurchaseNotFoundException(purchaseId));

        return switch (currencyMap.resolve(targetCurrencyCode)) {
            case CurrencyResolution.Malformed ignored -> throw new MalformedCurrencyException(targetCurrencyCode);
            case CurrencyResolution.Unsupported ignored -> throw new UnsupportedCurrencyException(targetCurrencyCode);
            case CurrencyResolution.Identity ignored -> identity(purchase);
            case CurrencyResolution.Supported supported -> convertVia(purchase, targetCurrencyCode, supported.descriptor());
        };
    }

    /** USD target: rate 1.00, converted == original, no upstream call (D-07). */
    private ConversionResponse identity(Purchase purchase) {
        Money amount = purchase.amount();
        return new ConversionResponse(
                purchase.id(),
                purchase.description(),
                purchase.transactionDate(),
                amount.amount(),
                amount.currencyCode(),
                amount.currencyCode(), // target == original == USD
                IDENTITY_RATE,
                null,
                amount.amount(),
                ConversionResponse.IDENTITY_SOURCE);
    }

    private ConversionResponse convertVia(Purchase purchase, String targetCurrency, String descriptor) {
        ExchangeRate rate = rates.findRate(descriptor, purchase.transactionDate())
                .orElseThrow(() -> new NoRateAvailableException(
                        purchase.id(), targetCurrency, purchase.transactionDate()));

        Money converted = purchase.amount().convertedAt(rate.exchangeRate(), targetCurrency);
        return new ConversionResponse(
                purchase.id(),
                purchase.description(),
                purchase.transactionDate(),
                purchase.amount().amount(),
                purchase.amount().currencyCode(),
                targetCurrency,
                rate.exchangeRate(),
                rate.effectiveDate(),
                converted.amount(),
                ConversionResponse.TREASURY_SOURCE);
    }
}
