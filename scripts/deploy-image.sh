#!/bin/bash
set -e
set -o pipefail

APP_NAME="resortslite"
NAMESPACE="resortslite"

echo "============================================"
echo "  ResortsLite - Deploy to Azure AKS"
echo "============================================"
echo ""

# ── Azure / AKS credentials ──────────────────────────────────────────────────
read -rp "Enter Azure Resource Group name: " RESOURCE_GROUP
if [ -z "$RESOURCE_GROUP" ]; then
  echo "ERROR: Resource group cannot be empty."
  exit 1
fi

read -rp "Enter AKS Cluster name: " CLUSTER_NAME
if [ -z "$CLUSTER_NAME" ]; then
  echo "ERROR: AKS cluster name cannot be empty."
  exit 1
fi

# ── Docker image URI ──────────────────────────────────────────────────────────
read -rp "Enter full Docker image URI (e.g. myregistry.azurecr.io/resortslite:latest): " IMAGE_URI
if [ -z "$IMAGE_URI" ]; then
  echo "ERROR: Image URI cannot be empty."
  exit 1
fi

# ── Application environment variables ────────────────────────────────────────
echo ""
echo "--- Application Environment Variables ---"
echo "(Press Enter to keep placeholder value)"

read -rp "Enter REDIS_HOST (Azure Cache for Redis hostname): " REDIS_HOST_VAL
[ -z "$REDIS_HOST_VAL" ] && REDIS_HOST_VAL="{{REDIS_HOST}}"

read -rp "Enter REDIS_PORT [6380]: " REDIS_PORT_VAL
[ -z "$REDIS_PORT_VAL" ] && REDIS_PORT_VAL="6380"

read -rsp "Enter REDIS_PASSWORD: " REDIS_PASSWORD_VAL
echo ""
[ -z "$REDIS_PASSWORD_VAL" ] && REDIS_PASSWORD_VAL="{{REDIS_PASSWORD}}"

read -rp "Enter REDIS_SSL [true]: " REDIS_SSL_VAL
[ -z "$REDIS_SSL_VAL" ] && REDIS_SSL_VAL="true"

read -rp "Enter PAYMENT_API_URL [http://payment-service:9090/payments/charge]: " PAYMENT_API_URL_VAL
[ -z "$PAYMENT_API_URL_VAL" ] && PAYMENT_API_URL_VAL="http://payment-service:9090/payments/charge"

# ── Configure kubectl ─────────────────────────────────────────────────────────
echo ""
echo "Configuring kubectl for AKS cluster: $CLUSTER_NAME ..."
az aks get-credentials --resource-group "$RESOURCE_GROUP" --name "$CLUSTER_NAME" --overwrite-existing

echo "Verifying cluster connectivity ..."
kubectl cluster-info || { echo "ERROR: Cannot connect to AKS cluster."; exit 1; }

# ── Patch manifests ───────────────────────────────────────────────────────────
echo ""
echo "Updating Kubernetes manifests with deployment values ..."

# Work on copies to avoid modifying originals
cp -r kubernetes kubernetes_deploy_tmp

sed -i "s|{{IMAGE_URI}}|${IMAGE_URI}|g"                   kubernetes_deploy_tmp/deployment.yaml
sed -i "s|{{REDIS_HOST}}|${REDIS_HOST_VAL}|g"             kubernetes_deploy_tmp/deployment.yaml
sed -i "s|{{REDIS_PORT}}|${REDIS_PORT_VAL}|g"             kubernetes_deploy_tmp/deployment.yaml
sed -i "s|{{REDIS_PASSWORD}}|${REDIS_PASSWORD_VAL}|g"     kubernetes_deploy_tmp/deployment.yaml
sed -i "s|{{REDIS_SSL}}|${REDIS_SSL_VAL}|g"               kubernetes_deploy_tmp/deployment.yaml
sed -i "s|{{PAYMENT_API_URL}}|${PAYMENT_API_URL_VAL}|g"   kubernetes_deploy_tmp/deployment.yaml

# ── Apply manifests ───────────────────────────────────────────────────────────
echo ""
echo "Applying Kubernetes manifests ..."

echo "  [1/4] Applying namespace ..."
kubectl apply -f kubernetes_deploy_tmp/namespace.yaml

echo "  [2/4] Applying deployment ..."
kubectl apply -f kubernetes_deploy_tmp/deployment.yaml

echo "  [3/4] Applying service ..."
kubectl apply -f kubernetes_deploy_tmp/service.yaml

echo "  [4/4] Applying ingress ..."
kubectl apply -f kubernetes_deploy_tmp/ingress.yaml

# ── Wait for rollout ──────────────────────────────────────────────────────────
echo ""
echo "Waiting for deployment rollout ..."
kubectl rollout status deployment/${APP_NAME} -n ${NAMESPACE} --timeout=300s

# ── Verify resources ──────────────────────────────────────────────────────────
echo ""
echo "Verifying deployed resources ..."
kubectl get pods,svc,ingress -n ${NAMESPACE}

# ── Display access URL ────────────────────────────────────────────────────────
echo ""
INGRESS_IP=$(kubectl get ingress ${APP_NAME}-ingress -n ${NAMESPACE} -o jsonpath='{.status.loadBalancer.ingress[0].ip}' 2>/dev/null || echo "pending")
echo "============================================"
echo "  Deployment Complete!"
echo "  Namespace : ${NAMESPACE}"
echo "  Image     : ${IMAGE_URI}"
echo "  Ingress IP: ${INGRESS_IP}"
echo "  App URL   : http://resortslite.example.com"
echo "============================================"
echo ""
echo "Rollback command (if needed):"
echo "  kubectl rollout undo deployment/${APP_NAME} -n ${NAMESPACE}"

# ── Cleanup temp manifests ────────────────────────────────────────────────────
rm -rf kubernetes_deploy_tmp
