#!/usr/bin/env bash
# ============================================================
# BankOS Platform — Developer Setup Script
# ============================================================
# Run once after cloning the repo (from repo root).
#
# Usage:
#   chmod +x infra/scripts/dev-setup.sh
#   ./infra/scripts/dev-setup.sh

set -euo pipefail

GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; BOLD='\033[1m'; NC='\033[0m'
ok()   { echo -e "  ${GREEN}✓${NC} $*"; }
warn() { echo -e "  ${YELLOW}!${NC} $*"; }
err()  { echo -e "  ${RED}✗${NC} $*"; }
info() { echo -e "    ${BOLD}→${NC} $*"; }
section() { echo ""; echo -e "${BOLD}$*${NC}"; echo "──────────────────────────────────────────"; }

echo ""
echo -e "${BOLD}BankOS Platform — Dev Setup${NC}"
echo "=========================================="

ERRORS=0

# ── Java 21+ ─────────────────────────────────────────────────────────────────
section "Java"
if command -v java &>/dev/null; then
    JAVA_VER=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}' | cut -d'.' -f1)
    if [[ "$JAVA_VER" -ge 21 ]]; then
        ok "Java $JAVA_VER"
    else
        err "Java $JAVA_VER found — Java 21+ required"
        info "Install: mise use java@21"
        info "      or: sdk install java 21-tem"
        ERRORS=$((ERRORS + 1))
    fi
else
    err "Java not found"
    info "Install via mise: curl https://mise.run | sh && mise use java@21"
    ERRORS=$((ERRORS + 1))
fi

# ── Rust ─────────────────────────────────────────────────────────────────────
section "Rust"
if command -v rustc &>/dev/null; then
    ok "$(rustc --version)"
    # Clippy
    if rustup component list --installed 2>/dev/null | grep -q "clippy"; then
        ok "clippy installed"
    else
        warn "clippy missing — installing..."
        rustup component add clippy && ok "clippy installed"
    fi
    # rustfmt
    if rustup component list --installed 2>/dev/null | grep -q "rustfmt"; then
        ok "rustfmt installed"
    else
        warn "rustfmt missing — installing..."
        rustup component add rustfmt && ok "rustfmt installed"
    fi
else
    err "Rust not found"
    info "Install: curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh"
    ERRORS=$((ERRORS + 1))
fi

# ── Just ─────────────────────────────────────────────────────────────────────
section "Just"
if command -v just &>/dev/null; then
    ok "$(just --version)"
else
    warn "just not found — attempting install..."
    if command -v brew &>/dev/null; then
        brew install just && ok "just installed via Homebrew"
    elif command -v cargo &>/dev/null; then
        cargo install just && ok "just installed via cargo"
    else
        err "Could not install just automatically"
        info "See: https://github.com/casey/just#installation"
        ERRORS=$((ERRORS + 1))
    fi
fi

# ── Podman ───────────────────────────────────────────────────────────────────
section "Podman"
if command -v podman &>/dev/null; then
    ok "$(podman --version)"

    # Check podman machine is running on macOS
    if [[ "$(uname)" == "Darwin" ]]; then
        if podman machine list 2>/dev/null | grep -q "Currently running"; then
            ok "Podman machine running"
        else
            warn "Podman machine not running"
            info "Run: podman machine init && podman machine start"
            warn "Continuing — run infra-up after starting the machine."
        fi
    fi

    # Check podman compose
    if command -v podman-compose &>/dev/null; then
        ok "podman-compose $(podman-compose --version 2>/dev/null | head -1)"
    elif podman compose version &>/dev/null 2>&1; then
        ok "podman compose (built-in) available"
    else
        warn "podman compose not found — installing podman-compose..."
        if command -v pip3 &>/dev/null; then
            pip3 install podman-compose && ok "podman-compose installed"
        else
            err "Could not install podman-compose (pip3 not found)"
            info "Install: pip3 install podman-compose"
            ERRORS=$((ERRORS + 1))
        fi
    fi
else
    err "Podman not found"
    info "macOS:  brew install podman && podman machine init && podman machine start"
    info "Linux:  https://podman.io/docs/installation"
    ERRORS=$((ERRORS + 1))
fi

# ── Gradle wrapper permissions ────────────────────────────────────────────────
section "Gradle Wrappers"
kotlin_services=("api-gateway" "account-service" "transaction-service" "notification-service")
for svc in "${kotlin_services[@]}"; do
    wrapper="$svc/gradlew"
    if [[ -f "$wrapper" ]]; then
        chmod +x "$wrapper"
        ok "chmod +x $wrapper"
    else
        warn "$wrapper not found — is the repo fully cloned?"
    fi
done

# ── Justfile ──────────────────────────────────────────────────────────────────
section "Repo Structure"
if [[ -f "Justfile" ]]; then
    ok "Justfile present"
else
    err "Justfile not found — run this script from the repo root"
    ERRORS=$((ERRORS + 1))
fi

if [[ -f "infra/docker-compose.yml" ]]; then
    ok "infra/docker-compose.yml present"
else
    warn "infra/docker-compose.yml not found"
fi

if [[ -f "infra/keycloak/realms/bankos-realm.json" ]]; then
    ok "Keycloak realm config present"
else
    warn "infra/keycloak/realms/bankos-realm.json not found — Keycloak won't auto-import realm"
fi

# ── kubectl (optional) ────────────────────────────────────────────────────────
section "Optional Tools"
if command -v kubectl &>/dev/null; then
    ok "kubectl $(kubectl version --client --short 2>/dev/null | awk '{print $3}')"
else
    warn "kubectl not found (optional — only needed for Kubernetes deployments)"
    info "Install: https://kubernetes.io/docs/tasks/tools/"
fi

if command -v cargo-watch &>/dev/null 2>&1 || cargo install --list 2>/dev/null | grep -q "cargo-watch"; then
    ok "cargo-watch installed (enables 'just watch <service>')"
else
    warn "cargo-watch not installed (optional)"
    info "Install: cargo install cargo-watch"
fi

# ── Summary ───────────────────────────────────────────────────────────────────
echo ""
echo "=========================================="
if [[ "$ERRORS" -eq 0 ]]; then
    echo -e "${GREEN}${BOLD}✅ All prerequisites satisfied!${NC}"
    echo ""
    echo "Quick start:"
    echo "  just infra-up           → Start Kafka, Postgres x3, Redis, Keycloak, Mailpit"
    echo "  just build              → Build all services"
    echo "  just test               → Run all tests"
    echo "  just                    → List all available tasks"
    echo ""
    echo "Infra URLs (after just infra-up):"
    echo "  Kafka UI   → http://localhost:9090"
    echo "  Keycloak   → http://localhost:8180  (admin/admin)"
    echo "  Mailpit    → http://localhost:8025"
else
    echo -e "${RED}${BOLD}❌ $ERRORS prerequisite(s) missing — fix them before running 'just build'.${NC}"
    exit 1
fi
