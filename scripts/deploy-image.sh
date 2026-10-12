#!/bin/bash
set -e
set -o pipefail

echo "=============================================="
echo "  ResortsLite - Deploy to AWS EKS"
echo "=============================================="

# ---- Collect deployment inputs ----
echo ""
read -rp "Enter AWS Region (e.g. us-east-1): " AWS_REGION
if [ -z "$AWS_REGION" ]; then
  echo "ERROR: AWS Region is required."
  exit 1
fi

read -rp "Enter EKS Cluster Name: " CLUSTER_NAME
if [ -z "$CLUSTER_NAME" ]; then
  echo "ERROR: EKS Cluster Name is required."
  exit 1
fi

read -rp "Enter full Docker image URI (e.g. 123456789.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest): " IMAGE_URI
if [ -z "$IMAGE_URI" ]; then
  echo "ERROR: Docker image URI is required."
  exit 1
fi

echo ""
echo "---- Optional: Application Environment Variables ----"
echo "Press Enter to skip any variable and keep the placeholder."
echo ""

read -rp "Enter SPRING_DATASOURCE_URL (e.g. jdbc:oracle:thin:@host:1521:ORCL): " SPRING_DATASOURCE_URL
read -rp "Enter SPRING_DATASOURCE_USERNAME: " SPRING_DATASOURCE_USERNAME
read -rsp "Enter SPRING_DATASOURCE_PASSWORD: " SPRING_DATASOURCE_PASSWORD
echo ""
read -rp "Enter REDIS_HOST (ElastiCache endpoint): " REDIS_HOST
read -rp "Enter REDIS_PORT (default 6379): " REDIS_PORT
read -rsp "Enter REDIS_PASSWORD (leave blank if none): " REDIS_PASSWORD
echo ""
read -rp "Enter PAYMENT_API_URL: " PAYMENT_API_URL
read -rp "Enter APP_PAYMENT_ENDPOINT: " APP_PAYMENT_ENDPOINT
read -rp "Enter APP_INVENTORY_ENDPOINT: " APP_INVENTORY_ENDPOINT
read -rp "Enter APP_NOTIFICATION_ENDPOINT: " APP_NOTIFICATION_ENDPOINT

# ---- Configure kubectl for EKS ----
echo ""
echo "Configuring kubectl for EKS cluster: $CLUSTER_NAME in $AWS_REGION ..."
aws eks update-kubeconfig --region "$AWS_REGION" --name "$CLUSTER_NAME"

echo "Verifying cluster connectivity..."
kubectl cluster-info || { echo "ERROR: Cannot connect to EKS cluster."; exit 1; }

# ---- Substitute placeholders in manifests ----
echo ""
echo "Updating Kubernetes manifests with deployment values..."

# Work on copies to avoid modifying originals
cp kubernetes/deployment.yaml kubernetes/deployment.yaml.bak

sed -i 's|{{IMAGE_URI}}|'"$IMAGE_URI"'|g' kubernetes/deployment.yaml

if [ -n "$SPRING_DATASOURCE_URL" ]; then
  sed -i 's|{{SPRING_DATASOURCE_URL}}|'"$SPRING_DATASOURCE_URL"'|g' kubernetes/deployment.yaml
fi
if [ -n "$SPRING_DATASOURCE_USERNAME" ]; then
  sed -i 's|{{SPRING_DATASOURCE_USERNAME}}|'"$SPRING_DATASOURCE_USERNAME"'|g' kubernetes/deployment.yaml
fi
if [ -n "$SPRING_DATASOURCE_PASSWORD" ]; then
  sed -i 's|{{SPRING_DATASOURCE_PASSWORD}}|'"$SPRING_DATASOURCE_PASSWORD"'|g' kubernetes/deployment.yaml
fi
if [ -n "$REDIS_HOST" ]; then
  sed -i 's|{{REDIS_HOST}}|'"$REDIS_HOST"'|g' kubernetes/deployment.yaml
fi
if [ -n "$REDIS_PORT" ]; then
  sed -i 's|{{REDIS_PORT}}|'"$REDIS_PORT"'|g' kubernetes/deployment.yaml
else
  sed -i 's|{{REDIS_PORT}}|6379|g' kubernetes/deployment.yaml
fi
if [ -n "$REDIS_PASSWORD" ]; then
  sed -i 's|{{REDIS_PASSWORD}}|'"$REDIS_PASSWORD"'|g' kubernetes/deployment.yaml
else
  sed -i 's|{{REDIS_PASSWORD}}||g' kubernetes/deployment.yaml
fi
if [ -n "$PAYMENT_API_URL" ]; then
  sed -i 's|{{PAYMENT_API_URL}}|'"$PAYMENT_API_URL"'|g' kubernetes/deployment.yaml
fi
if [ -n "$APP_PAYMENT_ENDPOINT" ]; then
  sed -i 's|{{APP_PAYMENT_ENDPOINT}}|'"$APP_PAYMENT_ENDPOINT"'|g' kubernetes/deployment.yaml
fi
if [ -n "$APP_INVENTORY_ENDPOINT" ]; then
  sed -i 's|{{APP_INVENTORY_ENDPOINT}}|'"$APP_INVENTORY_ENDPOINT"'|g' kubernetes/deployment.yaml
fi
if [ -n "$APP_NOTIFICATION_ENDPOINT" ]; then
  sed -i 's|{{APP_NOTIFICATION_ENDPOINT}}|'"$APP_NOTIFICATION_ENDPOINT"'|g' kubernetes/deployment.yaml
fi

# ---- Apply manifests in order ----
echo ""
echo "Applying Kubernetes manifests..."

echo "  [1/4] Applying namespace..."
kubectl apply -f kubernetes/namespace.yaml

echo "  [2/4] Applying deployment..."
kubectl apply -f kubernetes/deployment.yaml

echo "  [3/4] Applying service..."
kubectl apply -f kubernetes/service.yaml

echo "  [4/4] Applying ingress..."
kubectl apply -f kubernetes/ingress.yaml

# ---- Restore original deployment.yaml ----
mv kubernetes/deployment.yaml.bak kubernetes/deployment.yaml

# ---- Wait for rollout ----
echo ""
echo "Waiting for deployment rollout..."
kubectl rollout status deployment/resortslite -n resortslite --timeout=300s

# ---- Verify resources ----
echo ""
echo "Verifying deployed resources..."
kubectl get pods,svc,ingress -n resortslite

# ---- Display access URL ----
echo ""
echo "Fetching application ingress URL..."
INGRESS_HOST=$(kubectl get ingress resortslite-ingress -n resortslite \
  -o jsonpath='{.status.loadBalancer.ingress[0].hostname}' 2>/dev/null || echo "pending")

echo ""
echo "=============================================="
echo "  Deployment Complete!"
echo "  Cluster  : $CLUSTER_NAME"
echo "  Region   : $AWS_REGION"
echo "  Image    : $IMAGE_URI"
if [ "$INGRESS_HOST" != "pending" ] && [ -n "$INGRESS_HOST" ]; then
  echo "  App URL  : http://$INGRESS_HOST"
else
  echo "  App URL  : (Ingress hostname pending - check 'kubectl get ingress -n resortslite')"
fi
echo "=============================================="
echo ""
echo "Rollback command (if needed):"
echo "  kubectl rollout undo deployment/resortslite -n resortslite"
