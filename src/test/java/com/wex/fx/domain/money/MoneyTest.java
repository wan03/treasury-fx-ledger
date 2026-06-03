package com.wex.fx.domain.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link Money} value object (T2.5). Locks the money-path invariants from
 * test-strategy.md &sect;1: HALF_UP rounding, round-once, scale 2, no overflow, USD identity, and
 * the cross-currency guard.
 */
class MoneyTest {

    @Test
    void normalizes_to_scale_2_padding_a_cent_precise_principal() {
        Money m = Money.usd("12.3");
        assertThat(m.amount().scale()).isEqualTo(2);
        assertThat(m.amount().toPlainString()).isEqualTo("12.30");
    }

    @Test
    void integer_amount_is_padded_to_two_decimals() {
        assertThat(Money.usd("12").amount().toPlainString()).isEqualTo("12.00");
    }

    @Test
    void converts_with_half_up_rounding_once_at_the_end() {
        // 12.34 * 0.853 = 10.52602 -> 10.53 (test-strategy.md money example).
        Money converted = Money.usd("12.34").convertedAt(new BigDecimal("0.853"), "EUR");
        assertThat(converted.amount().toPlainString()).isEqualTo("10.53");
        assertThat(converted.currencyCode()).isEqualTo("EUR");
    }

    @Test
    void tie_rounds_half_up_not_half_even() {
        // 0.10 * 0.05 = 0.0050 -> 0.01 under HALF_UP (HALF_EVEN would give 0.00). The locking test.
        Money converted = Money.usd("0.10").convertedAt(new BigDecimal("0.05"), "XAF");
        assertThat(converted.amount().toPlainString()).isEqualTo("0.01");
    }

    @Test
    void rounds_once_a_high_precision_rate_is_not_truncated_mid_calc() {
        // Pre-rounding the rate to 2dp (0.34) would give 12.00*0.34 = 4.08; full precision then a
        // single final rounding gives 12.00*0.336 = 4.032 -> 4.03. Proves "round once".
        Money converted = Money.usd("12.00").convertedAt(new BigDecimal("0.336"), "EUR");
        assertThat(converted.amount().toPlainString()).isEqualTo("4.03");
    }

    @Test
    void usd_identity_multiplies_by_one() {
        Money converted = Money.usd("999.99").convertedAt(BigDecimal.ONE, "USD");
        assertThat(converted.isSameValueAs(Money.usd("999.99"))).isTrue();
    }

    @Test
    void handles_large_amounts_without_overflow() {
        Money huge = Money.usd("99999999999999999.99");
        Money converted = huge.convertedAt(new BigDecimal("1230.0"), "ARS");
        assertThat(converted.amount().toPlainString()).isEqualTo("122999999999999999987.70");
    }

    @Test
    void value_equality_uses_compareTo_not_scale_sensitive_equals() {
        // 12.3 and 12.30 are the same money; normalization makes record equals agree with compareTo.
        Money a = Money.usd("12.3");
        Money b = Money.usd("12.30");
        assertThat(a.isSameValueAs(b)).isTrue();
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void of_constructs_with_the_given_value_and_currency() {
        Money m = Money.of(new BigDecimal("5"), "EUR");
        assertThat(m.amount().toPlainString()).isEqualTo("5.00");
        assertThat(m.currencyCode()).isEqualTo("EUR");
    }

    @Test
    void compareTo_orders_by_amount_within_a_currency() {
        assertThat(Money.usd("1.00").compareTo(Money.usd("2.00"))).isNegative();
        assertThat(Money.usd("2.00").compareTo(Money.usd("1.00"))).isPositive();
        assertThat(Money.usd("1.50").compareTo(Money.usd("1.50"))).isZero();
    }

    @Test
    void isSameValueAs_is_false_across_currencies_and_differing_amounts() {
        assertThat(Money.usd("1.00").isSameValueAs(Money.of("1.00", "EUR"))).isFalse();
        assertThat(Money.usd("1.00").isSameValueAs(Money.usd("2.00"))).isFalse();
    }

    @Test
    void compare_across_currencies_is_rejected() {
        assertThatThrownBy(() -> Money.usd("1.00").compareTo(Money.of("1.00", "EUR")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot compare across currencies");
    }

    @Test
    void rejects_null_and_malformed_currency_code() {
        assertThatThrownBy(() -> Money.of(BigDecimal.ONE, "usd"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.of(BigDecimal.ONE, "EURO"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.of((BigDecimal) null, "USD"))
                .isInstanceOf(NullPointerException.class);
    }
}
