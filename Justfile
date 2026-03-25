# ============================================================
# BankOS Platform — Monorepo Task Runner
# ============================================================
# Prerequisites:
#   - just          (brew install just / cargo install just)
#   - java 21+      (mise use java@21 / sdk install java 21-tem)
#   - rust stable   (rustup)
#   - podman        (brew install podman)
#   - podman-compose (pip install podman-compose)
#
# Usage:
#   just            → list all available tasks
#   just build      → build everything
#   just test       → run all tests

default:
    @just --list

# ============================================================
# VARIABLES
# ============================================================

kotlin_services := "api-gateway account-service transaction-service notification-service"
rust_services   := "reporting-service"

# ============================================================
# TOP-LEVEL
# ============================================================

# Build all services
build: build-kotlin build-rust
    @echo "✅ All services built."

# Run all tests
test: test-kotlin test-rust
    @echo "✅ All tests passed."

# Lint + format check (Rust only — Kotlin via Gradle)
lint: lint-rust
    @echo "✅ Lint complete."

# Clean all build artifacts
clean: clean-kotlin clean-rust
    @echo "✅ Clean complete."

# Run lint + test — use before pushing
check: lint test
    @echo "✅ All checks passed."

# ============================================================
# KOTLIN SERVICES
# ============================================================

build-kotlin:
    #!/usr/bin/env bash
    set -e
    for svc in {{ kotlin_services }}; do
        echo "🔨 Building $svc..."
        (cd "$svc" && ./gradlew build -x test --parallel --quiet)
    done
    echo "✅ Kotlin builds complete."

test-kotlin:
    #!/usr/bin/env bash
    set -e
    for svc in {{ kotlin_services }}; do
        echo "🧪 Testing $svc..."
        (cd "$svc" && ./gradlew test --parallel)
    done
    echo "✅ Kotlin tests complete."

clean-kotlin:
    #!/usr/bin/env bash
    for svc in {{ kotlin_services }}; do
        (cd "$svc" && ./gradlew clean --quiet)
    done

# Per-service shortcuts
api-gateway-build:
    cd api-gateway && ./gradlew build -x test

api-gateway-test:
    cd api-gateway && ./gradlew test

api-gateway-run:
    cd api-gateway && ./gradlew bootRun

account-service-build:
    cd account-service && ./gradlew build -x test

account-service-test:
    cd account-service && ./gradlew test

account-service-run:
    cd account-service && ./gradlew bootRun

transaction-service-build:
    cd transaction-service && ./gradlew build -x test

transaction-service-test:
    cd transaction-service && ./gradlew test

transaction-service-run:
    cd transaction-service && ./gradlew bootRun

notification-service-build:
    cd notification-service && ./gradlew build -x test

notification-service-test:
    cd notification-service && ./gradlew test

notification-service-run:
    cd notification-service && ./gradlew bootRun

# ============================================================
# RUST SERVICES
# ============================================================

build-rust:
    #!/usr/bin/env bash
    set -e
    for svc in {{ rust_services }}; do
        echo "🦀 Building $svc..."
        (cd "$svc" && cargo build)
    done
    echo "✅ Rust builds complete."

build-rust-release:
    #!/usr/bin/env bash
    set -e
    for svc in {{ rust_services }}; do
        (cd "$svc" && cargo build --release)
    done

test-rust:
    #!/usr/bin/env bash
    set -e
    for svc in {{ rust_services }}; do
        echo "🧪 Testing $svc..."
        (cd "$svc" && cargo test)
    done
    echo "✅ Rust tests complete."

lint-rust:
    #!/usr/bin/env bash
    set -e
    for svc in {{ rust_services }}; do
        echo "🔍 Linting $svc..."
        (cd "$svc" && cargo fmt --check && cargo clippy -- -D warnings)
    done

fmt-rust:
    #!/usr/bin/env bash
    for svc in {{ rust_services }}; do
        (cd "$svc" && cargo fmt)
    done
    echo "✅ Rust formatting applied."

clean-rust:
    #!/usr/bin/env bash
    for svc in {{ rust_services }}; do
        (cd "$svc" && cargo clean)
    done

reporting-build:
    cd reporting-service && cargo build

reporting-build-release:
    cd reporting-service && cargo build --release

reporting-test:
    cd reporting-service && cargo test

reporting-consumer:
    cd reporting-service && cargo run --bin bankos-consumer

reporting-report *ARGS:
    cd reporting-service && cargo run --bin bankos-report -- {{ ARGS }}

# ============================================================
# PODMAN IMAGES
# ============================================================

# Build a container image for one service
# Usage: just image-build account-service
image-build service:
    #!/usr/bin/env bash
    set -e
    echo "📦 Building image bankos/{{ service }}:local..."
    if [[ "{{ service }}" == "reporting-service" ]]; then
        podman build -t bankos/{{ service }}:local -f {{ service }}/Dockerfile {{ service }}
    else
        (cd {{ service }} && ./gradlew build -x test --quiet)
        podman build -t bankos/{{ service }}:local -f {{ service }}/Dockerfile {{ service }}
    fi
    echo "✅ bankos/{{ service }}:local ready."

# Build all images
image-build-all:
    #!/usr/bin/env bash
    set -e
    for svc in {{ kotlin_services }} {{ rust_services }}; do
        just image-build "$svc"
    done

# List local bankos images
image-list:
    podman images | grep bankos

# ============================================================
# LOCAL INFRA (Podman Compose)
# ============================================================

# Start all infra containers
infra-up:
    podman compose -f infra/docker-compose.yml up -d
    @echo ""
    @echo "✅ Infrastructure started."
    @echo "   Kafka UI:   http://localhost:9090"
    @echo "   Keycloak:   http://localhost:8180  (admin / admin)"
    @echo "   Mailpit:    http://localhost:8025"
    @echo "   Postgres:   :5432 (accounts) :5433 (transactions) :5434 (notifications)"
    @echo "   Redis:      localhost:6379"

# Stop all infra containers
infra-down:
    podman compose -f infra/docker-compose.yml down

# Wipe all infra data (volumes included)
infra-reset:
    podman compose -f infra/docker-compose.yml down -v
    @echo "⚠️  All infra volumes wiped."

# Status of infra containers
infra-status:
    podman compose -f infra/docker-compose.yml ps

# Follow logs for one container
# Usage: just infra-logs kafka
infra-logs container:
    podman compose -f infra/docker-compose.yml logs -f {{ container }}

# ============================================================
# KUBERNETES (local — minikube / kind)
# ============================================================

k8s-deploy-local:
    kubectl apply -k infra/kubernetes/overlays/local

k8s-status:
    kubectl get pods -n bankos

# Usage: just k8s-logs account-service
k8s-logs deployment:
    kubectl logs -n bankos -l app={{ deployment }} -f --tail=100

# Usage: just k8s-port-forward account-service 8081
k8s-port-forward service port:
    kubectl port-forward -n bankos svc/{{ service }} {{ port }}:{{ port }}

# ============================================================
# DEV HELPERS
# ============================================================

# Get a JWT for local testing
# Usage: just token operator1
token user="operator1":
    #!/usr/bin/env bash
    TOKEN=$(curl -sf -X POST http://localhost:8180/realms/bankos/protocol/openid-connect/token \
      -H "Content-Type: application/x-www-form-urlencoded" \
      -d "client_id=bankos-gateway" \
      -d "client_secret=bankos-gateway-secret" \
      -d "username={{ user }}" \
      -d "password=password" \
      -d "grant_type=password" | python3 -c "import sys,json; print(json.load(sys.stdin)['access_token'])")
    echo "$TOKEN"
    echo ""
    echo "💡 Export with: export TOKEN=\$(just token {{ user }})"

# Smoke test against local gateway
smoke:
    #!/usr/bin/env bash
    echo "🔥 Smoke testing http://localhost:8080..."
    curl -sf http://localhost:8080/actuator/health | python3 -m json.tool
    echo "✅ Gateway healthy."

# Open Swagger UI for a service
# Usage: just swagger account-service
swagger service:
    #!/usr/bin/env bash
    declare -A ports=(
        ["account-service"]="8081"
        ["transaction-service"]="8082"
        ["notification-service"]="8083"
    )
    port="${ports[{{ service }}]:-}"
    if [[ -z "$port" ]]; then
        echo "❌ Unknown service: {{ service }}"
        echo "   Available: account-service, transaction-service, notification-service"
        exit 1
    fi
    url="http://localhost:$port/swagger-ui.html"
    echo "Opening $url"
    open "$url" 2>/dev/null || xdg-open "$url" 2>/dev/null || echo "Navigate to: $url"

# Watch Rust tests on file change (requires cargo-watch)
# Usage: just watch reporting-service
watch service:
    cd {{ service }} && cargo watch -x test

# Print installed tool versions
versions:
    @echo "── Tool versions ──────────────────────────"
    @java -version 2>&1 | head -1
    @rustc --version
    @cargo --version
    @just --version
    @podman --version
    @echo "───────────────────────────────────────────"
