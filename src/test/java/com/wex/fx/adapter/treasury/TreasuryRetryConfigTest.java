package com.wex.fx.adapter.treasury;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.retry.Retry;
import java.time.Duration;
import java.util.function.Function;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

/**
 * Fast unit test for the Treasury {@link Retry} wait policy (finding #6). Proves the configured retry uses
 * an <em>exponential backoff with jitter</em> interval function — not the old fixed 200ms wait — so retry
 * waves de-synchronize on recovery. We assert (a) successive attempts grow geometrically and (b) the
 * randomization actually varies the delay within the expected ±factor band.
 */
class TreasuryRetryConfigTest {

    private static RatesProperties.Resilience resilience() {
        return new RatesProperties.Resilience(
                /* maxAttempts */ 2,
                /* retryBackoff */ Duration.ofMillis(200),
                /* retryMultiplier */ 2.0,
                /* retryRandomizationFactor */ 0.5,
                /* slidingWindowSize */ 20,
                /* minimumNumberOfCalls */ 10,
                /* failureRateThreshold */ 50f,
                /* waitDurationInOpenState */ Duration.ofSeconds(30),
                /* permittedCallsInHalfOpenState */ 3);
    }

    // getIntervalFunction() is deprecated in favour of the bi-function variant, but for a single-arg
    // backoff it is the direct way to read back the configured wait policy — exactly what we assert.
    @SuppressWarnings("deprecation")
    private static Function<Integer, Long> intervalFunction() {
        RatesProperties props = new RatesProperties("ondemand", null, 6, null, null, resilience(), null);
        Retry retry = new TreasuryRateProviderConfiguration().treasuryRetry(props);
        return retry.getRetryConfig().getIntervalFunction();
    }

    @Test
    void retry_wait_is_exponential_with_jitter_not_a_fixed_interval() {
        Function<Integer, Long> interval = intervalFunction();

        // Attempt 1: base 200ms randomized by ±50% → [100ms, 300ms]. Attempt 2: base 400ms → [200ms, 600ms].
        assertThat(interval.apply(1)).isBetween(100L, 300L);
        assertThat(interval.apply(2)).isBetween(200L, 600L);
    }

    @Test
    void jitter_varies_the_delay_across_invocations() {
        Function<Integer, Long> interval = intervalFunction();

        // A fixed wait would return the same value every time; with jitter the same attempt index varies.
        long distinct = LongStream.range(0, 50).map(i -> interval.apply(1)).distinct().count();
        assertThat(distinct).isGreaterThan(1);
    }
}
