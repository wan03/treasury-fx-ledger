package com.wex.fx;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Base for Postgres-backed integration tests. Boots one prod-parity Postgres 16
 * container for the whole suite (singleton pattern — started once, reused, torn
 * down with the JVM) and exercises the real least-privilege role split:
 *
 * <ul>
 *   <li>{@code db/init/00-roles.sql} (the same script dev compose mounts) creates
 *       the {@code migration} and {@code app} roles on container init;</li>
 *   <li>Flyway connects as {@code migration} (owns DDL);</li>
 *   <li>the application datasource connects as {@code app} (DML only).</li>
 * </ul>
 *
 * Subclasses therefore test against exactly the privilege boundary that runs in
 * prod — including that the {@code app} role is denied DDL/UPDATE/DELETE.
 */
@SpringBootTest
@ActiveProfiles("test")
abstract class AbstractPostgresIT {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(
                    DockerImageName.parse("docker.io/library/postgres:16")
                            .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("currency_ledger")
                    .withUsername("postgres")   // throwaway bootstrap superuser; real roles come from db/init
                    .withPassword("postgres")
                    .withCopyFileToContainer(
                            MountableFile.forHostPath("db/init/00-roles.sql"),
                            "/docker-entrypoint-initdb.d/00-roles.sql");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        // Application → least-privilege `app` role (DML only).
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "app");
        registry.add("spring.datasource.password", () -> "change-me-app");
        // Flyway → `migration` role (owns DDL), on its own connection.
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", () -> "migration");
        registry.add("spring.flyway.password", () -> "change-me-migration");
    }
}
