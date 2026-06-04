package com.wex.fx.adapter.treasury;

import com.wex.fx.domain.rate.ExchangeRate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.springframework.web.client.RestClient;

/**
 * The shared HTTP fetcher behind providers A0 and A. Expresses the <em>entire</em> selection rule as one
 * Treasury request (F7 push-down) so only ~1 row crosses the wire: filter by descriptor and the
 * {@code effective_date} window, sort {@code -effective_date}, take the first page of one.
 *
 * <pre>
 * GET /v1/accounting/od/rates_of_exchange
 *   ?fields=country_currency_desc,exchange_rate,effective_date,record_date
 *   &amp;filter=country_currency_desc:eq:&lt;desc&gt;,effective_date:lte:&lt;lte&gt;,effective_date:gte:&lt;gte&gt;
 *   &amp;sort=-effective_date&amp;page[size]=1&amp;format=json
 * </pre>
 *
 * <p>It does no resilience and no caching — those are decorators around it. It maps the tolerant
 * {@link TreasuryRatesPayload} into domain {@link ExchangeRate}s (rate string→{@code BigDecimal}, no
 * assumed scale), raising {@link TreasuryContractException} on a malformed body. 4xx/5xx/timeout
 * propagate as the {@link RestClient}'s native exceptions for the resilience layer to classify.
 */
final class TreasuryRateFetcher implements RateFetcher {

    static final String PATH = "/v1/accounting/od/rates_of_exchange";
    private static final String FIELDS =
            "country_currency_desc,exchange_rate,effective_date,record_date";
    // Window fetch (ingest/sync) pulls every row in range. Treasury's per-currency history is quarterly
    // and small, so one generous page covers it; full pagination is a documented future extension.
    private static final int WINDOW_PAGE_SIZE = 1000;

    private final RestClient client;

    TreasuryRateFetcher(RestClient client) {
        this.client = client;
    }

    @Override
    public List<ExchangeRate> fetch(
            String descriptor, LocalDate effectiveOnOrBefore, LocalDate effectiveOnOrAfter) {
        // F7 single-row push-down: the server sorts and returns just the selected row (~1 over the wire).
        return query(filter(descriptor, effectiveOnOrBefore, effectiveOnOrAfter), 1);
    }

    @Override
    public List<ExchangeRate> fetchWindow(String descriptor, LocalDate from, LocalDate to) {
        // Every row in [from, to] (amendments included) so the local store can backfill/reconcile.
        return query(filter(descriptor, to, from), WINDOW_PAGE_SIZE);
    }

    private static String filter(String descriptor, LocalDate lte, LocalDate gte) {
        return "country_currency_desc:eq:" + descriptor
                + ",effective_date:lte:" + lte
                + ",effective_date:gte:" + gte;
    }

    private List<ExchangeRate> query(String filter, int pageSize) {
        TreasuryRatesPayload payload = client.get()
                .uri(uri -> uri.path(PATH)
                        .queryParam("fields", FIELDS)
                        .queryParam("filter", filter)
                        .queryParam("sort", "-effective_date")
                        .queryParam("page[size]", Integer.toString(pageSize))
                        .queryParam("format", "json")
                        .build())
                .retrieve()
                .body(TreasuryRatesPayload.class);

        if (payload == null || payload.data() == null || payload.data().isEmpty()) {
            return List.of();   // genuine "no rate in window" — a normal 422, not a failure
        }
        return payload.data().stream().map(TreasuryRateFetcher::toDomain).toList();
    }

    private static ExchangeRate toDomain(TreasuryRatesPayload.Row row) {
        return new ExchangeRate(
                require(row.countryCurrencyDesc(), "country_currency_desc"),
                parseDate(row.effectiveDate(), "effective_date"),
                parseDate(row.recordDate(), "record_date"),
                parseRate(row.exchangeRate()));
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new TreasuryContractException("Treasury row missing critical field: " + field, null);
        }
        return value;
    }

    private static BigDecimal parseRate(String raw) {
        String rate = require(raw, "exchange_rate");
        try {
            return new BigDecimal(rate);
        } catch (NumberFormatException e) {
            throw new TreasuryContractException("Treasury exchange_rate is not numeric", e);
        }
    }

    private static LocalDate parseDate(String raw, String field) {
        try {
            return LocalDate.parse(require(raw, field));
        } catch (DateTimeParseException e) {
            throw new TreasuryContractException("Treasury " + field + " is not an ISO date", e);
        }
    }
}
