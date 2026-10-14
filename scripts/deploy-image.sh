#!/usr/bin/env bash
# =============================================================================
# deploy-image.sh — Deploy ResortsLite to AWS EKS
# Usage: bash scripts/deploy-image.sh   (run from repository root)
# =============================================================================
set -e
set -o pipefail

APP_NAME="resortsLite"
NAMESPACE="resortsLite"
K8S_DIR="kubernetes"

echo "============================================="
echo "  ResortsLite — AWS EKS Deployment"
echo "============================================="
echo ""

# ---------------------------------------------------------------------------
# Collect deployment inputs
# ---------------------------------------------------------------------------
read -rp "Enter AWS region (e.g. us-east-1): " AWS_REGION
if [ -z "$AWS_REGION" ]; then
  echo "ERROR: AWS region is required."
  exit 1
fi

read -rp "Enter EKS cluster name: " CLUSTER_NAME
if [ -z "$CLUSTER_NAME" ]; then
  echo "ERROR: EKS cluster name is required."
  exit 1
fi

read -rp "Enter full Docker image URI (e.g. 123456789.dkr.ecr.us-east-1.amazonaws.com/resortsLite:latest): " IMAGE_URI
if [ -z "$IMAGE_URI" ]; then
  echo "ERROR: Docker image URI is required."
  exit 1
fi

echo ""
echo "--- Application Environment Variables ---"
echo "Press Enter to skip any variable (placeholder will remain in manifest)."
echo ""

read -rp "Enter SPRING_DATASOURCE_URL (e.g. jdbc:oracle:thin:@host:1521:SID): " SPRING_DATASOURCE_URL
read -rp "Enter SPRING_DATASOURCE_USERNAME: " SPRING_DATASOURCE_USERNAME
read -rsp "Enter SPRING_DATASOURCE_PASSWORD: " SPRING_DATASOURCE_PASSWORD
echo ""
read -rp "Enter REDIS_HOST (ElastiCache primary endpoint): " REDIS_HOST
read -rp "Enter REDIS_PORT [6379]: " REDIS_PORT
REDIS_PORT="${REDIS_PORT:-6379}"
read -rp "Enter CACHE_TTL_MINUTES [30]: " CACHE_TTL_MINUTES
CACHE_TTL_MINUTES="${CACHE_TTL_MINUTES:-30}"
read -rp "Enter APP_PAYMENT_ENDPOINT: " APP_PAYMENT_ENDPOINT
read -rp "Enter APP_INVENTORY_ENDPOINT: " APP_INVENTORY_ENDPOINT
read -rp "Enter APP_NOTIFICATION_ENDPOINT: " APP_NOTIFICATION_ENDPOINT
read -rp "Enter REPORT_BASE_PATH [/var/reports]: " REPORT_BASE_PATH
REPORT_BASE_PATH="${REPORT_BASE_PATH:-/var/reports}"

# ---------------------------------------------------------------------------
# Configure kubectl for EKS
# ---------------------------------------------------------------------------
echo ""
echo "Configuring kubectl for EKS cluster: $CLUSTER_NAME ..."
aws eks update-kubeconfig --region "$AWS_REGION" --name "$CLUSTER_NAME"

echo "Verifying cluster connectivity..."
kubectl cluster-info || { echo "ERROR: Cannot connect to EKS cluster."; exit 1; }

# ---------------------------------------------------------------------------
# Substitute placeholders in manifests (work on copies to avoid dirty git state)
# ---------------------------------------------------------------------------
echo ""
echo "Preparing Kubernetes manifests..."
TMP_DIR=$(mktemp -d)
cp -r "$K8S_DIR"/. "$TMP_DIR/"

DEPLOYMENT_YAML="$TMP_DIR/deployment.yaml"

sed -i "s|{{IMAGE_URI}}|${IMAGE_URI}|g"                                   "$DEPLOYMENT_YAML"
sed -i "s|{{SPRING_DATASOURCE_URL}}|${SPRING_DATASOURCE_URL}|g"           "$DEPLOYMENT_YAML"
sed -i "s|{{SPRING_DATASOURCE_USERNAME}}|${SPRING_DATASOURCE_USERNAME}|g" "$DEPLOYMENT_YAML"
sed -i "s|{{SPRING_DATASOURCE_PASSWORD}}|${SPRING_DATASOURCE_PASSWORD}|g" "$DEPLOYMENT_YAML"
sed -i "s|{{REDIS_HOST}}|${REDIS_HOST}|g"                                 "$DEPLOYMENT_YAML"
sed -i "s|{{REDIS_PORT}}|${REDIS_PORT}|g"                                 "$DEPLOYMENT_YAML"
sed -i "s|{{CACHE_TTL_MINUTES}}|${CACHE_TTL_MINUTES}|g"                   "$DEPLOYMENT_YAML"
sed -i "s|{{APP_PAYMENT_ENDPOINT}}|${APP_PAYMENT_ENDPOINT}|g"             "$DEPLOYMENT_YAML"
sed -i "s|{{APP_INVENTORY_ENDPOINT}}|${APP_INVENTORY_ENDPOINT}|g"         "$DEPLOYMENT_YAML"
sed -i "s|{{APP_NOTIFICATION_ENDPOINT}}|${APP_NOTIFICATION_ENDPOINT}|g"   "$DEPLOYMENT_YAML"
sed -i "s|{{REPORT_BASE_PATH}}|${REPORT_BASE_PATH}|g"                     "$DEPLOYMENT_YAML"

# ---------------------------------------------------------------------------
# Apply manifests in order
# ---------------------------------------------------------------------------
echo ""
echo "Applying Kubernetes manifests..."

echo "  [1/4] Namespace..."
kubectl apply -f "$TMP_DIR/namespace.yaml"

echo "  [2/4] Deployment..."
kubectl apply -f "$DEPLOYMENT_YAML"

echo "  [3/4] Service..."
kubectl apply -f "$TMP_DIR/service.yaml"

echo "  [4/4] Ingress..."
kubectl apply -f "$TMP_DIR/ingress.yaml"

# ---------------------------------------------------------------------------
# Wait for rollout
# ---------------------------------------------------------------------------
echo ""
echo "Waiting for deployment rollout..."
kubectl rollout status deployment/"$APP_NAME" -n "$NAMESPACE" --timeout=300s

# ---------------------------------------------------------------------------
# Verify
# ---------------------------------------------------------------------------
echo ""
echo "Deployed resources:"
kubectl get pods,svc,ingress -n "$NAMESPACE"

# ---------------------------------------------------------------------------
# Display ingress URL
# ---------------------------------------------------------------------------
echo ""
INGRESS_HOST=$(kubectl get ingress "${APP_NAME}-ingress" -n "$NAMESPACE" \
  -o jsonpath='{.status.loadBalancer.ingress[0].hostname}' 2>/dev/null || true)
if [ -n "$INGRESS_HOST" ]; then
  echo "Application URL: http://$INGRESS_HOST"
else
  echo "Ingress hostname not yet assigned. Run:"
  echo "  kubectl get ingress ${APP_NAME}-ingress -n ${NAMESPACE}"
fi

# ---------------------------------------------------------------------------
# Cleanup temp dir
# ---------------------------------------------------------------------------
rm -rf "$TMP_DIR"

echo ""
echo "============================================="
echo "  Deployment complete!"
echo "============================================="
echo ""
echo "Rollback command (if needed):"
echo "  kubectl rollout undo deployment/$APP_NAME -n $NAMESPACE"
