#!/bin/bash
set -e
set -o pipefail

echo "============================================"
echo "  ResortsLite - Deploy to Azure AKS"
echo "============================================"
echo ""

# ---- Collect Azure / AKS details ----
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
echo "---- Application Environment Variables ----"
echo "Press Enter to skip any variable (placeholder will remain in manifest)."
echo ""

read -rp "Enter SPRING_DATASOURCE_URL (Oracle JDBC URL): " SPRING_DATASOURCE_URL
read -rp "Enter SPRING_DATASOURCE_USERNAME: " SPRING_DATASOURCE_USERNAME
read -rsp "Enter SPRING_DATASOURCE_PASSWORD: " SPRING_DATASOURCE_PASSWORD
echo ""
read -rp "Enter SPRING_REDIS_HOST (Azure Cache for Redis hostname): " SPRING_REDIS_HOST
read -rp "Enter SPRING_REDIS_PORT [default: 6380]: " SPRING_REDIS_PORT
[ -z "$SPRING_REDIS_PORT" ] && SPRING_REDIS_PORT="6380"
read -rsp "Enter SPRING_REDIS_PASSWORD (Redis access key): " SPRING_REDIS_PASSWORD
echo ""
read -rp "Enter BOOKING_CACHE_TTL_SECONDS [default: 3600]: " BOOKING_CACHE_TTL_SECONDS
[ -z "$BOOKING_CACHE_TTL_SECONDS" ] && BOOKING_CACHE_TTL_SECONDS="3600"
read -rp "Enter PAYMENT_API_URL [default: http://payment-service:9090/payments/charge]: " PAYMENT_API_URL
[ -z "$PAYMENT_API_URL" ] && PAYMENT_API_URL="http://payment-service:9090/payments/charge"

echo ""
echo "---- Configuring kubectl for AKS ----"
az aks get-credentials --resource-group "$RESOURCE_GROUP" --name "$CLUSTER_NAME" --overwrite-existing
if [ $? -ne 0 ]; then
    echo "ERROR: Failed to get AKS credentials." >&2
    exit 1
fi

echo "Verifying cluster connectivity..."
kubectl cluster-info || { echo "ERROR: Cannot connect to AKS cluster." >&2; exit 1; }

echo ""
echo "---- Updating Kubernetes manifests ----"

# Replace IMAGE_URI placeholder
sed -i "s|{{IMAGE_URI}}|${IMAGE_URI}|g" kubernetes/deployment.yaml

# Replace environment variable placeholders
sed -i "s|{{SPRING_DATASOURCE_URL}}|${SPRING_DATASOURCE_URL}|g" kubernetes/deployment.yaml
sed -i "s|{{SPRING_DATASOURCE_USERNAME}}|${SPRING_DATASOURCE_USERNAME}|g" kubernetes/deployment.yaml
sed -i "s|{{SPRING_DATASOURCE_PASSWORD}}|${SPRING_DATASOURCE_PASSWORD}|g" kubernetes/deployment.yaml
sed -i "s|{{SPRING_REDIS_HOST}}|${SPRING_REDIS_HOST}|g" kubernetes/deployment.yaml
sed -i "s|{{SPRING_REDIS_PORT}}|${SPRING_REDIS_PORT}|g" kubernetes/deployment.yaml
sed -i "s|{{SPRING_REDIS_PASSWORD}}|${SPRING_REDIS_PASSWORD}|g" kubernetes/deployment.yaml
sed -i "s|{{BOOKING_CACHE_TTL_SECONDS}}|${BOOKING_CACHE_TTL_SECONDS}|g" kubernetes/deployment.yaml
sed -i "s|{{PAYMENT_API_URL}}|${PAYMENT_API_URL}|g" kubernetes/deployment.yaml

echo ""
echo "---- Applying Kubernetes manifests ----"
kubectl apply -f kubernetes/namespace.yaml
echo "  [OK] Namespace applied"

kubectl apply -f kubernetes/deployment.yaml
echo "  [OK] Deployment applied"

kubectl apply -f kubernetes/service.yaml
echo "  [OK] Service applied"

kubectl apply -f kubernetes/ingress.yaml
echo "  [OK] Ingress applied"

echo ""
echo "---- Waiting for deployment rollout ----"
kubectl rollout status deployment/resortslite -n resortslite --timeout=300s
if [ $? -ne 0 ]; then
    echo "ERROR: Deployment rollout failed. Check pod logs:" >&2
    kubectl describe pods -n resortslite >&2
    echo ""
    echo "To rollback: kubectl rollout undo deployment/resortslite -n resortslite"
    exit 1
fi

echo ""
echo "---- Verifying deployed resources ----"
kubectl get pods,svc,ingress -n resortslite

echo ""
echo "---- Application Access ----"
INGRESS_IP=$(kubectl get ingress resortslite-ingress -n resortslite -o jsonpath='{.status.loadBalancer.ingress[0].ip}' 2>/dev/null || echo "pending")
echo "  Ingress IP  : $INGRESS_IP"
echo "  Application : http://resortslite.example.com"
echo "  Health Check: http://resortslite.example.com/actuator/health"
echo ""
echo "============================================"
echo "  SUCCESS: ResortsLite deployed to AKS!"
echo "============================================"
echo ""
echo "Rollback command (if needed):"
echo "  kubectl rollout undo deployment/resortslite -n resortslite"
