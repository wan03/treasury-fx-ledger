// =============================================================================
// currency-ledger build (Phase 0). Java 21 + Spring Boot 3.5.x, hexagonal app.
// - Split test suites: fast `test` (pure/slice, no network) vs heavy
//   `integrationTest` (Testcontainers + WireMock) — JVM Test Suite plugin.
// - Quality gates wired but LENIENT to start (T0.2): JaCoCo (floor), PIT
//   (mutation on the money + rate-selection packages), ArchUnit (boundaries).
// Pinned non-BOM versions are verified against the live build.
// =============================================================================

// Flyway 10+ split per-vendor support into separate modules, and the Flyway
// GRADLE plugin resolves them from the BUILDSCRIPT classpath — having
// flyway-database-postgresql on the app's runtime classpath (below) is not
// enough for `make db-migrate` (flywayMigrate), which would otherwise fail with
// "No Flyway database plugin found to handle jdbc:postgresql://…". Pin to the
// plugin's version (11.7.2) so the handler matches flyway-core exactly.
buildscript {
    repositories { mavenCentral() }
    dependencies { classpath("org.flywaydb:flyway-database-postgresql:12.8.1") }
}

plugins {
    java
    id("org.springframework.boot") version "3.5.14"
    id("io.spring.dependency-management") version "1.1.7"
    jacoco
    id("info.solidsoft.pitest") version "1.19.0"
    id("org.flywaydb.flyway") version "12.8.1"  // matches Boot 3.5.14's managed Flyway
    // CycloneDX SBOM (finding #2): `./gradlew cyclonedxBom` → build/reports/bom.json, which the nightly
    // Trivy job scans so the CVE scan enumerates REAL Java deps. Not wired into `check` — PR gate stays fast.
    id("org.cyclonedx.bom") version "1.10.0"
}

group = "com.wex"
version = "0.0.1-SNAPSHOT"
description = "USD purchase ledger with on-demand Treasury currency conversion"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

// Override the Spring Boot BOM's managed JUnit version (see `junitVersion` note above) so the whole
// JUnit Platform — engine, commons, AND launcher — aligns at 1.14.2, satisfying Gradle 8.14.5.
extra["junit-jupiter.version"] = "5.14.2"

repositories {
    mavenCentral()
}

// Versions for dependencies NOT managed by the Spring Boot BOM.
val springdocVersion = "2.8.17"   // latest 2.x line (Boot 3.x); 3.x is for Boot 4
val resilience4jVersion = "2.3.0"
val archunitVersion = "1.4.2"
val jqwikVersion = "1.9.3"         // jqwik engine (property-based); runs on the JUnit Platform below
// JUnit alignment. Gradle 8.14.5's test worker hard-requires junit-platform-launcher 1.14.x
// (its processor calls OutputDirectoryCreator, added in platform 1.13). Spring Boot 3.5.14's BOM
// force-pins junit-platform-engine/commons to 1.12.2, so the launcher (1.14) and engine (1.12)
// split-brain and the worker dies with NoClassDefFoundError: OutputDirectoryCreator. We resolve
// it by aligning the WHOLE platform UP to 5.14.2 — overriding the Boot-managed `junit-jupiter`
// property (below) so engine+commons+launcher all land on 1.14.2. (5.14 is a backward-compatible
// minor over Boot's 5.12; jqwik 1.9.3 runs on it via the stable TestEngine SPI.)
val junitVersion = "5.14.2"
val wiremockVersion = "3.13.2"     // latest stable 3.x; 4.x is beta

dependencies {
    // --- web / validation / persistence ---
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-cache")

    // --- server-generated, time-ordered ids (UUIDv7, RFC 9562; D-08). Java 21 has no
    //     native UUIDv7, so use FasterXML's generator behind an IdGenerator port. ---
    implementation("com.fasterxml.uuid:java-uuid-generator:5.1.0")

    // --- migrations (Postgres) ---
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // --- rate provider A cache + Treasury resilience (D-03 / constitution §7) ---
    implementation("com.github.ben-manes.caffeine:caffeine") // version via Boot BOM
    implementation("io.github.resilience4j:resilience4j-spring-boot3:$resilience4jVersion")
    // Resilience4j↔Micrometer binders (finding #4): publish breaker/retry/bulkhead meters. Pinned
    // explicitly so the TaggedXxxMetrics types are on OUR compile classpath, not just transitively.
    implementation("io.github.resilience4j:resilience4j-micrometer:$resilience4jVersion")

    // --- observability: Prometheus scrape (#4) + W3C tracing/correlation (#5), versions via Boot BOM ---
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("io.micrometer:micrometer-tracing-bridge-otel")

    // --- OpenAPI: serve the authored contract via Swagger UI (D-09) ---
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:$springdocVersion")

    // --- dev: one-command Postgres on bootRun ---
    developmentOnly("org.springframework.boot:spring-boot-docker-compose")

    // --- fast unit/slice tests ---
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("net.jqwik:jqwik:$jqwikVersion")               // property-based
    testImplementation("com.tngtech.archunit:archunit-junit5:$archunitVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// --- split test suites (Gradle JVM Test Suite plugin) -----------------------
testing {
    suites {
        val test by getting(JvmTestSuite::class) {
            useJUnitJupiter(junitVersion)
        }
        val integrationTest by registering(JvmTestSuite::class) {
            useJUnitJupiter(junitVersion)
            dependencies {
                // NOTE: the main classpath is wired in below via `extendsFrom` + the main
                // source-set output (NOT `implementation(project())`). We disable the plain
                // `jar` task, so the project()-as-artifact path has no jar to resolve; binding
                // to the source-set output also gives @SpringBootTest the *full* main runtime
                // classpath (web/data-jdbc/flyway/driver), which a bare project() dep would not.
                implementation("org.springframework.boot:spring-boot-starter-test")
                implementation("org.springframework.boot:spring-boot-testcontainers")
                implementation("org.testcontainers:junit-jupiter")
                implementation("org.testcontainers:postgresql")
                implementation("org.wiremock:wiremock-standalone:$wiremockVersion")
            }
            targets {
                all {
                    testTask.configure {
                        shouldRunAfter(test)
                        // Constitution §10: the gating suite makes ZERO real network calls. The live
                        // Treasury canary (@Tag("live"), T6.2) is therefore excluded by default and
                        // opted in explicitly with `-Plive` (nightly/manual), so it never gates a PR.
                        if (!project.hasProperty("live")) {
                            options {
                                (this as JUnitPlatformOptions).excludeTags("live")
                            }
                        }
                    }
                }
            }
        }
    }
}

// integrationTest sees the whole main module — compiled classes (via the source-set output,
// bypassing the disabled plain jar) plus every dependency on main's compile/runtime classpath.
configurations {
    named("integrationTestImplementation") { extendsFrom(configurations.implementation.get()) }
    named("integrationTestRuntimeOnly") { extendsFrom(configurations.runtimeOnly.get()) }
}
dependencies {
    "integrationTestImplementation"(sourceSets.main.get().output)
}
// NOTE: `integrationTest` is intentionally NOT wired into `check`/`build`, so the
// default build stays fast and needs no container runtime. CI runs it explicitly
// (`make integration`); locally it uses Testcontainers over Podman.

// --- only the executable boot jar (no -plain.jar) ---------------------------
tasks.named<Jar>("jar") {
    enabled = false
}

// The CycloneDX plugin links `cyclonedxBom` into `processResources` (to embed the SBOM in the jar),
// which would pull SBOM generation into the fast PR gate. Keep SCA strictly nightly (finding #2):
// make the task a no-op UNLESS it is explicitly on the command line, so it stays in the graph but
// does zero work during `check`/`test`/`bootJar` — only `./gradlew cyclonedxBom` (the nightly job)
// actually generates build/reports/application.cdx.json.
tasks.named("cyclonedxBom") {
    onlyIf { gradle.startParameter.taskNames.any { it.substringAfterLast(":") == "cyclonedxBom" } }
}

// --- JaCoCo: a floor/guardrail, NOT the quality target (mutation is) --------
jacoco {
    toolVersion = "0.8.12"
}
tasks.named<Test>("test") {
    finalizedBy(tasks.named("jacocoTestReport"))
}
tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn(tasks.named("test"))
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

// The floor is enforced ONLY on the framework-free core (`domain.*` + `application.*`), which the
// fast `test` suite exercises exhaustively (measured 85–100% per package). The adapters, config and
// bootstrap class are deliberately covered by the heavy `integrationTest` suite (Testcontainers +
// WireMock) instead, so they read near-zero in THIS report — including them would force a meaningless
// ~30% floor. Mutation testing (PIT, above) is the real assertion-quality gate; this is the guardrail.
tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    dependsOn(tasks.named("jacocoTestReport"))
    classDirectories.setFrom(
        files(classDirectories.files.map { dir ->
            fileTree(dir) { include("com/wex/fx/domain/**", "com/wex/fx/application/**") }
        })
    )
    violationRules {
        rule {
            element = "BUNDLE"
            limit {
                counter = "INSTRUCTION"
                value = "COVEREDRATIO"
                minimum = "0.85".toBigDecimal()
            }
        }
    }
}
// Wire the floor into the fast gating build so a PR fails on a core-coverage regression.
tasks.named("check") {
    dependsOn(tasks.named("jacocoTestCoverageVerification"))
}

// --- PIT mutation testing: assertion strength on the crown-jewel packages ---
// Run on demand / nightly (not wired into `check` to keep PRs fast).
pitest {
    targetClasses.set(setOf("com.wex.fx.domain.*"))
    targetTests.set(setOf("com.wex.fx.domain.*"))
    junit5PluginVersion.set("1.2.3")
    pitestVersion.set("1.25.3")
    threads.set(4)
    timestampedReports.set(false)
    // T6.3: raised off the lenient 0 now the domain has landed. Measured 92% mutation / 93% test
    // strength on `com.wex.fx.domain.*` (83 mutations, 76 killed); 85 keeps a safe margin against
    // the few equivalent/empty-return survivors while still failing the build on a real regression.
    mutationThreshold.set(85)
}

// --- Flyway CLI (`make db-migrate`) — reads env / .env ----------------------
flyway {
    url = System.getenv("DATABASE_URL") ?: "jdbc:postgresql://localhost:5432/currency_ledger"
    user = System.getenv("DB_MIGRATION_USERNAME") ?: System.getenv("DATABASE_USERNAME") ?: "app"
    password = System.getenv("DB_MIGRATION_PASSWORD") ?: System.getenv("DATABASE_PASSWORD") ?: "change-me-app"
    locations = arrayOf("filesystem:src/main/resources/db/migration")
}
