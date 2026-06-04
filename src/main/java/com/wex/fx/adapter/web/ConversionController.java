package com.wex.fx.adapter.web;

import com.wex.fx.application.ConvertPurchaseService;
import com.wex.fx.application.dto.ConversionResponse;
import java.time.Duration;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * R2 inbound adapter — the conversion sub-resource
 * {@code GET /v1/purchases/{id}/conversions/{currencyCode}}. A pure projection (never persisted): it
 * resolves the target currency through the curated map and selects the rate active on/before the
 * purchase date, all behind {@link ConvertPurchaseService}. Outcomes map to RFC 9457 in
 * {@link ApiExceptionHandler}: malformed code → 400, unsupported/no-rate → 422, unknown purchase →
 * 404, upstream trouble → 502/503/504.
 *
 * <p>{@code currencyCode} is intentionally <strong>not</strong> regex-constrained in the path mapping:
 * a malformed token must surface as {@code 400 CURRENCY_CODE_MALFORMED} (the domain classifies it),
 * not as a {@code 404} from an unmatched route.
 */
@RestController
class ConversionController {

    /**
     * A past-dated conversion is near-deterministic, so it is cacheable — but the body carries the
     * purchase {@code description} (PII) and recent quarters can still gain an amendment (F8), so:
     * {@code private} (keep PII out of shared caches), a <em>moderate</em> TTL, and explicitly
     * <em>not</em> {@code immutable}. Revalidation rides the ETag (WebConfig's filter).
     */
    private static final CacheControl CONVERSION_CACHE =
            CacheControl.maxAge(Duration.ofHours(1)).cachePrivate();

    private final ConvertPurchaseService conversions;

    ConversionController(ConvertPurchaseService conversions) {
        this.conversions = conversions;
    }

    @GetMapping("/v1/purchases/{id}/conversions/{currencyCode}")
    ResponseEntity<ConversionResponse> convert(
            @PathVariable("id") UUID id, @PathVariable("currencyCode") String currencyCode) {
        ConversionResponse body = conversions.convert(id, currencyCode);
        return ResponseEntity.ok()
                .cacheControl(CONVERSION_CACHE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }
}
