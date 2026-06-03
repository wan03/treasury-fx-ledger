package com.wex.fx.domain.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PurchaseValidator} (T2.5, test-strategy.md &sect;1). A fixed {@link Clock}
 * (2026-06-03 UTC) makes the future-date rule deterministic (no {@code LocalDate.now()} anywhere).
 * Verifies the precise machine codes from the api-contract error catalog.
 */
class PurchaseValidatorTest {

    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-06-03T12:00:00Z"), ZoneOffset.UTC);

    private final PurchaseValidator validator = PurchaseValidator.withDefaults(FIXED);

    // --- description: 1-50 Unicode CODE POINTS, trimmed, no control chars ------------------------

    @Test
    void description_at_or_below_50_code_points_is_accepted_and_trimmed() {
        assertThat(validator.validateDescription("a".repeat(49))).hasSize(49);
        assertThat(validator.validateDescription("a".repeat(50))).hasSize(50);
        assertThat(validator.validateDescription("  Office supplies  ")).isEqualTo("Office supplies");
    }

    @Test
    void description_counts_code_points_not_utf16_chars() {
        // 50 emoji = 100 UTF-16 chars but 50 code points -> accepted (proves code-point counting).
        String fiftyEmoji = "😀".repeat(50);
        assertThat(fiftyEmoji.length()).isEqualTo(100);
        assertThatCode(() -> validator.validateDescription(fiftyEmoji)).doesNotThrowAnyException();
        // 51 emoji -> 51 code points -> too long.
        assertRejected(() -> validator.validateDescription("😀".repeat(51)),
                ValidationCode.DESCRIPTION_TOO_LONG);
    }

    @Test
    void description_over_50_code_points_is_rejected() {
        assertRejected(() -> validator.validateDescription("a".repeat(51)), ValidationCode.DESCRIPTION_TOO_LONG);
    }

    @Test
    void blank_null_and_control_char_descriptions_are_invalid() {
        assertRejected(() -> validator.validateDescription("   "), ValidationCode.DESCRIPTION_INVALID);
        assertRejected(() -> validator.validateDescription(null), ValidationCode.DESCRIPTION_INVALID);
        assertRejected(() -> validator.validateDescription("bad\u0001ctrl"), ValidationCode.DESCRIPTION_INVALID);
        assertRejected(() -> validator.validateDescription("tab\there"), ValidationCode.DESCRIPTION_INVALID);
    }

    // --- amount: plain decimal, <=2dp (reject, never round), > 0, within cap ----------------------

    @Test
    void valid_amounts_are_accepted_with_value_preserved() {
        assertThat(validator.validateAmount("12.34")).isEqualByComparingTo("12.34");
        assertThat(validator.validateAmount("12.3")).isEqualByComparingTo("12.3");
        assertThat(validator.validateAmount("12")).isEqualByComparingTo("12");
        assertThat(validator.validateAmount("99999999999999999.99")).isEqualByComparingTo("99999999999999999.99");
    }

    @Test
    void more_than_two_decimals_is_rejected_not_rounded() {
        assertRejected(() -> validator.validateAmount("12.345"), ValidationCode.AMOUNT_PRECISION);
        assertRejected(() -> validator.validateAmount("0.001"), ValidationCode.AMOUNT_PRECISION);
    }

    @Test
    void zero_and_negative_amounts_are_not_positive() {
        assertRejected(() -> validator.validateAmount("0"), ValidationCode.AMOUNT_NOT_POSITIVE);
        assertRejected(() -> validator.validateAmount("0.00"), ValidationCode.AMOUNT_NOT_POSITIVE);
        assertRejected(() -> validator.validateAmount("-1"), ValidationCode.AMOUNT_NOT_POSITIVE);
    }

    @Test
    void separators_symbols_sign_sci_notation_and_over_cap_are_malformed() {
        assertRejected(() -> validator.validateAmount("1,000"), ValidationCode.AMOUNT_MALFORMED);
        assertRejected(() -> validator.validateAmount("$5"), ValidationCode.AMOUNT_MALFORMED);
        assertRejected(() -> validator.validateAmount("+5"), ValidationCode.AMOUNT_MALFORMED);
        assertRejected(() -> validator.validateAmount("1e3"), ValidationCode.AMOUNT_MALFORMED);
        assertRejected(() -> validator.validateAmount("12.3.4"), ValidationCode.AMOUNT_MALFORMED);
        assertRejected(() -> validator.validateAmount(null), ValidationCode.AMOUNT_MALFORMED);
        // one cent over the NUMERIC(19,2) ceiling.
        assertRejected(() -> validator.validateAmount("100000000000000000.00"), ValidationCode.AMOUNT_MALFORMED);
    }

    // --- transactionDate: strict ISO, reject future (fixed Clock), accept past --------------------

    @Test
    void valid_past_and_today_dates_are_accepted() {
        assertThat(validator.validateTransactionDate("2026-06-03")).isEqualTo("2026-06-03");  // today
        assertThat(validator.validateTransactionDate("2020-01-01")).isEqualTo("2020-01-01");
        assertThat(validator.validateTransactionDate("1900-01-01")).isEqualTo("1900-01-01");  // old, stored anyway
    }

    @Test
    void impossible_and_non_iso_dates_are_invalid() {
        assertRejected(() -> validator.validateTransactionDate("2026-02-30"), ValidationCode.DATE_INVALID);
        assertRejected(() -> validator.validateTransactionDate("2026-13-01"), ValidationCode.DATE_INVALID);
        assertRejected(() -> validator.validateTransactionDate("03/15/2026"), ValidationCode.DATE_INVALID);
        assertRejected(() -> validator.validateTransactionDate("2026-3-15"), ValidationCode.DATE_INVALID);
        assertRejected(() -> validator.validateTransactionDate(null), ValidationCode.DATE_INVALID);
    }

    @Test
    void future_dates_are_rejected() {
        assertRejected(() -> validator.validateTransactionDate("2026-06-04"), ValidationCode.DATE_IN_FUTURE);
        assertRejected(() -> validator.validateTransactionDate("2030-01-01"), ValidationCode.DATE_IN_FUTURE);
    }

    // --- aggregate: accumulate every failure ------------------------------------------------------

    @Test
    void aggregate_validate_accumulates_all_field_errors() {
        assertThatThrownBy(() -> validator.validate("", "12.345", "2030-01-01"))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).errors())
                        .extracting(FieldError::code)
                        .containsExactlyInAnyOrder(
                                ValidationCode.DESCRIPTION_INVALID,
                                ValidationCode.AMOUNT_PRECISION,
                                ValidationCode.DATE_IN_FUTURE));
    }

    @Test
    void aggregate_validate_returns_parsed_values_when_all_valid() {
        ValidatedPurchase result = validator.validate("Office supplies", "12.34", "2026-03-15");
        assertThat(result.description()).isEqualTo("Office supplies");
        assertThat(result.amount()).isEqualByComparingTo("12.34");
        assertThat(result.transactionDate()).isEqualTo("2026-03-15");
    }

    private static void assertRejected(ThrowingCallable call, ValidationCode expected) {
        assertThatThrownBy(call)
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).errors())
                        .extracting(FieldError::code)
                        .containsExactly(expected));
    }
}
