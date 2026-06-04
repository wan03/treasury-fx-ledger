package com.wex.fx.adapter.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wex.fx.application.dto.PurchaseResponse;
import com.wex.fx.application.error.DuplicateIdempotencyKeyException;
import com.wex.fx.application.port.IdempotencyStore;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC adapter for {@link IdempotencyStore}. Uses {@link NamedParameterJdbcTemplate} (not Spring Data
 * mapping) for direct control over the {@code jsonb} body cast and, crucially, to translate the
 * primary-key unique violation into a domain-level {@link DuplicateIdempotencyKeyException} — keeping
 * Spring's {@code DuplicateKeyException} from leaking into the application's race-handling logic.
 *
 * <p>The replayable body is serialized with the Spring-managed {@link ObjectMapper}, the same one the
 * web layer uses, so a replay is byte-faithful to the original {@code 201}.
 */
@Repository
class JdbcIdempotencyStore implements IdempotencyStore {

    private static final String INSERT =
            """
            INSERT INTO idempotency_keys
                (key, request_hash, purchase_id, response_status, response_body, expires_at)
            VALUES
                (:key, :hash, :purchaseId, :status, CAST(:body AS jsonb), :expiresAt)
            """;

    private static final String SELECT =
            "SELECT request_hash, response_status, response_body FROM idempotency_keys WHERE key = :key";

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    JdbcIdempotencyStore(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<StoredResponse> find(String key) {
        var params = new MapSqlParameterSource("key", key);
        return jdbc.query(SELECT, params, (rs, rowNum) -> new StoredResponse(
                        rs.getString("request_hash"),
                        rs.getInt("response_status"),
                        deserialize(rs.getString("response_body"))))
                .stream()
                .findFirst();
    }

    @Override
    public void save(
            String key,
            String requestHash,
            UUID purchaseId,
            int responseStatus,
            PurchaseResponse responseBody,
            Instant expiresAt) {
        var params = new MapSqlParameterSource()
                .addValue("key", key)
                .addValue("hash", requestHash)
                .addValue("purchaseId", purchaseId)
                .addValue("status", responseStatus)
                .addValue("body", serialize(responseBody))
                .addValue("expiresAt", OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC));
        try {
            jdbc.update(INSERT, params);
        } catch (DuplicateKeyException e) {
            // A concurrent request already committed this key — surface as a domain signal.
            throw new DuplicateIdempotencyKeyException(key, e);
        }
    }

    private String serialize(PurchaseResponse body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize idempotency response body", e);
        }
    }

    private PurchaseResponse deserialize(String json) {
        try {
            return objectMapper.readValue(json, PurchaseResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to read stored idempotency response body", e);
        }
    }
}
