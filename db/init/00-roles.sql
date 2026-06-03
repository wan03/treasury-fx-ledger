-- =============================================================================
-- Role bootstrap — runs ONCE as the cluster superuser during Postgres init
-- (/docker-entrypoint-initdb.d). Shared verbatim by dev (compose mount) and the
-- test suite (Testcontainers copy) so the least-privilege split is identical in
-- every environment (data-model.md §Least privilege, constitution §9).
--
--   migration  -> owns the schema + all DDL; Flyway connects as this role.
--   app        -> DML only; the application connects as this role. It holds NO
--                 table rights here — each versioned migration GRANTs exactly the
--                 DML it needs, so privileges travel with the schema and stay
--                 auditable in one place.
--
-- Prod (Neon) provisions the same two roles out of band (T7.4). These passwords
-- are LOCAL-DEV ONLY and intentionally weak — real secrets come from env.
-- =============================================================================

CREATE ROLE migration WITH LOGIN PASSWORD 'change-me-migration';
CREATE ROLE app       WITH LOGIN PASSWORD 'change-me-app';

-- migration owns the schema so it can create/alter/drop objects freely.
ALTER SCHEMA public OWNER TO migration;
GRANT ALL ON SCHEMA public TO migration;

-- app may connect and resolve names in the schema, nothing more by default.
GRANT CONNECT ON DATABASE currency_ledger TO app;
GRANT USAGE   ON SCHEMA  public           TO app;

-- Defense in depth: PUBLIC keeps no implicit rights on the schema.
REVOKE ALL ON SCHEMA public FROM PUBLIC;
