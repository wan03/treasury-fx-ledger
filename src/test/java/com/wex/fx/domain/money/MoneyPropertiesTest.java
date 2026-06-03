package com.wex.fx.domain.money;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Property-based invariants on {@link Money} (test-strategy.md &sect;1). These don't pin specific
 * outputs &mdash; they assert laws that must hold for <em>every</em> positive amount and rate, which
 * is what hardens the rounding code against off-by-one mutations.
 */
class MoneyPropertiesTest {

    @Property
    void output_scale_is_always_two(@ForAll("positiveAmounts") BigDecimal amount,
                                    @ForAll("positiveRates") BigDecimal rate) {
        Money converted = Money.usd(amount.toPlainString()).convertedAt(rate, "EUR");
        assertThat(converted.amount().scale()).isEqualTo(Money.SCALE);
    }

    @Property
    void identity_at_rate_one_preserves_value(@ForAll("positiveAmounts") BigDecimal amount) {
        Money m = Money.usd(amount.toPlainString());
        assertThat(m.convertedAt(BigDecimal.ONE, "USD").isSameValueAs(m)).isTrue();
    }

    @Property
    void conversion_is_monotonic_in_amount(@ForAll("positiveAmounts") BigDecimal a,
                                           @ForAll("positiveAmounts") BigDecimal b,
                                           @ForAll("positiveRates") BigDecimal rate) {
        Money lo = Money.usd(a.min(b).toPlainString());
        Money hi = Money.usd(a.max(b).toPlainString());
        BigDecimal convertedLo = lo.convertedAt(rate, "EUR").amount();
        BigDecimal convertedHi = hi.convertedAt(rate, "EUR").amount();
        assertThat(convertedLo.compareTo(convertedHi)).isLessThanOrEqualTo(0);
    }

    @Provide
    Arbitrary<BigDecimal> positiveAmounts() {
        return Arbitraries.bigDecimals()
                .between(new BigDecimal("0.01"), new BigDecimal("1000000000.00"))
                .ofScale(2)
                .filter(b -> b.signum() > 0);
    }

    @Provide
    Arbitrary<BigDecimal> positiveRates() {
        return Arbitraries.bigDecimals()
                .between(new BigDecimal("0.000001"), new BigDecimal("100000.000000"))
                .ofScale(6)
                .filter(b -> b.signum() > 0);
    }
}
