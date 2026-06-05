package com.wex.fx.adapter.treasury;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Tolerant binding of the Treasury <em>Rates of Exchange</em> JSON. We bind the four fields the selection
 * rule needs plus {@code meta.total-pages} (so the window fetch can page; finding #5), and
 * <strong>ignore everything else</strong> ({@code links} and any fields Treasury adds later) so a benign
 * schema addition never breaks us (constitution §7, schema tolerance). The rate stays a <em>string</em>
 * here and is parsed to {@code BigDecimal} downstream with no assumed scale (F2). A missing
 * <em>critical</em> field surfaces as a clear mapped error, never an NPE.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record TreasuryRatesPayload(@JsonProperty("data") List<Row> data, @JsonProperty("meta") Meta meta) {

    /**
     * Total pages for the current query, or {@code 1} when {@code meta}/{@code total-pages} is absent (an
     * un-paginated or single-page response). Drives the window pagination loop.
     */
    int totalPages() {
        return meta != null && meta.totalPages() != null && meta.totalPages() > 0 ? meta.totalPages() : 1;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Row(
            @JsonProperty("country_currency_desc") String countryCurrencyDesc,
            @JsonProperty("exchange_rate") String exchangeRate,
            @JsonProperty("effective_date") String effectiveDate,
            @JsonProperty("record_date") String recordDate) {}

    /** Pagination metadata (Treasury {@code meta}). Only {@code total-pages} is load-bearing here. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Meta(
            @JsonProperty("total-pages") Integer totalPages,
            @JsonProperty("total-count") Integer totalCount) {}
}
