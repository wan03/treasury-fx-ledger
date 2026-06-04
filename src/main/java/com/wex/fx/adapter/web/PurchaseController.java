package com.wex.fx.adapter.web;

import com.wex.fx.application.GetPurchaseService;
import com.wex.fx.application.StorePurchaseService;
import com.wex.fx.application.dto.IdempotencyRequest;
import com.wex.fx.application.dto.PurchaseResponse;
import com.wex.fx.application.dto.StoreOutcome;
import com.wex.fx.application.dto.StorePurchaseCommand;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * R1 inbound adapter — the {@code /v1/purchases} resource (create + fetch). Thin by design: it
 * translates HTTP to the use-case ports and owns only web concerns (status, {@code Location}, the
 * idempotency fingerprint, cache headers). All validation, money, and persistence rules live behind
 * {@link StorePurchaseService} / {@link GetPurchaseService}; errors become RFC 9457 in
 * {@link ApiExceptionHandler}.
 *
 * <p><strong>Append-only (D-09):</strong> only {@code POST} and {@code GET} are mapped — no
 * {@code PUT}/{@code PATCH}/{@code DELETE} handler exists, so those verbs get a framework {@code 405}.
 */
@RestController
@RequestMapping("/v1/purchases")
class PurchaseController {

    /**
     * A stored purchase is immutable, but its {@code description} is PII, so we keep it out of shared
     * caches: {@code private} + a short TTL lets the owning client revalidate cheaply via the ETag.
     */
    private static final CacheControl PURCHASE_CACHE =
            CacheControl.maxAge(Duration.ofSeconds(60)).cachePrivate();

    /** Contract cap on the opaque idempotency key (api-contract.md). */
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 255;

    private final StorePurchaseService storePurchases;
    private final GetPurchaseService getPurchases;

    PurchaseController(StorePurchaseService storePurchases, GetPurchaseService getPurchases) {
        this.storePurchases = storePurchases;
        this.getPurchases = getPurchases;
    }

    @PostMapping
    ResponseEntity<PurchaseResponse> create(
            @RequestBody CreatePurchaseRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {

        StorePurchaseCommand command = new StorePurchaseCommand(
                request.description(), request.amount(), request.transactionDate(), request.currency());
        StoreOutcome outcome = storePurchases.store(command, idempotencyOf(command, idempotencyKey));

        PurchaseResponse body = outcome.response();
        ResponseEntity.BodyBuilder builder =
                ResponseEntity.created(URI.create("/v1/purchases/" + body.id()));
        if (outcome.replayed()) {
            // Same status (201) for a replay; the header lets a client tell a fresh create from a retry.
            builder.header("Idempotency-Replayed", "true");
        }
        return builder.body(body);
    }

    @GetMapping("/{id}")
    ResponseEntity<PurchaseResponse> get(@PathVariable("id") UUID id) {
        PurchaseResponse body = getPurchases.get(id);
        // ETag / If-None-Match are added by ShallowEtagHeaderFilter (WebConfig).
        return ResponseEntity.ok()
                .cacheControl(PURCHASE_CACHE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    /**
     * Builds the idempotency input, or {@code null} when the client sent no key (a plain create).
     * The request hash is a SHA-256 over the <em>canonical</em> fields (not the raw bytes), so the
     * same logical request fingerprints identically regardless of JSON whitespace or key order, while
     * a genuinely different payload under the same key still collides to a {@code 409}.
     */
    private static IdempotencyRequest idempotencyOf(StorePurchaseCommand command, String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        if (key.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new MalformedRequestException(
                    "Idempotency-Key must be at most " + MAX_IDEMPOTENCY_KEY_LENGTH + " characters.");
        }
        return new IdempotencyRequest(key, requestHash(command));
    }

    private static String requestHash(StorePurchaseCommand command) {
        // Length-prefixed (netstring-style) encoding: "<len>:<value>" per field. Uniquely decodable,
        // so distinct field tuples can never collide to the same canonical string even though a raw
        // description may contain whatever single delimiter we might otherwise have picked. Null then "".
        String canonical = encode(command.description())
                + encode(command.transactionDate())
                + encode(command.amount())
                + encode(command.currencyOrDefault());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a mandated JDK algorithm; its absence is an unrecoverable environment fault.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String encode(String field) {
        String value = field == null ? "" : field;
        return value.length() + ":" + value;
    }
}
