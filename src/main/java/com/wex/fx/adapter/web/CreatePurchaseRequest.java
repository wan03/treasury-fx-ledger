package com.wex.fx.adapter.web;

/**
 * Wire shape of {@code POST /v1/purchases} (api-contract.md / OpenAPI {@code CreatePurchaseRequest}).
 *
 * <p>Every field is a <strong>raw string</strong>, deliberately unparsed: the domain
 * {@code PurchaseValidator} owns every parse/shape decision (reject-not-round amounts, strict ISO
 * dates), so the edge does no lenient coercion of its own. {@code amount} in particular is a string,
 * never a JSON number — numbers would invite binary-float rounding before we ever see them (D-04).
 *
 * <p>Unknown properties are rejected by the global Jackson policy
 * ({@code fail-on-unknown-properties: true}) → a {@code 400 MALFORMED_REQUEST}, matching the
 * contract's {@code additionalProperties: false}.
 *
 * @param description     raw description (validated: 1&ndash;50 code points, trimmed, no control chars)
 * @param transactionDate raw ISO date string (validated: strict {@code uuuu-MM-dd}, not future)
 * @param amount          raw decimal string (validated: {@code ^\d{1,17}(\.\d{1,2})?$}, &gt; 0, within cap)
 * @param currency        optional ISO code; omitted/blank defaults to USD; any non-USD &rArr; not storable
 */
public record CreatePurchaseRequest(
        String description, String transactionDate, String amount, String currency) {}
