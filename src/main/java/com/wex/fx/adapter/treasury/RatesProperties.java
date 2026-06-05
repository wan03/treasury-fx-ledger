package com.wex.fx.adapter.treasury;

import com.wex.fx.domain.rate.RateDateBasis;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Bound view of {@code fx.rates.*} (application.yml). All tunables for the Treasury rate providers live
 * here so timeouts, retry/breaker thresholds, the 6-month window, and the cache TTLs are configuration,
 * not magic numbers (plan.md §Configuration; constitution §7). Records with {@link DefaultValue} keep
 * every key optional — the defaults below are the documented production posture.
 *
 * @param provider     which adapter is active: {@code passthrough} (A0), {@code ondemand} (A, default),
 *                     {@code ingest} (B) or {@code hybrid} (C). B/C land in a later slice; until then they
 *                     fall back to the no-rate provider. (D-03)
 * @param windowMonths the rate-selection window, in calendar months (D-02 / rate-selection.md).
 * @param rateDateBasis which Treasury date governs selection: {@code effective_date} (default, the
 *                     authoritative reading of D-02/F8) or {@code record_date} (the literal-brief reading).
 *                     Both agree for every non-amended currency; relaxed binding accepts {@code effective-date}.
 */
@ConfigurationProperties("fx.rates")
public record RatesProperties(
        @DefaultValue("ondemand") String provider,
        @DefaultValue Treasury treasury,
        @DefaultValue("6") int windowMonths,
        @DefaultValue("effective_date") RateDateBasis rateDateBasis,
        @DefaultValue Cache cache,
        @DefaultValue Resilience resilience,
        @DefaultValue Sync sync) {

    /**
     * HTTP knobs for the Treasury fetcher. Timeouts are always bounded (never unbounded waits on an
     * external dependency).
     */
    public record Treasury(
            @DefaultValue("https://api.fiscaldata.treasury.gov/services/api/fiscal_service") String baseUrl,
            @DefaultValue("2s") Duration connectTimeout,
            @DefaultValue("5s") Duration readTimeout) {}

    /**
     * Provider-A cache (Caffeine). Quarter-aware TTL: a purchase whose date falls in a settled past
     * quarter is effectively immutable (cache long); one in the current, still-amendable quarter caches
     * briefly so a late amendment (F8) is picked up; a "no rate" outcome caches only briefly.
     */
    public record Cache(
            @DefaultValue("30d") Duration settledTtl,
            @DefaultValue("10m") Duration currentQuarterTtl,
            @DefaultValue("1m") Duration negativeTtl,
            @DefaultValue("10000") long maximumSize) {}

    /**
     * Resilience around the fetcher. Retries are bounded and fire <em>only</em> on transient upstream
     * failures (5xx / timeout / connection) — never on a {@code 4xx}. The breaker fails fast once the
     * failure rate over the recent window crosses the threshold.
     *
     * <p>{@code minimumNumberOfCalls} must stay {@code <= slidingWindowSize}: Resilience4j's default is
     * 100, but a COUNT_BASED window only ever accumulates {@code slidingWindowSize} measurements, so a
     * default-100 minimum against a 20-wide window would mean the breaker could <em>never</em> open.
     */
    public record Resilience(
            @DefaultValue("2") int maxAttempts,
            @DefaultValue("200ms") Duration retryBackoff,
            @DefaultValue("20") int slidingWindowSize,
            @DefaultValue("10") int minimumNumberOfCalls,
            @DefaultValue("50") float failureRateThreshold,
            @DefaultValue("30s") Duration waitDurationInOpenState,
            @DefaultValue("3") int permittedCallsInHalfOpenState) {}

    /**
     * Ingest/sync knobs for providers B/C. {@code windowMonths} bounds how far back the backfill pulls
     * (the local store can only answer dates it has ingested); {@code interval} is the scheduled-reconcile
     * cadence — short enough to catch a current-quarter amendment, long enough to stay light on Treasury.
     * Inert under A0/A.
     */
    public record Sync(
            @DefaultValue("24") int windowMonths,
            @DefaultValue("6h") Duration interval) {}
}
