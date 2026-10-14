#!/usr/bin/env bash
# =============================================================================
# build-push.sh — Build and push the ResortsLite Docker image
# Supports: AWS ECR  |  Docker Hub
# Usage: bash scripts/build-push.sh   (run from repository root)
# =============================================================================
set -e
set -o pipefail

PROJECT_NAME="resortsLite"
DOCKERFILE_PATH="Dockerfile"

# ---------------------------------------------------------------------------
# Sanitise image name: lowercase, replace non-alphanumeric with hyphens,
# strip leading/trailing hyphens.
# ---------------------------------------------------------------------------
IMAGE_NAME=$(echo "$PROJECT_NAME" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9' '-' | sed 's/^-*//;s/-*$//')

echo "============================================="
echo "  ResortsLite — Docker Build & Push"
echo "============================================="
echo ""

# ---------------------------------------------------------------------------
# Registry selection
# ---------------------------------------------------------------------------
echo "Select container registry:"
echo "  1) AWS ECR"
echo "  2) Docker Hub"
echo ""
read -rp "Enter choice [1 or 2]: " REGISTRY_CHOICE

# ---------------------------------------------------------------------------
# Image tag
# ---------------------------------------------------------------------------
read -rp "Enter image tag (press Enter for 'latest'): " RAW_TAG
IMAGE_TAG=$(echo "$RAW_TAG" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9._-' '-' | sed 's/^-*//;s/-*$//')
if [ -z "$IMAGE_TAG" ]; then
  IMAGE_TAG="latest"
fi

echo ""
echo "Image name : $IMAGE_NAME"
echo "Image tag  : $IMAGE_TAG"
echo ""

# ---------------------------------------------------------------------------
# Registry-specific configuration
# ---------------------------------------------------------------------------
if [ "$REGISTRY_CHOICE" = "1" ]; then
  # ---- AWS ECR ----
  read -rp "Enter AWS region (e.g. us-east-1): " AWS_REGION
  read -rp "Enter AWS account ID: " AWS_ACCOUNT_ID

  ECR_REPO="$IMAGE_NAME"
  REGISTRY_URL="${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com"
  FULL_IMAGE_NAME="${REGISTRY_URL}/${ECR_REPO}:${IMAGE_TAG}"

  echo ""
  echo "Authenticating with AWS ECR..."
  aws ecr get-login-password --region "$AWS_REGION" | \
    docker login --username AWS --password-stdin "$REGISTRY_URL"

  echo "Ensuring ECR repository exists..."
  aws ecr describe-repositories --repository-names "$ECR_REPO" --region "$AWS_REGION" \
    >/dev/null 2>&1 || \
    aws ecr create-repository --repository-name "$ECR_REPO" --region "$AWS_REGION"

elif [ "$REGISTRY_CHOICE" = "2" ]; then
  # ---- Docker Hub ----
  read -rp "Enter Docker Hub username: " DOCKER_USERNAME
  read -rsp "Enter Docker Hub password/token: " DOCKER_PASSWORD
  echo ""

  REGISTRY_URL="docker.io"
  FULL_IMAGE_NAME="${DOCKER_USERNAME}/${IMAGE_NAME}:${IMAGE_TAG}"

  echo ""
  echo "Authenticating with Docker Hub..."
  echo "$DOCKER_PASSWORD" | docker login --username "$DOCKER_USERNAME" --password-stdin

else
  echo "ERROR: Invalid choice '$REGISTRY_CHOICE'. Exiting."
  exit 1
fi

# ---------------------------------------------------------------------------
# Build
# ---------------------------------------------------------------------------
echo ""
echo "Building Docker image: $FULL_IMAGE_NAME"
docker build -f "$DOCKERFILE_PATH" -t "$FULL_IMAGE_NAME" .

echo ""
echo "Pushing image: $FULL_IMAGE_NAME"
docker push "$FULL_IMAGE_NAME"

echo ""
echo "============================================="
echo "  Build & push complete!"
echo "  Image: $FULL_IMAGE_NAME"
echo "============================================="
