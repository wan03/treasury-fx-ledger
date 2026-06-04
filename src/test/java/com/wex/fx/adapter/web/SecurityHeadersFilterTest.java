package com.wex.fx.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Direct unit test for {@link WebConfig.SecurityHeadersFilter} (constitution §9). The filter is the one
 * place that tailors {@code Content-Security-Policy} per surface, so it gets its own focused test rather
 * than only being exercised transitively through a controller slice: the {@code /v1} data plane must get
 * the strictest policy, the explorer HTML page ({@code /} and {@code /explore.html}) a narrowly-relaxed
 * one, and everything else (e.g. Swagger UI) no CSP at all — while the baseline transport/anti-framing
 * headers are present unconditionally on every response.
 */
class SecurityHeadersFilterTest {

    private final WebConfig.SecurityHeadersFilter filter = new WebConfig.SecurityHeadersFilter();

    private MockHttpServletResponse filter(String uri) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRequestURI(uri);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = new MockFilterChain();
        filter.doFilterInternal(request, response, chain);
        return response;
    }

    @Test
    void baselineHeaders_areSetOnEveryResponse() throws Exception {
        for (String uri : new String[] {"/v1/purchases/x", "/", "/explore.html", "/swagger-ui/index.html"}) {
            MockHttpServletResponse response = filter(uri);
            assertThat(response.getHeader("Strict-Transport-Security"))
                    .as("HSTS on %s", uri)
                    .isEqualTo("max-age=31536000; includeSubDomains");
            assertThat(response.getHeader("X-Content-Type-Options")).as("nosniff on %s", uri).isEqualTo("nosniff");
            assertThat(response.getHeader("X-Frame-Options")).as("frame on %s", uri).isEqualTo("DENY");
            assertThat(response.getHeader("Referrer-Policy")).as("referrer on %s", uri).isEqualTo("no-referrer");
        }
    }

    @Test
    void dataPlane_getsStrictCsp_thatLoadsAndFramesNothing() throws Exception {
        String csp = filter("/v1/purchases/x").getHeader("Content-Security-Policy");

        assertThat(csp).isEqualTo("default-src 'none'; frame-ancestors 'none'");
        // The strict policy must never relax to inline execution — that is the explorer page's concession.
        assertThat(csp).doesNotContain("unsafe-inline");
    }

    @Test
    void explorerPage_getsRelaxedCsp_onRootAndOnExplicitPath() throws Exception {
        for (String uri : new String[] {"/", "/explore.html"}) {
            String csp = filter(uri).getHeader("Content-Security-Policy");

            assertThat(csp).as("CSP present on %s", uri).isNotNull();
            // A real HTML document: inline script + style, and a same-origin fetch to the API.
            assertThat(csp)
                    .as("relaxed CSP on %s", uri)
                    .contains("default-src 'none'")
                    .contains("script-src 'self' 'unsafe-inline'")
                    .contains("style-src 'self' 'unsafe-inline'")
                    .contains("connect-src 'self'")
                    .contains("img-src 'self' data:")
                    .contains("frame-ancestors 'none'");
            // Still no remote origins and no framing: relaxed for *this* page, not for the world.
            assertThat(csp).doesNotContain("http://").doesNotContain("https://").doesNotContain("*");
        }
    }

    @Test
    void otherPaths_getNoCsp_soBundledAssetsLoad() throws Exception {
        // Swagger UI (dev-only) ships its own scripts/styles; a CSP header here would break it.
        assertThat(filter("/swagger-ui/index.html").getHeader("Content-Security-Policy")).isNull();
        assertThat(filter("/v3/api-docs").getHeader("Content-Security-Policy")).isNull();
    }
}
