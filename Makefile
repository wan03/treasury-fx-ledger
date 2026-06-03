# =============================================================================
# Developer entry points (AGENTS.md). Thin wrappers over the Gradle wrapper so a
# reviewer can drive everything without learning the build. Java toolchain is
# pinned via the wrapper; tests use Testcontainers over Podman (no Docker daemon).
# =============================================================================
GRADLE := ./gradlew

# Testcontainers → Podman (rootless): point at the user socket, skip Ryuk.
PODMAN_SOCK ?= $(XDG_RUNTIME_DIR)/podman/podman.sock
TC_ENV := DOCKER_HOST=unix://$(PODMAN_SOCK) TESTCONTAINERS_RYUK_DISABLED=true

.PHONY: help dev test integration build db-migrate clean openapi podman-up

help:           ## List targets
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | awk 'BEGIN{FS=":.*?## "}{printf "  \033[36m%-14s\033[0m %s\n",$$1,$$2}'

dev:            ## Run locally (dev profile); docker-compose auto-starts Postgres; Swagger UI up
	SPRING_PROFILES_ACTIVE=dev $(GRADLE) bootRun

test:           ## Fast unit + slice tests (no live network, no containers)
	$(GRADLE) test

integration:    ## Testcontainers + WireMock integration/E2E (needs Podman socket)
	$(TC_ENV) $(GRADLE) integrationTest

build:          ## Production jar + all gates
	$(GRADLE) clean build

db-migrate:     ## Apply Flyway migrations (uses .env / env vars)
	$(GRADLE) flywayMigrate

openapi:        ## Print where the authored contract lives
	@echo "Contract: src/main/resources/static/openapi.yaml  (Swagger UI: /swagger-ui.html)"

podman-up:      ## Enable the rootless Podman socket for Testcontainers
	systemctl --user enable --now podman.socket

clean:          ## Remove build outputs
	$(GRADLE) clean
