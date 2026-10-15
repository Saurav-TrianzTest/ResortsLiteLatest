@echo off
setlocal enabledelayedexpansion

set PROJECT_NAME=resortslite
set IMAGE_NAME=resortslite

echo ============================================
echo   ResortsLite - Docker Build ^& Push Script
echo ============================================
echo.
echo Select container registry:
echo   1. Azure Container Registry (ACR)
echo   2. Docker Hub
echo.
set /p REGISTRY_CHOICE="Enter choice [1 or 2]: "

if "!REGISTRY_CHOICE!"=="1" (
    rem ---- Azure ACR ----
    set /p ACR_NAME="Enter ACR name (e.g. myregistry): "
    set /p IMAGE_TAG="Enter image tag [default: latest]: "

    if "!IMAGE_TAG!"=="" set IMAGE_TAG=latest

    set REGISTRY=!ACR_NAME!.azurecr.io
    set FULL_IMAGE_NAME=!REGISTRY!/!IMAGE_NAME!:!IMAGE_TAG!

    echo.
    echo Logging in to ACR: !REGISTRY!
    az acr login --name !ACR_NAME!
    if !ERRORLEVEL! neq 0 (
        echo ERROR: ACR login failed.
        exit /b 1
    )

) else if "!REGISTRY_CHOICE!"=="2" (
    rem ---- Docker Hub ----
    set /p DOCKER_USERNAME="Enter Docker Hub username: "
    set /p DOCKER_PASSWORD="Enter Docker Hub password/token: "
    set /p IMAGE_TAG="Enter image tag [default: latest]: "

    if "!IMAGE_TAG!"=="" set IMAGE_TAG=latest

    set FULL_IMAGE_NAME=!DOCKER_USERNAME!/!IMAGE_NAME!:!IMAGE_TAG!

    echo.
    echo Logging in to Docker Hub...
    echo !DOCKER_PASSWORD! | docker login --username !DOCKER_USERNAME! --password-stdin
    if !ERRORLEVEL! neq 0 (
        echo ERROR: Docker Hub login failed.
        exit /b 1
    )

) else (
    echo ERROR: Invalid choice. Please enter 1 or 2.
    exit /b 1
)

echo.
echo Building Docker image: !FULL_IMAGE_NAME!
docker build -f Dockerfile -t !FULL_IMAGE_NAME! .
if !ERRORLEVEL! neq 0 (
    echo ERROR: Docker build failed.
    exit /b 1
)

echo.
echo Pushing image: !FULL_IMAGE_NAME!
docker push !FULL_IMAGE_NAME!
if !ERRORLEVEL! neq 0 (
    echo ERROR: Docker push failed.
    exit /b 1
)

echo.
echo ============================================
echo   SUCCESS: Image pushed successfully!
echo   Image: !FULL_IMAGE_NAME!
echo ============================================

endlocal
