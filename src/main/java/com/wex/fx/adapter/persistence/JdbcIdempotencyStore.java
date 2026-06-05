package com.wex.fx.adapter.persistence;

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
 * mapping) to translate the composite-key unique violation into a domain-level
 * {@link DuplicateIdempotencyKeyException} — keeping Spring's {@code DuplicateKeyException} out of the
 * application's race-handling logic.
 *
 * <p>Rows are keyed by {@code (principal, key)} (finding #6) and store no response body (finding #8):
 * the replay body is re-projected from the referenced {@code purchases} row, so the only PII copy is in
 * the ledger itself.
 */
@Repository
class JdbcIdempotencyStore implements IdempotencyStore {

    private static final String INSERT =
            """
            INSERT INTO idempotency_keys
                (principal, key, request_hash, purchase_id, response_status, expires_at)
            VALUES
                (:principal, :key, :hash, :purchaseId, :status, :expiresAt)
            """;

    private static final String SELECT =
            """
            SELECT request_hash, response_status, purchase_id
            FROM idempotency_keys
            WHERE principal = :principal AND key = :key
            """;

    // Bounded delete: the inner SELECT picks at most :limit expired ctids, so each statement takes a
    // short lock window instead of one unbounded DELETE holding locks across the whole expired backlog.
    private static final String DELETE_EXPIRED =
            """
            DELETE FROM idempotency_keys
            WHERE ctid IN (
                SELECT ctid FROM idempotency_keys WHERE expires_at < :now LIMIT :limit
            )
            """;

    private final NamedParameterJdbcTemplate jdbc;

    JdbcIdempotencyStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<StoredResponse> find(String principal, String key) {
        var params = new MapSqlParameterSource()
                .addValue("principal", principal)
                .addValue("key", key);
        return jdbc.query(SELECT, params, (rs, rowNum) -> new StoredResponse(
                        rs.getString("request_hash"),
                        rs.getInt("response_status"),
                        rs.getObject("purchase_id", UUID.class)))
                .stream()
                .findFirst();
    }

    @Override
    public void save(
            String principal,
            String key,
            String requestHash,
            UUID purchaseId,
            int responseStatus,
            Instant expiresAt) {
        var params = new MapSqlParameterSource()
                .addValue("principal", principal)
                .addValue("key", key)
                .addValue("hash", requestHash)
                .addValue("purchaseId", purchaseId)
                .addValue("status", responseStatus)
                .addValue("expiresAt", OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC));
        try {
            jdbc.update(INSERT, params);
        } catch (DuplicateKeyException e) {
            // A concurrent request already committed this (principal, key) — surface as a domain signal.
            throw new DuplicateIdempotencyKeyException(key, e);
        }
    }

    @Override
    public int deleteExpired(Instant now, int batchLimit) {
        var params = new MapSqlParameterSource()
                .addValue("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .addValue("limit", batchLimit);
        return jdbc.update(DELETE_EXPIRED, params);
    }
}
