#!/bin/bash
set -e

PROJECT_NAME="resortslite"
IMAGE_NAME=$(echo "$PROJECT_NAME" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9' '-' | sed 's/^-*//;s/-*$//')

echo "============================================"
echo "  ResortsLite - Docker Build & Push Script"
echo "============================================"
echo ""
echo "Select container registry:"
echo "  1. Azure Container Registry (ACR)"
echo "  2. Docker Hub"
echo ""
read -rp "Enter choice [1 or 2]: " REGISTRY_CHOICE

if [ "$REGISTRY_CHOICE" = "1" ]; then
    # ---- Azure ACR ----
    read -rp "Enter ACR name (e.g. myregistry): " ACR_NAME
    ACR_NAME=$(echo "$ACR_NAME" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9' '-' | sed 's/^-*//;s/-*$//')
    REGISTRY="${ACR_NAME}.azurecr.io"

    read -rp "Enter image tag [default: latest]: " IMAGE_TAG
    IMAGE_TAG=$(echo "$IMAGE_TAG" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9._-' '-' | sed 's/^-*//;s/-*$//')
    [ -z "$IMAGE_TAG" ] && IMAGE_TAG="latest"

    FULL_IMAGE_NAME="${REGISTRY}/${IMAGE_NAME}:${IMAGE_TAG}"

    echo ""
    echo "Logging in to ACR: $REGISTRY"
    az acr login --name "$ACR_NAME"
    if [ $? -ne 0 ]; then
        echo "ERROR: ACR login failed." >&2
        exit 1
    fi

elif [ "$REGISTRY_CHOICE" = "2" ]; then
    # ---- Docker Hub ----
    read -rp "Enter Docker Hub username: " DOCKER_USERNAME
    read -rsp "Enter Docker Hub password/token: " DOCKER_PASSWORD
    echo ""

    read -rp "Enter image tag [default: latest]: " IMAGE_TAG
    IMAGE_TAG=$(echo "$IMAGE_TAG" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9._-' '-' | sed 's/^-*//;s/-*$//')
    [ -z "$IMAGE_TAG" ] && IMAGE_TAG="latest"

    FULL_IMAGE_NAME="${DOCKER_USERNAME}/${IMAGE_NAME}:${IMAGE_TAG}"

    echo ""
    echo "Logging in to Docker Hub..."
    echo "$DOCKER_PASSWORD" | docker login --username "$DOCKER_USERNAME" --password-stdin
    if [ $? -ne 0 ]; then
        echo "ERROR: Docker Hub login failed." >&2
        exit 1
    fi

else
    echo "ERROR: Invalid choice. Please enter 1 or 2." >&2
    exit 1
fi

echo ""
echo "Building Docker image: $FULL_IMAGE_NAME"
docker build -f Dockerfile -t "$FULL_IMAGE_NAME" .
if [ $? -ne 0 ]; then
    echo "ERROR: Docker build failed." >&2
    exit 1
fi

echo ""
echo "Pushing image: $FULL_IMAGE_NAME"
docker push "$FULL_IMAGE_NAME"
if [ $? -ne 0 ]; then
    echo "ERROR: Docker push failed." >&2
    exit 1
fi

echo ""
echo "============================================"
echo "  SUCCESS: Image pushed successfully!"
echo "  Image: $FULL_IMAGE_NAME"
echo "============================================"
