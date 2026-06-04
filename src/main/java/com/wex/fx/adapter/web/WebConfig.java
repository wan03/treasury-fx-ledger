package com.wex.fx.adapter.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.filter.ShallowEtagHeaderFilter;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web-layer hygiene that is cross-cutting rather than per-endpoint (constitution §5/§9, api-contract.md
 * CC-2): conditional-GET support, browser security headers, and a deny-by-default CORS posture. Wiring
 * only — no business logic — so it lives beside the controllers in the inbound adapter.
 *
 * <p><strong>What is handled elsewhere.</strong> Cache-Control TTLs are set per-resource on the
 * controllers (they know the freshness story for each body). Request-body size and header caps are
 * connector-level limits in {@code application.yml} (Tomcat). Logs already exclude amounts/PII — the
 * single log site is {@link ApiExceptionHandler}, which emits only {@code code}/{@code traceId}/path.
 */
@Configuration
class WebConfig implements WebMvcConfigurer {

    /** ETag/{@code If-None-Match} → {@code 304} only for the read endpoints; POST is never cached. */
    private static final String PURCHASES_PATH = "/v1/purchases/*";

    /**
     * Adds an {@code ETag} to GET responses and answers a matching {@code If-None-Match} with a bodiless
     * {@code 304} — cheap revalidation for the immutable purchase and the near-deterministic conversion,
     * which pairs with the controllers' {@code private}, moderate-TTL {@code Cache-Control}. Scoped to the
     * resource paths so it never buffers an unrelated response. Runs <em>inside</em> the security-headers
     * filter so a {@code 304} still carries them.
     */
    @Bean
    FilterRegistrationBean<ShallowEtagHeaderFilter> etagFilter() {
        FilterRegistrationBean<ShallowEtagHeaderFilter> reg =
                new FilterRegistrationBean<>(new ShallowEtagHeaderFilter());
        reg.addUrlPatterns(PURCHASES_PATH);
        reg.setName("etagFilter");
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return reg;
    }

    /**
     * Baseline security headers on every response (constitution §9). Set unconditionally because they are
     * inert when irrelevant: HSTS is ignored by browsers over plain HTTP, and the anti-framing / sniffing
     * headers cost nothing on a JSON body. The {@code Content-Security-Policy} is tailored per surface (see
     * the filter): strictest on the {@code /v1} data plane, an all-{@code 'self'} policy for the explorer
     * HTML page (its script/style/icon are sibling same-origin files — no {@code 'unsafe-inline'}), and
     * absent on the dev-only Swagger UI so it can load its own bundled assets.
     */
    @Bean
    FilterRegistrationBean<SecurityHeadersFilter> securityHeadersFilter() {
        FilterRegistrationBean<SecurityHeadersFilter> reg =
                new FilterRegistrationBean<>(new SecurityHeadersFilter());
        reg.addUrlPatterns("/*");
        reg.setName("securityHeadersFilter");
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return reg;
    }

    /**
     * Deny-by-default CORS: registering no mappings means no {@code Access-Control-Allow-*} headers are
     * ever emitted, so a browser blocks every cross-origin call. The method is here, explicit and empty,
     * to make that posture a documented decision rather than an accident — relaxing it later is a
     * one-method change reviewed in one place.
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // Intentionally empty — see Javadoc. No origins are permitted.
    }

    /**
     * Makes the interactive explorer the application's front door: a bare {@code GET /} forwards to the
     * self-contained {@code /explore.html} static page (the recommended way to tour the codebase and
     * exercise the live API same-origin). A forward — not a redirect — keeps the client URL clean and
     * serves the page in one round-trip. The data plane stays untouched: every API route lives under
     * {@code /v1} and is matched by controllers, which take precedence over this view controller.
     */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/").setViewName("forward:/explore.html");
    }

    /**
     * Sets the response security headers. Stateless and thread-safe, so a single instance serves every
     * request. Written as a {@link OncePerRequestFilter} (not a {@code WebMvcConfigurer} interceptor) so
     * the headers are present even on responses produced outside the handler chain — e.g. a container-level
     * error or a {@code 304} short-circuit.
     */
    static final class SecurityHeadersFilter extends OncePerRequestFilter {

        @Override
        protected void doFilterInternal(
                HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            // Force HTTPS for a year, subdomains included — the API is TLS-only (constitution §9).
            response.setHeader(
                    "Strict-Transport-Security", "max-age=31536000; includeSubDomains");
            // Defeat MIME sniffing and clickjacking; a JSON API is never legitimately framed.
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setHeader("X-Frame-Options", "DENY");
            // Never leak a URL (which could carry an id) to another origin via the Referer header.
            response.setHeader("Referrer-Policy", "no-referrer");
            // Content-Security-Policy is tailored per surface:
            //   • /v1 data plane — loads and frames nothing, so the strictest possible policy.
            //   • the explorer page ("/" forwards to it, and "/explore.html" direct) — a real HTML
            //     document whose script, style and icon are all served same-origin from sibling files
            //     (explore.js / explore.css / favicon.svg). It needs 'self' for exactly those plus a
            //     same-origin fetch to the API — and nothing more: no 'unsafe-inline', no remote
            //     origins, no data: URIs, no framing. This is why the page was de-inlined (D-13).
            //   • Swagger UI and other paths — no CSP header, so its bundled assets still load in dev.
            String uri = request.getRequestURI();
            if (uri.startsWith("/v1/")) {
                response.setHeader(
                        "Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
            } else if (uri.equals("/") || uri.equals("/explore.html")) {
                response.setHeader(
                        "Content-Security-Policy",
                        "default-src 'none'; script-src 'self'; style-src 'self'; "
                                + "connect-src 'self'; img-src 'self'; base-uri 'none'; "
                                + "form-action 'none'; frame-ancestors 'none'");
            }
            chain.doFilter(request, response);
        }
    }
}
