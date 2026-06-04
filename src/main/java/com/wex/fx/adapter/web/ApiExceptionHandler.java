package com.wex.fx.adapter.web;

import com.wex.fx.application.error.CurrencyNotStorableException;
import com.wex.fx.application.error.IdempotencyConflictException;
import com.wex.fx.application.error.MalformedCurrencyException;
import com.wex.fx.application.error.NoRateAvailableException;
import com.wex.fx.application.error.PurchaseNotFoundException;
import com.wex.fx.application.error.RateProviderUnavailableException;
import com.wex.fx.application.error.UnsupportedCurrencyException;
import com.wex.fx.domain.validation.ValidationException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Single translation point from exceptions to RFC 9457 {@code application/problem+json} (D-09,
 * api-contract.md). Two tiers share one body shape:
 *
 * <ul>
 *   <li><strong>Domain / application</strong> failures (validation, not-found, currency, no-rate,
 *       upstream outage) map to explicit handlers with a stable machine {@code code}, a {@code traceId},
 *       and — for validation — a field-level {@code errors[]} array.</li>
 *   <li><strong>Protocol</strong> failures handled by {@link ResponseEntityExceptionHandler} (unmapped
 *       verb → 405, wrong media type → 415/406, unreadable body → 400, unknown route → 404) are
 *       enriched in {@link #handleExceptionInternal} so they too carry {@code code} + {@code traceId}.
 *       A 405 here is how append-only (D-09) shows up on the wire: no {@code PUT}/{@code PATCH}/{@code
 *       DELETE} handler exists to match.</li>
 * </ul>
 *
 * <p><strong>Status discipline:</strong> {@code 400} = malformed/can't-process-as-written;
 * {@code 422} = well-formed but unfulfillable (unsupported currency, no rate). A successful "no rate in
 * the window" ({@link NoRateAvailableException} → 422) is never conflated with an upstream
 * <em>outage</em> ({@link RateProviderUnavailableException} → 502/503/504).
 *
 * <p><strong>No leaks (constitution §5/§9):</strong> a {@code detail} states the rule and at most echoes
 * what the caller already sent (a currency code, a date) — never an amount, the {@code description}, or
 * an idempotency key. Stack traces stay server-side; logs carry only {@code code}/{@code traceId}/path.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private static final String TYPE_BASE = "https://api.example.com/problems/";
    /** Mirrors the breaker's open-state wait (application.yml) so a 503 advertises an honest backoff. */
    private static final String RETRY_AFTER_SECONDS = "30";

    // --- domain / application exceptions ---------------------------------------------------------

    @ExceptionHandler(ValidationException.class)
    ResponseEntity<ProblemDetail> handleValidation(ValidationException ex, HttpServletRequest req) {
        List<ApiFieldError> errors = ex.errors().stream()
                .map(e -> new ApiFieldError(e.field(), e.code().name(), e.message()))
                .toList();
        // Top-level code mirrors the first failure so single-field rejections read naturally; the full
        // set is always in errors[].
        String primaryCode = ex.errors().get(0).code().name();
        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, primaryCode, "Validation failed",
                "One or more fields failed validation.", req, ex);
        pd.setProperty("errors", errors);
        return respond(pd);
    }

    @ExceptionHandler(MalformedRequestException.class)
    ResponseEntity<ProblemDetail> handleMalformedRequest(MalformedRequestException ex, HttpServletRequest req) {
        return respond(problem(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Malformed request",
                ex.getMessage(), req, ex));
    }

    @ExceptionHandler(MalformedCurrencyException.class)
    ResponseEntity<ProblemDetail> handleMalformedCurrency(MalformedCurrencyException ex, HttpServletRequest req) {
        return respond(problem(HttpStatus.BAD_REQUEST, "CURRENCY_CODE_MALFORMED", "Malformed currency code",
                "Target currency code must be three uppercase letters (^[A-Z]{3}$).", req, ex));
    }

    @ExceptionHandler(PurchaseNotFoundException.class)
    ResponseEntity<ProblemDetail> handleNotFound(PurchaseNotFoundException ex, HttpServletRequest req) {
        return respond(problem(HttpStatus.NOT_FOUND, "PURCHASE_NOT_FOUND", "Purchase not found",
                "No purchase exists with the given id.", req, ex));
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    ResponseEntity<ProblemDetail> handleIdempotencyConflict(IdempotencyConflictException ex, HttpServletRequest req) {
        // Never echo the key — it is opaque and could carry caller PII.
        return respond(problem(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "Idempotency key reused",
                "The Idempotency-Key was already used with a different request payload.", req, ex));
    }

    @ExceptionHandler(CurrencyNotStorableException.class)
    ResponseEntity<ProblemDetail> handleNotStorable(CurrencyNotStorableException ex, HttpServletRequest req) {
        return respond(problem(HttpStatus.UNPROCESSABLE_ENTITY, "CURRENCY_NOT_STORABLE",
                "Currency not storable",
                "Only USD purchases can be stored; received '" + ex.currencyCode() + "'.", req, ex));
    }

    @ExceptionHandler(UnsupportedCurrencyException.class)
    ResponseEntity<ProblemDetail> handleUnsupported(UnsupportedCurrencyException ex, HttpServletRequest req) {
        return respond(problem(HttpStatus.UNPROCESSABLE_ENTITY, "CURRENCY_UNSUPPORTED",
                "Currency not supported",
                "Currency '" + ex.currencyCode() + "' is not supported for conversion.", req, ex));
    }

    @ExceptionHandler(NoRateAvailableException.class)
    ResponseEntity<ProblemDetail> handleNoRate(NoRateAvailableException ex, HttpServletRequest req) {
        // AC-2.4: name the currency pair and date (all non-PII).
        String detail = "No exchange rate available to convert USD to " + ex.targetCurrency()
                + " on or before " + ex.purchaseDate() + " within the prior 6 months.";
        return respond(problem(HttpStatus.UNPROCESSABLE_ENTITY, "NO_RATE_AVAILABLE",
                "No exchange rate available", detail, req, ex));
    }

    @ExceptionHandler(RateProviderUnavailableException.class)
    ResponseEntity<ProblemDetail> handleUpstream(RateProviderUnavailableException ex, HttpServletRequest req) {
        UpstreamMapping m = switch (ex.reason()) {
            case UPSTREAM_ERROR -> new UpstreamMapping(HttpStatus.BAD_GATEWAY, "UPSTREAM_BAD_GATEWAY");
            case TIMEOUT -> new UpstreamMapping(HttpStatus.GATEWAY_TIMEOUT, "UPSTREAM_TIMEOUT");
            case CIRCUIT_OPEN -> new UpstreamMapping(HttpStatus.SERVICE_UNAVAILABLE, "UPSTREAM_UNAVAILABLE");
        };
        ProblemDetail pd = problem(m.status(), m.code(), "Exchange-rate provider unavailable",
                "The exchange-rate provider is temporarily unavailable. Please retry later.", req, ex);
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(m.status())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON);
        if (m.status() == HttpStatus.SERVICE_UNAVAILABLE) {
            // Circuit open: tell the caller roughly when to come back (matches the open-state window).
            builder.header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);
        }
        return builder.body(pd);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ProblemDetail> handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest req) {
        // The only typed path variable is the purchase id (UUID); an unparseable id is "no such
        // purchase", not a protocol error → 404, consistent with the GET contract.
        if ("id".equals(ex.getName())) {
            return respond(problem(HttpStatus.NOT_FOUND, "PURCHASE_NOT_FOUND", "Purchase not found",
                    "No purchase exists with the given id.", req, ex));
        }
        return respond(problem(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Malformed request",
                "A path parameter could not be parsed.", req, ex));
    }

    /** Last resort: an unexpected fault never leaks internals — a generic 500 with a correlatable id. */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, HttpServletRequest req) {
        return respond(problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL", "Internal server error",
                "An unexpected error occurred.", req, ex));
    }

    // --- protocol exceptions handled by the framework superclass ---------------------------------

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail pd) {
            String code = frameworkCode(statusCode);
            String traceId = newTraceId();
            pd.setProperty("code", code);
            pd.setProperty("traceId", traceId);
            if (pd.getType() == null || "about:blank".equals(String.valueOf(pd.getType()))) {
                pd.setType(URI.create(TYPE_BASE + slug(code)));
            }
            if (pd.getInstance() == null) {
                pd.setInstance(URI.create(requestPath(request)));
            }
            logProblem(statusCode, code, traceId, requestMethod(request), requestPath(request), ex);
        }
        return response;
    }

    // --- helpers ---------------------------------------------------------------------------------

    private ProblemDetail problem(HttpStatus status, String code, String title, String detail,
            HttpServletRequest req, Exception ex) {
        String traceId = newTraceId();
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setTitle(title);
        pd.setType(URI.create(TYPE_BASE + slug(code)));
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", code);
        pd.setProperty("traceId", traceId);
        logProblem(status, code, traceId, req.getMethod(), req.getRequestURI(), ex);
        return pd;
    }

    private static ResponseEntity<ProblemDetail> respond(ProblemDetail pd) {
        return ResponseEntity.status(pd.getStatus())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(pd);
    }

    /** Server-side audit line — code/traceId/path only; never the body, an amount, or PII. */
    private static void logProblem(HttpStatusCode status, String code, String traceId,
            String method, String path, Exception ex) {
        if (status.is5xxServerError()) {
            // Keep the stack server-side so we can diagnose without leaking it to the client.
            log.error("API error status={} code={} traceId={} method={} path={}",
                    status.value(), code, traceId, method, path, ex);
        } else {
            log.warn("API error status={} code={} traceId={} method={} path={}",
                    status.value(), code, traceId, method, path);
        }
    }

    private static String slug(String code) {
        return code.toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static String newTraceId() {
        return UUID.randomUUID().toString();
    }

    private static String frameworkCode(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> "MALFORMED_REQUEST";
            case 404 -> "NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED";
            case 406 -> "NOT_ACCEPTABLE";
            case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            default -> "INTERNAL";
        };
    }

    private static String requestPath(WebRequest request) {
        return request instanceof ServletWebRequest swr ? swr.getRequest().getRequestURI() : "";
    }

    private static String requestMethod(WebRequest request) {
        return request instanceof ServletWebRequest swr ? swr.getRequest().getMethod() : "";
    }

    /** Field-level validation failure as it appears in the problem {@code errors[]} array. */
    private record ApiFieldError(String field, String code, String message) {}

    private record UpstreamMapping(HttpStatus status, String code) {}
}
