#!/usr/bin/env bash
# =============================================================================
# deploy-image.sh – Deploy ResortsLite to Azure AKS
# Usage: ./scripts/deploy-image.sh
# Run from the repository root directory.
# =============================================================================
set -e
set -o pipefail

APP_NAME="resortslite"
NAMESPACE="resortslite"

echo "=============================================="
echo "  ResortsLite – Deploy to Azure AKS"
echo "=============================================="
echo ""

# ---------------------------------------------------------------------------
# Collect Azure / AKS details
# ---------------------------------------------------------------------------
read -rp "Enter Azure Resource Group name: " RESOURCE_GROUP
if [ -z "$RESOURCE_GROUP" ]; then
  echo "ERROR: Resource group cannot be empty." >&2
  exit 1
fi

read -rp "Enter AKS Cluster name: " CLUSTER_NAME
if [ -z "$CLUSTER_NAME" ]; then
  echo "ERROR: AKS cluster name cannot be empty." >&2
  exit 1
fi

read -rp "Enter full Docker image URI (e.g. myregistry.azurecr.io/resortslite:latest): " IMAGE_URI
if [ -z "$IMAGE_URI" ]; then
  echo "ERROR: Image URI cannot be empty." >&2
  exit 1
fi

echo ""
echo "Resource Group : $RESOURCE_GROUP"
echo "AKS Cluster    : $CLUSTER_NAME"
echo "Image URI      : $IMAGE_URI"
echo ""

# ---------------------------------------------------------------------------
# Prompt for application-specific environment variables
# ---------------------------------------------------------------------------
echo "--- Application Environment Variables ---"
echo "(Press Enter to keep placeholder value for any variable)"
echo ""

read -rp "Enter SPRING_DATASOURCE_URL [jdbc:oracle:thin:@<host>:1521:<sid>]: " SPRING_DATASOURCE_URL
SPRING_DATASOURCE_URL="${SPRING_DATASOURCE_URL:-jdbc:oracle:thin:@db-host:1521:ORCL}"

read -rp "Enter SPRING_DATASOURCE_USERNAME [admin]: " SPRING_DATASOURCE_USERNAME
SPRING_DATASOURCE_USERNAME="${SPRING_DATASOURCE_USERNAME:-admin}"

read -rsp "Enter SPRING_DATASOURCE_PASSWORD: " SPRING_DATASOURCE_PASSWORD
echo ""
SPRING_DATASOURCE_PASSWORD="${SPRING_DATASOURCE_PASSWORD:-changeme}"

read -rp "Enter REDIS_HOST: " REDIS_HOST
REDIS_HOST="${REDIS_HOST:-redis-host}"

read -rp "Enter REDIS_PORT [6380]: " REDIS_PORT
REDIS_PORT="${REDIS_PORT:-6380}"

read -rsp "Enter REDIS_PASSWORD: " REDIS_PASSWORD
echo ""
REDIS_PASSWORD="${REDIS_PASSWORD:-}"

read -rp "Enter REDIS_SSL [true]: " REDIS_SSL
REDIS_SSL="${REDIS_SSL:-true}"

read -rp "Enter PAYMENT_API_URL [http://payment-service:9090/payments/charge]: " PAYMENT_API_URL
PAYMENT_API_URL="${PAYMENT_API_URL:-http://payment-service:9090/payments/charge}"

read -rp "Enter APP_PAYMENT_ENDPOINT [http://payment-svc:9090/charge]: " APP_PAYMENT_ENDPOINT
APP_PAYMENT_ENDPOINT="${APP_PAYMENT_ENDPOINT:-http://payment-svc:9090/charge}"

read -rp "Enter APP_INVENTORY_ENDPOINT [http://inventory-svc:8081/rooms]: " APP_INVENTORY_ENDPOINT
APP_INVENTORY_ENDPOINT="${APP_INVENTORY_ENDPOINT:-http://inventory-svc:8081/rooms}"

read -rp "Enter APP_NOTIFICATION_ENDPOINT [http://notify-svc:7070/send]: " APP_NOTIFICATION_ENDPOINT
APP_NOTIFICATION_ENDPOINT="${APP_NOTIFICATION_ENDPOINT:-http://notify-svc:7070/send}"

read -rp "Enter REPORT_BASE_PATH [/app/reports/]: " REPORT_BASE_PATH
REPORT_BASE_PATH="${REPORT_BASE_PATH:-/app/reports/}"

echo ""

# ---------------------------------------------------------------------------
# Substitute placeholders in Kubernetes manifests (pipe delimiter)
# ---------------------------------------------------------------------------
echo "Updating Kubernetes manifests with provided values ..."

# Work on copies to avoid modifying originals
cp -r kubernetes kubernetes_deploy_tmp

sed -i "s|{{IMAGE_URI}}|${IMAGE_URI}|g"                                   kubernetes_deploy_tmp/deployment.yaml
sed -i "s|{{SPRING_DATASOURCE_URL}}|${SPRING_DATASOURCE_URL}|g"           kubernetes_deploy_tmp/deployment.yaml
sed -i "s|{{SPRING_DATASOURCE_USERNAME}}|${SPRING_DATASOURCE_USERNAME}|g" kubernetes_deploy_tmp/deployment.yaml
sed -i "s|{{SPRING_DATASOURCE_PASSWORD}}|${SPRING_DATASOURCE_PASSWORD}|g" kubernetes_deploy_tmp/deployment.yaml
sed -i "s|{{REDIS_HOST}}|${REDIS_HOST}|g"                                 kubernetes_deploy_tmp/deployment.yaml
sed -i "s|{{REDIS_PORT}}|${REDIS_PORT}|g"                                 kubernetes_deploy_tmp/deployment.yaml
sed -i "s|{{REDIS_PASSWORD}}|${REDIS_PASSWORD}|g"                         kubernetes_deploy_tmp/deployment.yaml
sed -i "s|{{REDIS_SSL}}|${REDIS_SSL}|g"                                   kubernetes_deploy_tmp/deployment.yaml
sed -i "s|{{PAYMENT_API_URL}}|${PAYMENT_API_URL}|g"                       kubernetes_deploy_tmp/deployment.yaml
sed -i "s|{{APP_PAYMENT_ENDPOINT}}|${APP_PAYMENT_ENDPOINT}|g"             kubernetes_deploy_tmp/deployment.yaml
sed -i "s|{{APP_INVENTORY_ENDPOINT}}|${APP_INVENTORY_ENDPOINT}|g"         kubernetes_deploy_tmp/deployment.yaml
sed -i "s|{{APP_NOTIFICATION_ENDPOINT}}|${APP_NOTIFICATION_ENDPOINT}|g"   kubernetes_deploy_tmp/deployment.yaml
sed -i "s|{{REPORT_BASE_PATH}}|${REPORT_BASE_PATH}|g"                     kubernetes_deploy_tmp/deployment.yaml

echo "Manifests updated."
echo ""

# ---------------------------------------------------------------------------
# Configure kubectl for AKS
# ---------------------------------------------------------------------------
echo "Configuring kubectl for AKS cluster: $CLUSTER_NAME ..."
az aks get-credentials --resource-group "$RESOURCE_GROUP" --name "$CLUSTER_NAME" --overwrite-existing
if [ $? -ne 0 ]; then
  echo "ERROR: Failed to get AKS credentials." >&2
  rm -rf kubernetes_deploy_tmp
  exit 1
fi

echo "Verifying cluster connectivity ..."
kubectl cluster-info || { echo "ERROR: Cannot connect to AKS cluster." >&2; rm -rf kubernetes_deploy_tmp; exit 1; }
echo ""

# ---------------------------------------------------------------------------
# Apply manifests in order
# ---------------------------------------------------------------------------
echo "Applying Kubernetes manifests ..."

echo "  [1/4] Applying namespace ..."
kubectl apply -f kubernetes_deploy_tmp/namespace.yaml

echo "  [2/4] Applying deployment ..."
kubectl apply -f kubernetes_deploy_tmp/deployment.yaml

echo "  [3/4] Applying service ..."
kubectl apply -f kubernetes_deploy_tmp/service.yaml

echo "  [4/4] Applying ingress ..."
kubectl apply -f kubernetes_deploy_tmp/ingress.yaml

echo ""

# ---------------------------------------------------------------------------
# Wait for rollout
# ---------------------------------------------------------------------------
echo "Waiting for deployment rollout ..."
kubectl rollout status deployment/${APP_NAME} -n ${NAMESPACE} --timeout=300s
if [ $? -ne 0 ]; then
  echo "ERROR: Deployment rollout failed. Rolling back ..." >&2
  kubectl rollout undo deployment/${APP_NAME} -n ${NAMESPACE}
  rm -rf kubernetes_deploy_tmp
  exit 1
fi

echo ""

# ---------------------------------------------------------------------------
# Verify resources
# ---------------------------------------------------------------------------
echo "Verifying deployed resources ..."
kubectl get pods,svc,ingress -n ${NAMESPACE}
echo ""

# ---------------------------------------------------------------------------
# Display application URL
# ---------------------------------------------------------------------------
INGRESS_IP=$(kubectl get ingress resortslite-ingress -n ${NAMESPACE} -o jsonpath='{.status.loadBalancer.ingress[0].ip}' 2>/dev/null || echo "")
INGRESS_HOST=$(kubectl get ingress resortslite-ingress -n ${NAMESPACE} -o jsonpath='{.spec.rules[0].host}' 2>/dev/null || echo "resortslite.example.com")

echo "=============================================="
echo "  Deployment Successful!"
echo "=============================================="
echo "  Namespace : ${NAMESPACE}"
echo "  Image     : ${IMAGE_URI}"
if [ -n "$INGRESS_IP" ]; then
  echo "  App URL   : http://${INGRESS_IP}"
fi
echo "  Host      : http://${INGRESS_HOST}"
echo "  Health    : http://${INGRESS_HOST}/actuator/health"
echo ""
echo "  Rollback  : kubectl rollout undo deployment/${APP_NAME} -n ${NAMESPACE}"
echo "=============================================="

# Clean up temporary manifests
rm -rf kubernetes_deploy_tmp
