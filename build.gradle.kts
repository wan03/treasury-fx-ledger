// =============================================================================
// currency-ledger build (Phase 0). Java 21 + Spring Boot 3.5.x, hexagonal app.
// - Split test suites: fast `test` (pure/slice, no network) vs heavy
//   `integrationTest` (Testcontainers + WireMock) — JVM Test Suite plugin.
// - Quality gates wired but LENIENT to start (T0.2): JaCoCo (floor), PIT
//   (mutation on the money + rate-selection packages), ArchUnit (boundaries).
// Pinned non-BOM versions are verified against the live build.
// =============================================================================
plugins {
    java
    id("org.springframework.boot") version "3.5.14"
    id("io.spring.dependency-management") version "1.1.7"
    jacoco
    id("info.solidsoft.pitest") version "1.19.0"
    id("org.flywaydb.flyway") version "11.7.2"  // matches Boot 3.5.14's managed Flyway
}

group = "com.wex"
version = "0.0.1-SNAPSHOT"
description = "USD purchase ledger with on-demand Treasury currency conversion"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

// Versions for dependencies NOT managed by the Spring Boot BOM.
val springdocVersion = "2.8.17"   // latest 2.x line (Boot 3.x); 3.x is for Boot 4
val resilience4jVersion = "2.3.0"
val archunitVersion = "1.4.2"
val jqwikVersion = "1.9.3"         // aligned to JUnit Platform 1.12 (Boot 3.5.14)
val wiremockVersion = "3.13.2"     // latest stable 3.x; 4.x is beta

dependencies {
    // --- web / validation / persistence ---
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-cache")

    // --- migrations (Postgres) ---
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // --- rate provider A cache + Treasury resilience (D-03 / constitution §7) ---
    implementation("com.github.ben-manes.caffeine:caffeine") // version via Boot BOM
    implementation("io.github.resilience4j:resilience4j-spring-boot3:$resilience4jVersion")

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
            useJUnitJupiter()
        }
        val integrationTest by registering(JvmTestSuite::class) {
            useJUnitJupiter()
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

// --- PIT mutation testing: assertion strength on the crown-jewel packages ---
// Run on demand / nightly (not wired into `check` to keep PRs fast).
pitest {
    targetClasses.set(setOf("com.wex.fx.domain.*"))
    targetTests.set(setOf("com.wex.fx.domain.*"))
    junit5PluginVersion.set("1.2.3")
    pitestVersion.set("1.25.3")
    threads.set(4)
    timestampedReports.set(false)
    mutationThreshold.set(0) // lenient to start (T0.2); raised once the domain lands
}

// --- Flyway CLI (`make db-migrate`) — reads env / .env ----------------------
flyway {
    url = System.getenv("DATABASE_URL") ?: "jdbc:postgresql://localhost:5432/currency_ledger"
    user = System.getenv("DB_MIGRATION_USERNAME") ?: System.getenv("DATABASE_USERNAME") ?: "app"
    password = System.getenv("DB_MIGRATION_PASSWORD") ?: System.getenv("DATABASE_PASSWORD") ?: "change-me-app"
    locations = arrayOf("filesystem:src/main/resources/db/migration")
}
