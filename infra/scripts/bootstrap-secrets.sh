#!/usr/bin/env bash
# =============================================================================
# BankOS Platform — Secrets Bootstrap
# =============================================================================
#
# Creates Kubernetes Secrets for all services.
# Run ONCE per environment during initial cluster setup.
#
# IMPORTANT: This script reads values from environment variables.
# Never commit actual secret values to Git.
#
# Usage:
#   export DB_PASSWORD="$(vault kv get -field=password secret/bankos/postgres)"
#   export SMTP_PASSWORD="$(vault kv get -field=password secret/bankos/smtp)"
#   ./infra/scripts/bootstrap-secrets.sh
#
# In a real deployment, use:
#   - HashiCorp Vault + external-secrets-operator
#   - AWS Secrets Manager + external-secrets-operator
#   - Sealed Secrets (Bitnami) for GitOps-friendly encrypted secrets
# =============================================================================

set -euo pipefail

NAMESPACE="${NAMESPACE:-bankos}"
GREEN='\033[0;32m'; NC='\033[0m'
ok() { echo -e "${GREEN}[✓]${NC} $*"; }

# Ensure namespace exists
kubectl create namespace "$NAMESPACE" --dry-run=client -o yaml | kubectl apply -f -

# ── Database credentials ───────────────────────────────────────────────────────

kubectl create secret generic account-db-credentials \
  --namespace "$NAMESPACE" \
  --from-literal=username="${ACCOUNT_DB_USER:-bankos}" \
  --from-literal=password="${ACCOUNT_DB_PASSWORD:?ACCOUNT_DB_PASSWORD is required}" \
  --dry-run=client -o yaml | kubectl apply -f -
ok "account-db-credentials"

kubectl create secret generic transaction-db-credentials \
  --namespace "$NAMESPACE" \
  --from-literal=username="${TRANSACTION_DB_USER:-bankos}" \
  --from-literal=password="${TRANSACTION_DB_PASSWORD:?TRANSACTION_DB_PASSWORD is required}" \
  --dry-run=client -o yaml | kubectl apply -f -
ok "transaction-db-credentials"

kubectl create secret generic notification-db-credentials \
  --namespace "$NAMESPACE" \
  --from-literal=username="${NOTIFICATION_DB_USER:-bankos}" \
  --from-literal=password="${NOTIFICATION_DB_PASSWORD:?NOTIFICATION_DB_PASSWORD is required}" \
  --dry-run=client -o yaml | kubectl apply -f -
ok "notification-db-credentials"

# ── Redis ─────────────────────────────────────────────────────────────────────

kubectl create secret generic redis-credentials \
  --namespace "$NAMESPACE" \
  --from-literal=host="${REDIS_HOST:-bankos-redis}" \
  --from-literal=password="${REDIS_PASSWORD:-}" \
  --dry-run=client -o yaml | kubectl apply -f -
ok "redis-credentials"

# ── SMTP ──────────────────────────────────────────────────────────────────────

kubectl create secret generic smtp-credentials \
  --namespace "$NAMESPACE" \
  --from-literal=host="${SMTP_HOST:?SMTP_HOST is required}" \
  --from-literal=port="${SMTP_PORT:-587}" \
  --from-literal=username="${SMTP_USERNAME:?SMTP_USERNAME is required}" \
  --from-literal=password="${SMTP_PASSWORD:?SMTP_PASSWORD is required}" \
  --dry-run=client -o yaml | kubectl apply -f -
ok "smtp-credentials"

# ── TLS certificate (self-signed for non-prod) ────────────────────────────────
# In production, cert-manager handles this automatically via the Ingress annotation.
# This is only needed for staging without cert-manager.

if [[ "${CREATE_SELF_SIGNED_TLS:-false}" == "true" ]]; then
  openssl req -x509 -nodes -days 365 -newkey rsa:2048 \
    -keyout /tmp/bankos-tls.key \
    -out /tmp/bankos-tls.crt \
    -subj "/CN=api.bankos.demo/O=BankOS" 2>/dev/null

  kubectl create secret tls bankos-tls \
    --namespace "$NAMESPACE" \
    --key /tmp/bankos-tls.key \
    --cert /tmp/bankos-tls.crt \
    --dry-run=client -o yaml | kubectl apply -f -
  rm /tmp/bankos-tls.{key,crt}
  ok "bankos-tls (self-signed)"
fi

echo ""
echo "All secrets bootstrapped in namespace: $NAMESPACE"
echo ""
echo "Next step: ./infra/scripts/deploy.sh local"
