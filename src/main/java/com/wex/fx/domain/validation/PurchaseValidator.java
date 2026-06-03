package com.wex.fx.domain.validation;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The <strong>single place</strong> the store-purchase validation policy lives (constitution &sect;3)
 * so the accept-vs-reject posture flips cheaply. Pure &mdash; only JDK types (incl. an injected
 * {@link Clock}); no framework imports, so it stays under the ArchUnit domain gate.
 *
 * <p>Each field has a method that returns the parsed value or throws a single-error
 * {@link ValidationException}; {@link #validate} runs all three and <strong>accumulates</strong>
 * errors so the caller can return every problem at once.
 *
 * <p>Rules (api-contract.md, D-05/D-06):
 * <ul>
 *   <li><b>description</b> &mdash; required; trimmed; 1&ndash;50 Unicode <em>code points</em>
 *       (documented counting rule); no control characters.</li>
 *   <li><b>amount</b> &mdash; plain decimal string {@code ^-?\d+(\.\d+)?$}; &le; 2 dp
 *       (<em>reject, never round</em>); {@code > 0}; within the configured cap.</li>
 *   <li><b>transactionDate</b> &mdash; strict ISO local date {@code uuuu-MM-dd}; not in the future
 *       (injected {@code Clock} + skew). Old dates are accepted here; they fail only at conversion.</li>
 * </ul>
 */
public final class PurchaseValidator {

    public static final int DEFAULT_MAX_DESCRIPTION_CODE_POINTS = 50;

    /** Largest value that fits {@code NUMERIC(19,2)}: 17 integer digits + 2 decimals. */
    public static final BigDecimal NUMERIC_19_2_MAX = new BigDecimal("99999999999999999.99");

    /** Cent precision: the principal may carry at most this many decimal places (D-05). */
    private static final int MAX_DECIMAL_PLACES = 2;

    // Plain decimal only: optional leading minus, digits, optional fraction. No separators,
    // currency symbols, leading '+', whitespace, or scientific notation.
    private static final Pattern DECIMAL_SHAPE = Pattern.compile("-?\\d+(\\.\\d+)?");

    // STRICT + proleptic year ('uuuu', not 'yyyy') rejects impossible dates (2026-02-30) and
    // non-ISO shapes (03/15/2026, 2026-3-15) rather than silently coercing them.
    private static final DateTimeFormatter ISO_STRICT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);

    private final Clock clock;
    private final Duration futureSkew;
    private final int maxDescriptionCodePoints;
    private final BigDecimal maxAmount;

    public PurchaseValidator(Clock clock, Duration futureSkew, int maxDescriptionCodePoints, BigDecimal maxAmount) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.futureSkew = Objects.requireNonNull(futureSkew, "futureSkew");
        if (maxDescriptionCodePoints <= 0) {
            throw new IllegalArgumentException("maxDescriptionCodePoints must be positive");
        }
        this.maxDescriptionCodePoints = maxDescriptionCodePoints;
        this.maxAmount = Objects.requireNonNull(maxAmount, "maxAmount");
    }

    public static PurchaseValidator withDefaults(Clock clock) {
        return new PurchaseValidator(clock, Duration.ZERO, DEFAULT_MAX_DESCRIPTION_CODE_POINTS, NUMERIC_19_2_MAX);
    }

    /** Validate all fields, accumulating every failure into one {@link ValidationException}. */
    public ValidatedPurchase validate(String description, String amount, String transactionDate) {
        List<FieldError> errors = new ArrayList<>();
        String desc = null;
        BigDecimal amt = null;
        LocalDate date = null;
        try {
            desc = validateDescription(description);
        } catch (ValidationException e) {
            errors.addAll(e.errors());
        }
        try {
            amt = validateAmount(amount);
        } catch (ValidationException e) {
            errors.addAll(e.errors());
        }
        try {
            date = validateTransactionDate(transactionDate);
        } catch (ValidationException e) {
            errors.addAll(e.errors());
        }
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }
        return new ValidatedPurchase(desc, amt, date);
    }

    public String validateDescription(String raw) {
        if (raw == null) {
            throw fail("description", ValidationCode.DESCRIPTION_INVALID, "description is required");
        }
        String trimmed = raw.strip();
        if (trimmed.isEmpty()) {
            throw fail("description", ValidationCode.DESCRIPTION_INVALID, "description must not be blank");
        }
        if (trimmed.codePoints().anyMatch(Character::isISOControl)) {
            throw fail("description", ValidationCode.DESCRIPTION_INVALID,
                    "description must not contain control characters");
        }
        int codePoints = trimmed.codePointCount(0, trimmed.length());
        if (codePoints > maxDescriptionCodePoints) {
            throw fail("description", ValidationCode.DESCRIPTION_TOO_LONG,
                    "description must be at most " + maxDescriptionCodePoints + " code points");
        }
        return trimmed;
    }

    public BigDecimal validateAmount(String raw) {
        if (raw == null || raw.isBlank()) {
            throw fail("amount", ValidationCode.AMOUNT_MALFORMED, "amount is required");
        }
        String s = raw.strip();
        if (!DECIMAL_SHAPE.matcher(s).matches()) {
            throw fail("amount", ValidationCode.AMOUNT_MALFORMED,
                    "amount must be a plain decimal (no separators, symbols, leading +, or scientific notation)");
        }
        int dot = s.indexOf('.');
        int fractionDigits = (dot < 0) ? 0 : s.length() - dot - 1;
        if (fractionDigits > MAX_DECIMAL_PLACES) {
            // Reject, never round (D-05): silently truncating the principal would mutate it.
            throw fail("amount", ValidationCode.AMOUNT_PRECISION, "amount must have at most 2 decimal places");
        }
        BigDecimal value = new BigDecimal(s);
        if (value.signum() <= 0) {
            throw fail("amount", ValidationCode.AMOUNT_NOT_POSITIVE, "amount must be greater than zero");
        }
        if (value.compareTo(maxAmount) > 0) {
            throw fail("amount", ValidationCode.AMOUNT_MALFORMED, "amount exceeds the maximum supported value");
        }
        return value;
    }

    public LocalDate validateTransactionDate(String raw) {
        if (raw == null || raw.isBlank()) {
            throw fail("transactionDate", ValidationCode.DATE_INVALID, "transactionDate is required");
        }
        LocalDate parsed;
        try {
            parsed = LocalDate.parse(raw.strip(), ISO_STRICT);
        } catch (DateTimeParseException e) {
            throw fail("transactionDate", ValidationCode.DATE_INVALID,
                    "transactionDate must be a valid ISO local date (uuuu-MM-dd)");
        }
        // "Today" in the clock's zone, nudged by the skew tolerance so a slightly-fast client clock
        // near midnight is not wrongly rejected. Default skew is zero.
        LocalDate latestAcceptable = LocalDate.ofInstant(clock.instant().plus(futureSkew), clock.getZone());
        if (parsed.isAfter(latestAcceptable)) {
            throw fail("transactionDate", ValidationCode.DATE_IN_FUTURE, "transactionDate must not be in the future");
        }
        return parsed;
    }

    private static ValidationException fail(String field, ValidationCode code, String message) {
        return new ValidationException(new FieldError(field, code, message));
    }
}
