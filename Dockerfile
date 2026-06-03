# =============================================================================
# Multi-stage build (D-12). Render builds this image on its side, so deploying
# needs no local Docker/JDK. Stage 1 compiles a layered Spring Boot jar; stage 2
# runs it on a slim JRE as a non-root user.
# =============================================================================

# ---- Stage 1: build ---------------------------------------------------------
# Fully-qualified registry path: portable across Docker (Render) and rootless
# Podman (local), which enforces unambiguous image names.
FROM docker.io/library/eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace

# Copy the wrapper + build scripts first so dependency resolution caches across
# source-only changes.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies --quiet || true

# Now the sources, then build the executable jar (skip tests — CI gates those).
COPY src ./src
RUN ./gradlew --no-daemon clean bootJar -x test

# Explode the jar into layers for runtime image caching (Boot 3.3+ `tools` jarmode).
# `--launcher` keeps the loader classes exploded under spring-boot-loader/ so the
# runtime stage can launch via JarLauncher (the default omits them, breaking it).
RUN java -Djarmode=tools -jar build/libs/*.jar extract --layers --launcher --destination build/extracted

# ---- Stage 2: runtime -------------------------------------------------------
FROM docker.io/library/eclipse-temurin:21-jre-jammy AS runtime
WORKDIR /app

# Run as an unprivileged user.
RUN groupadd --system spring && useradd --system --gid spring --home-dir /app spring

# Layers ordered least- to most-frequently-changing for cache efficiency.
COPY --from=build /workspace/build/extracted/dependencies/ ./
COPY --from=build /workspace/build/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/build/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/build/extracted/application/ ./

USER spring:spring
EXPOSE 8080

# Container-aware heap sizing; honor Render's $PORT via the app config.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
