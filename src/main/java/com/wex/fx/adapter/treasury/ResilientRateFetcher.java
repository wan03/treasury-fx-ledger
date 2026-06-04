package com.wex.fx.adapter.treasury;

import com.wex.fx.application.error.RateProviderUnavailableException;
import com.wex.fx.application.error.RateProviderUnavailableException.Reason;
import com.wex.fx.domain.rate.ExchangeRate;
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
 *   <li>circuit open → fail fast, {@link Reason#CIRCUIT_OPEN} (→ {@code 503});</li>
 *   <li>read timeout → {@link Reason#TIMEOUT} (→ {@code 504});</li>
 *   <li>5xx / 4xx / malformed body → {@link Reason#UPSTREAM_ERROR} (→ {@code 502}).</li>
 * </ul>
 *
 * Retry/breaker policy (which exceptions are transient, which trip the breaker) is configured where the
 * {@code Retry}/{@code CircuitBreaker} are built — {@code RateProviderConfig}.
 */
final class ResilientRateFetcher implements RateFetcher {

    private final RateFetcher delegate;
    private final Retry retry;
    private final CircuitBreaker circuitBreaker;

    ResilientRateFetcher(RateFetcher delegate, Retry retry, CircuitBreaker circuitBreaker) {
        this.delegate = delegate;
        this.retry = retry;
        this.circuitBreaker = circuitBreaker;
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

    /** Apply the retry-over-breaker composition and collapse every failure into one domain signal. */
    private List<ExchangeRate> guarded(Supplier<List<ExchangeRate>> call) {
        Supplier<List<ExchangeRate>> guarded =
                Retry.decorateSupplier(retry, CircuitBreaker.decorateSupplier(circuitBreaker, call));
        try {
            return guarded.get();
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
