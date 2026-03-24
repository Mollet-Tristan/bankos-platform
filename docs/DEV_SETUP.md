## Local Development Setup

### Prerequisites

| Tool | Version | Install |
|------|---------|---------|
| Java | 21+ | `mise use java@21` or `sdk install java 21-tem` |
| Rust | stable | `curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs \| sh` |
| just | any | `brew install just` |
| Podman | 4.4+ | `brew install podman` |
| podman-compose | any | `pip3 install podman-compose` |

### First-time setup

```bash
# macOS — initialize Podman VM (once)
podman machine init
podman machine start

# From repo root
chmod +x infra/scripts/dev-setup.sh
./infra/scripts/dev-setup.sh
```

The setup script checks all prerequisites, fixes Gradle wrapper permissions, and installs `just` and `podman-compose` if missing.

### Daily workflow

```bash
# Start infrastructure
just infra-up

# Build everything
just build

# Run all tests
just test

# Work on a single service
just account-service-test
just account-service-run

# Reporting CLI (Rust)
just reporting-test
just reporting-report summary --start 2024-01-01 --end 2024-01-31

# List all available tasks
just
```

### Infrastructure (after `just infra-up`)

| Service | URL / Port | Credentials |
|---------|-----------|-------------|
| Kafka | `localhost:9092` | — |
| Kafka UI | http://localhost:9090 | — |
| Postgres (accounts) | `localhost:5432` | `bankos / bankos` |
| Postgres (transactions) | `localhost:5433` | `bankos / bankos` |
| Postgres (notifications) | `localhost:5434` | `bankos / bankos` |
| Redis | `localhost:6379` | — |
| Keycloak | http://localhost:8180 | `admin / admin` |
| Mailpit (SMTP) | `localhost:1025` | no auth |
| Mailpit (UI) | http://localhost:8025 | — |

### Test users (Keycloak realm `bankos`)

| Username | Password | Roles |
|----------|----------|-------|
| `user1` | `password` | USER |
| `operator1` | `password` | USER, BACKOFFICE |
| `admin1` | `password` | USER, BACKOFFICE, ADMIN |

### Getting a JWT for manual testing

```bash
# Get a token (operator1 by default)
just token

# Get a token for a specific user
just token user1

# Use in curl
export TOKEN=$(just token operator1)
curl -H "Authorization: Bearer $TOKEN" http://localhost:8081/api/v1/accounts
```

### Project structure

```
bankos-platform/
├── Justfile                    ← Task runner — start here
├── api-gateway/                ← Spring Cloud Gateway (Kotlin)
├── account-service/            ← Account domain (Kotlin)
├── transaction-service/        ← Transaction + Saga (Kotlin)
├── notification-service/       ← Email/SMS consumer (Kotlin)
├── reporting-service/          ← Analytics CLI (Rust)
├── docs/adr/                   ← Architecture Decision Records
└── infra/
    ├── docker-compose.yml      ← Local infra (Podman compatible)
    ├── keycloak/realms/        ← Realm config auto-imported at startup
    ├── kubernetes/             ← K8s manifests (Kustomize)
    ├── helm/                   ← Helm chart (production)
    ├── monitoring/             ← Prometheus + Grafana dashboards
    └── scripts/
        ├── dev-setup.sh        ← Prerequisite checker
        ├── deploy.sh           ← Deploy to local/staging/prod
        └── bootstrap-secrets.sh
```

### Why Podman over Docker?

- **No daemon** — rootless by default, no background service with root privileges
- **OCI-compliant** — builds the same images as Docker
- **Drop-in replacement** — same CLI, same Compose format (`podman compose`)
- Better fit for security-conscious environments (banking context)
