package com.wex.fx.adapter.treasury;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Tolerant binding of the Treasury <em>Rates of Exchange</em> JSON. We bind only the four fields the
 * selection rule needs and <strong>ignore everything else</strong> ({@code meta}, {@code links}, and any
 * fields Treasury adds later) so a benign schema addition never breaks us (constitution §7, schema
 * tolerance). The rate stays a <em>string</em> here and is parsed to {@code BigDecimal} downstream with no
 * assumed scale (F2). A missing <em>critical</em> field surfaces as a clear mapped error, never an NPE.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record TreasuryRatesPayload(@JsonProperty("data") List<Row> data) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Row(
            @JsonProperty("country_currency_desc") String countryCurrencyDesc,
            @JsonProperty("exchange_rate") String exchangeRate,
            @JsonProperty("effective_date") String effectiveDate,
            @JsonProperty("record_date") String recordDate) {}
}
