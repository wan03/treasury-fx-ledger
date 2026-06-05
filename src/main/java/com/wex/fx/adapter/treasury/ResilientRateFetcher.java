package com.wex.fx.adapter.treasury;

import com.wex.fx.application.error.RateProviderUnavailableException;
import com.wex.fx.application.error.RateProviderUnavailableException.Reason;
import com.wex.fx.domain.rate.ExchangeRate;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/**
 * Resilience decorator around the HTTP {@link RateFetcher} (constitution §7). Composes a bounded
 * {@link Retry} (transient failures only) <em>over</em> a {@link CircuitBreaker} (which records each
 * attempt), then translates every failure mode into a single domain signal,
 * {@link RateProviderUnavailableException}, so the outage vocabulary never leaks Spring/HTTP types past
 * the adapter:
 *
 * <ul>
 *   <li>bulkhead full → fail fast, {@link Reason#OVERLOADED} (→ {@code 503});</li>
 *   <li>circuit open → fail fast, {@link Reason#CIRCUIT_OPEN} (→ {@code 503});</li>
 *   <li>read timeout → {@link Reason#TIMEOUT} (→ {@code 504});</li>
 *   <li>5xx / 4xx / malformed body → {@link Reason#UPSTREAM_ERROR} (→ {@code 502}).</li>
 * </ul>
 *
 * <p>Composition is {@code Bulkhead(Retry(CircuitBreaker(call)))}: the semaphore {@link Bulkhead}
 * bounds total in-flight upstream calls <em>outside</em> retry/breaker, so a burst of distinct-key
 * cache misses on virtual threads cannot open an unbounded number of simultaneous connections — excess
 * callers fail fast as {@code OVERLOADED} rather than pile onto the dependency.
 *
 * <p>Retry/breaker/bulkhead policy (which exceptions are transient, which trip the breaker, the
 * concurrency limit) is configured where those objects are built — {@code TreasuryRateProviderConfiguration}.
 */
final class ResilientRateFetcher implements RateFetcher {

    private final RateFetcher delegate;
    private final Retry retry;
    private final CircuitBreaker circuitBreaker;
    private final Bulkhead bulkhead;

    ResilientRateFetcher(RateFetcher delegate, Retry retry, CircuitBreaker circuitBreaker, Bulkhead bulkhead) {
        this.delegate = delegate;
        this.retry = retry;
        this.circuitBreaker = circuitBreaker;
        this.bulkhead = bulkhead;
    }

    @Override
    public List<ExchangeRate> fetch(
            String descriptor, LocalDate effectiveOnOrBefore, LocalDate effectiveOnOrAfter) {
        return guarded(() -> delegate.fetch(descriptor, effectiveOnOrBefore, effectiveOnOrAfter));
    }

    @Override
    public List<ExchangeRate> fetchWindow(String descriptor, LocalDate from, LocalDate to) {
        // The ingest/lazy-fill bulk pull rides the same retry + breaker as the per-request fetch, so a
        // failing Treasury trips the one shared breaker and the sync stops hammering it (constitution §7).
        return guarded(() -> delegate.fetchWindow(descriptor, from, to));
    }

    /** Apply the bulkhead-over-retry-over-breaker composition and collapse every failure into one signal. */
    private List<ExchangeRate> guarded(Supplier<List<ExchangeRate>> call) {
        Supplier<List<ExchangeRate>> guarded = Bulkhead.decorateSupplier(bulkhead,
                Retry.decorateSupplier(retry, CircuitBreaker.decorateSupplier(circuitBreaker, call)));
        try {
            return guarded.get();
        } catch (BulkheadFullException e) {
            throw new RateProviderUnavailableException(Reason.OVERLOADED, e);
        } catch (CallNotPermittedException e) {
            throw new RateProviderUnavailableException(Reason.CIRCUIT_OPEN, e);
        } catch (ResourceAccessException e) {
            Reason reason = isTimeout(e.getCause()) ? Reason.TIMEOUT : Reason.UPSTREAM_ERROR;
            throw new RateProviderUnavailableException(reason, e);
        } catch (HttpServerErrorException | HttpClientErrorException | TreasuryContractException e) {
            throw new RateProviderUnavailableException(Reason.UPSTREAM_ERROR, e);
        }
    }

    /**
     * A read/connect timeout, across HTTP client implementations: the JDK {@code HttpClient} (what
     * {@code ClientHttpRequestFactoryBuilder.detect()} selects here) raises {@link HttpTimeoutException},
     * whereas the Apache/Simple factories raise {@link SocketTimeoutException}. Both must map to
     * {@link Reason#TIMEOUT} (→ {@code 504}); a missed type would silently degrade to a {@code 502}.
     */
    private static boolean isTimeout(Throwable cause) {
        return cause instanceof SocketTimeoutException || cause instanceof HttpTimeoutException;
    }
}
