#!/usr/bin/env bash
# =============================================================================
# BankOS Platform — Deployment Script
# =============================================================================
#
# Usage:
#   ./infra/scripts/deploy.sh [ENVIRONMENT] [OPTIONS]
#
# Environments:
#   local       Apply to local cluster (minikube/kind) via Kustomize
#   staging     Deploy to staging via Helm
#   production  Deploy to production via Helm (requires confirmation)
#
# Examples:
#   ./infra/scripts/deploy.sh local
#   ./infra/scripts/deploy.sh staging --tag 1.2.3
#   ./infra/scripts/deploy.sh production --tag 1.2.3 --confirm
#
# Prerequisites:
#   - kubectl configured for the target cluster
#   - helm >= 3.12
#   - kustomize >= 5.0 (for local)
# =============================================================================

set -euo pipefail

# ── Colours ───────────────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
BLUE='\033[0;34m'; BOLD='\033[1m'; NC='\033[0m'

log()  { echo -e "${BLUE}[deploy]${NC} $*"; }
ok()   { echo -e "${GREEN}[✓]${NC} $*"; }
warn() { echo -e "${YELLOW}[!]${NC} $*"; }
err()  { echo -e "${RED}[✗]${NC} $*"; exit 1; }

# ── Arguments ─────────────────────────────────────────────────────────────────
ENVIRONMENT="${1:-local}"
IMAGE_TAG="${IMAGE_TAG:-latest}"
CONFIRM=false

shift || true
while [[ $# -gt 0 ]]; do
  case $1 in
    --tag)       IMAGE_TAG="$2"; shift 2 ;;
    --confirm)   CONFIRM=true; shift ;;
    *)           err "Unknown argument: $1" ;;
  esac
done

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
INFRA_DIR="$REPO_ROOT/infra"

# ── Pre-flight checks ─────────────────────────────────────────────────────────
preflight() {
  log "Running pre-flight checks..."

  command -v kubectl >/dev/null 2>&1 || err "kubectl not found"
  command -v helm    >/dev/null 2>&1 || err "helm not found"

  local ctx
  ctx=$(kubectl config current-context 2>/dev/null || echo "none")
  log "Current kubectl context: ${BOLD}${ctx}${NC}"

  # Safety: prevent accidental production deploy
  if [[ "$ENVIRONMENT" == "production" ]] && [[ "$CONFIRM" != "true" ]]; then
    err "Production deploy requires --confirm flag. Add it only if you are certain."
  fi

  ok "Pre-flight checks passed"
}

# ── Local deployment (Kustomize) ──────────────────────────────────────────────
deploy_local() {
  log "Deploying to LOCAL cluster via Kustomize..."
  log "Image tag: ${IMAGE_TAG}"

  # Ensure namespace exists
  kubectl apply -f "$INFRA_DIR/kubernetes/namespaces/bankos.yaml"

  # Apply Kustomize overlay
  kubectl apply -k "$INFRA_DIR/kubernetes/overlays/local"

  ok "Local deployment applied"
  log "Waiting for rollout..."

  kubectl rollout status deployment/api-gateway         -n bankos --timeout=120s
  kubectl rollout status deployment/account-service     -n bankos --timeout=120s
  kubectl rollout status deployment/transaction-service -n bankos --timeout=120s
  kubectl rollout status deployment/notification-service -n bankos --timeout=120s

  ok "All services running"
  echo ""
  kubectl get pods -n bankos
}

# ── Staging / Production deployment (Helm) ────────────────────────────────────
deploy_helm() {
  local env="$1"
  local values_file="$INFRA_DIR/helm/values-${env}.yaml"
  local release="bankos-platform"
  local namespace="bankos"

  log "Deploying to ${BOLD}${env}${NC} via Helm..."
  log "Image tag: ${IMAGE_TAG}"

  if [[ ! -f "$values_file" ]]; then
    warn "No values file found at $values_file — using defaults only"
    values_file=""
  fi

  # Ensure namespace exists
  kubectl create namespace "$namespace" --dry-run=client -o yaml | kubectl apply -f -

  # Helm upgrade (installs if not present)
  helm upgrade "$release" "$INFRA_DIR/helm/bankos-platform" \
    --namespace "$namespace" \
    --install \
    --wait \
    --timeout 5m \
    --atomic \
    ${values_file:+--values "$values_file"} \
    --set global.imageTag="$IMAGE_TAG" \
    --set apiGateway.image.tag="$IMAGE_TAG" \
    --set accountService.image.tag="$IMAGE_TAG" \
    --set transactionService.image.tag="$IMAGE_TAG" \
    --set notificationService.image.tag="$IMAGE_TAG" \
    --set reportingService.image.tag="$IMAGE_TAG"

  ok "Helm release '${release}' deployed to ${env}"

  # Post-deploy smoke test
  smoke_test "$env"
}

# ── Smoke test ─────────────────────────────────────────────────────────────────
smoke_test() {
  local env="$1"
  log "Running smoke tests against ${env}..."

  local gateway_url
  if [[ "$env" == "local" ]]; then
    gateway_url="http://localhost:8080"
  elif [[ "$env" == "staging" ]]; then
    gateway_url="https://staging-api.bankos.demo"
  else
    gateway_url="https://api.bankos.demo"
  fi

  # Check gateway health
  local status
  status=$(curl -s -o /dev/null -w "%{http_code}" "$gateway_url/actuator/health" 2>/dev/null || echo "000")

  if [[ "$status" == "200" ]]; then
    ok "Gateway health check passed (HTTP $status)"
  else
    warn "Gateway health check returned HTTP $status — deployment may still be starting"
  fi
}

# ── Rollback ──────────────────────────────────────────────────────────────────
rollback() {
  local env="${1:-staging}"
  warn "Rolling back ${env} to previous Helm release..."
  helm rollback bankos-platform --namespace bankos
  ok "Rollback complete"
}

# ── Main ──────────────────────────────────────────────────────────────────────
main() {
  echo ""
  echo -e "${BOLD}╔══════════════════════════════════════╗${NC}"
  echo -e "${BOLD}║   BankOS Platform Deployment         ║${NC}"
  echo -e "${BOLD}║   Environment: ${ENVIRONMENT}$(printf '%*s' $((20 - ${#ENVIRONMENT})) '')║${NC}"
  echo -e "${BOLD}╚══════════════════════════════════════╝${NC}"
  echo ""

  preflight

  case "$ENVIRONMENT" in
    local)      deploy_local ;;
    staging)    deploy_helm "staging" ;;
    production) deploy_helm "production" ;;
    rollback)   rollback "${2:-staging}" ;;
    *)          err "Unknown environment: $ENVIRONMENT. Use: local | staging | production | rollback" ;;
  esac
}

main "$@"
