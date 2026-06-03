package com.wex.fx.domain.currency;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Resolves ISO-4217 target codes to Treasury {@code country_currency_desc} strings through a
 * curated, version-controlled map (D-01, currency-mapping.md). The map is <strong>data, not
 * code</strong> &mdash; loaded at startup from {@code /currency-map.csv}; expanding the supported
 * set is adding rows, never changing logic.
 *
 * <p>Pure: only JDK types, so it stays under the ArchUnit domain gate. The {@code @Tag("live")}
 * canary (T6.2) verifies the descriptor strings against the live dataset; the gating suite tests
 * the resolution <em>logic</em> against fixtures only.
 *
 * <p>Resolution policy (currency-mapping.md):
 * <pre>
 *   malformed token (not ^[A-Z]{3}$) -&gt; Malformed   (400)
 *   USD                              -&gt; Identity    (no upstream call, D-07)
 *   in curated map                   -&gt; Supported   (the common case)
 *   ISO-valid but absent             -&gt; Unsupported (422)
 * </pre>
 */
public final class CurrencyMap {

    private static final Pattern ISO_4217 = Pattern.compile("[A-Z]{3}");
    private static final String IDENTITY_CODE = "USD";
    private static final String DEFAULT_RESOURCE = "/currency-map.csv";

    private final Map<String, String> isoToDescriptor;

    /** Build from a pre-parsed map (tests / programmatic construction). Validates + freezes it. */
    public CurrencyMap(Map<String, String> isoToDescriptor) {
        Map<String, String> copy = new LinkedHashMap<>();
        isoToDescriptor.forEach((iso, descriptor) -> {
            if (iso == null || !ISO_4217.matcher(iso).matches()) {
                throw new IllegalArgumentException("currency-map key must match [A-Z]{3}, was: " + iso);
            }
            if (IDENTITY_CODE.equals(iso)) {
                throw new IllegalArgumentException("USD must not be mapped; it is an in-app identity (D-07)");
            }
            if (descriptor == null || descriptor.isBlank()) {
                throw new IllegalArgumentException("blank descriptor for " + iso);
            }
            if (copy.put(iso, descriptor.strip()) != null) {
                throw new IllegalArgumentException("duplicate currency-map key: " + iso);
            }
        });
        this.isoToDescriptor = Collections.unmodifiableMap(copy);
    }

    /** Load the bundled curated map from {@code /currency-map.csv} on the classpath. */
    public static CurrencyMap loadDefault() {
        return load(DEFAULT_RESOURCE);
    }

    public static CurrencyMap load(String resourcePath) {
        try (InputStream in = CurrencyMap.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException("currency map resource not found on classpath: " + resourcePath);
            }
            return parse(in);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read currency map: " + resourcePath, e);
        }
    }

    /**
     * Parse a {@code iso,country_currency_desc[,note]} CSV. Blank lines and {@code #} comments are
     * skipped; only the first two columns are read (descriptors contain no commas &mdash; a
     * documented simplification). The optional note column is informational.
     */
    static CurrencyMap parse(InputStream in) throws IOException {
        Map<String, String> map = new LinkedHashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            int lineNo = 0;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                String trimmed = line.strip();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                String[] parts = trimmed.split(",", -1);
                if (parts.length < 2 || parts[0].isBlank() || parts[1].isBlank()) {
                    throw new IllegalArgumentException(
                            "malformed currency-map line " + lineNo + " (expected: iso,descriptor)");
                }
                String iso = parts[0].strip();
                if (map.put(iso, parts[1].strip()) != null) {
                    throw new IllegalArgumentException("duplicate currency-map key on line " + lineNo + ": " + iso);
                }
            }
        }
        return new CurrencyMap(map);
    }

    /** Resolve a target code to its outcome. Case-sensitive: lowercase is malformed, not USD/identity. */
    public CurrencyResolution resolve(String code) {
        if (code == null || !ISO_4217.matcher(code).matches()) {
            return new CurrencyResolution.Malformed(code);
        }
        if (IDENTITY_CODE.equals(code)) {
            return new CurrencyResolution.Identity();
        }
        String descriptor = isoToDescriptor.get(code);
        if (descriptor == null) {
            return new CurrencyResolution.Unsupported(code);
        }
        return new CurrencyResolution.Supported(descriptor);
    }

    /** The supported ISO codes (USD excluded by design). */
    public Set<String> supportedCodes() {
        return isoToDescriptor.keySet();
    }

    /** Immutable ISO &rarr; descriptor view (drives the live canary's "every entry resolves" check). */
    public Map<String, String> asMap() {
        return isoToDescriptor;
    }
}
