package com.wex.fx.domain.currency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for ISO-4217 -> Treasury descriptor resolution (T2.5, currency-mapping.md). Exercises
 * the bundled curated map plus the construction guards. The headline assertion is
 * <strong>XOF != XAF</strong> &mdash; word-keying "Cfa Franc" would be provably unsafe (F9).
 */
class CurrencyMapTest {

    private final CurrencyMap map = CurrencyMap.loadDefault();

    @Test
    void supported_currencies_resolve_to_their_exact_descriptor() {
        assertThat(map.resolve("EUR")).isEqualTo(new CurrencyResolution.Supported("Euro Zone-Euro"));
        assertThat(map.resolve("CAD")).isEqualTo(new CurrencyResolution.Supported("Canada-Dollar"));
    }

    @Test
    void xof_and_xaf_resolve_to_different_descriptors() {
        CurrencyResolution xof = map.resolve("XOF");
        CurrencyResolution xaf = map.resolve("XAF");
        assertThat(xof).isInstanceOf(CurrencyResolution.Supported.class);
        assertThat(xaf).isInstanceOf(CurrencyResolution.Supported.class);
        String xofDesc = ((CurrencyResolution.Supported) xof).descriptor();
        String xafDesc = ((CurrencyResolution.Supported) xaf).descriptor();
        assertThat(xofDesc).isNotEqualTo(xafDesc);
        assertThat(xofDesc).isEqualTo("Senegal-Cfa Franc");
        assertThat(xafDesc).isEqualTo("Cameroon-Cfa Franc");
    }

    @Test
    void usd_is_an_in_app_identity_never_mapped() {
        assertThat(map.resolve("USD")).isInstanceOf(CurrencyResolution.Identity.class);
        assertThat(map.supportedCodes()).doesNotContain("USD");
    }

    @Test
    void iso_valid_but_uncurated_currency_is_unsupported() {
        assertThat(map.resolve("ZZZ")).isEqualTo(new CurrencyResolution.Unsupported("ZZZ"));
    }

    @Test
    void malformed_tokens_are_flagged_distinctly_from_unsupported() {
        assertThat(map.resolve("eur")).isInstanceOf(CurrencyResolution.Malformed.class);   // lowercase
        assertThat(map.resolve("EU")).isInstanceOf(CurrencyResolution.Malformed.class);     // too short
        assertThat(map.resolve("EURO")).isInstanceOf(CurrencyResolution.Malformed.class);   // too long
        assertThat(map.resolve("E1R")).isInstanceOf(CurrencyResolution.Malformed.class);    // digit
        assertThat(map.resolve(null)).isInstanceOf(CurrencyResolution.Malformed.class);
    }

    @Test
    void default_map_loads_a_sensible_core_with_usd_absent() {
        assertThat(map.supportedCodes())
                .contains("EUR", "GBP", "CAD", "AUD", "JPY", "ARS", "XOF", "XAF", "XCD")
                .doesNotContain("USD");
    }

    // --- construction guards (fail fast at startup on a bad artifact) -----------------------------

    @Test
    void constructor_rejects_mapping_usd() {
        assertThatThrownBy(() -> new CurrencyMap(Map.of("USD", "Should-Not-Exist")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("USD");
    }

    @Test
    void constructor_rejects_malformed_iso_key_and_blank_descriptor() {
        assertThatThrownBy(() -> new CurrencyMap(Map.of("eur", "Euro Zone-Euro")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CurrencyMap(Map.of("EUR", "  ")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
