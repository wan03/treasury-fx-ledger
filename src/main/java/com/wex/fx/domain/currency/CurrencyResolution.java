package com.wex.fx.domain.currency;

/**
 * The outcome of resolving an ISO-4217 target code to a Treasury {@code country_currency_desc}
 * (D-01, currency-mapping.md). A sealed type so the web layer pattern-matches exhaustively onto the
 * right HTTP status:
 *
 * <ul>
 *   <li>{@link Identity} &mdash; {@code USD}: in-app identity, rate {@code 1.00}, no upstream call (D-07);</li>
 *   <li>{@link Supported} &mdash; carries the exact Treasury descriptor to query;</li>
 *   <li>{@link Unsupported} &mdash; ISO-valid but not in the curated map &rarr; {@code 422 CURRENCY_UNSUPPORTED};</li>
 *   <li>{@link Malformed} &mdash; not {@code ^[A-Z]{3}$} &rarr; {@code 400 CURRENCY_CODE_MALFORMED}.</li>
 * </ul>
 */
public sealed interface CurrencyResolution
        permits CurrencyResolution.Identity,
                CurrencyResolution.Supported,
                CurrencyResolution.Unsupported,
                CurrencyResolution.Malformed {

    /** USD: convert in-app at rate 1.00, no Treasury call (D-07). */
    record Identity() implements CurrencyResolution {
    }

    /** A supported currency, resolved to its exact Treasury {@code country_currency_desc}. */
    record Supported(String descriptor) implements CurrencyResolution {
    }

    /** ISO-shaped but outside the curated supported set &rarr; {@code 422 CURRENCY_UNSUPPORTED}. */
    record Unsupported(String code) implements CurrencyResolution {
    }

    /** Not a well-formed ISO-4217 token ({@code ^[A-Z]{3}$}) &rarr; {@code 400 CURRENCY_CODE_MALFORMED}. */
    record Malformed(String code) implements CurrencyResolution {
    }
}
