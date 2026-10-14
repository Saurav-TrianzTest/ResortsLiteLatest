@echo off
setlocal enabledelayedexpansion

:: =============================================================================
:: build-push.bat – Build and push the ResortsLite Docker image (Windows)
:: Usage: scripts\build-push.bat
:: Run from the repository root directory.
:: =============================================================================

set "PROJECT_NAME=resortslite"

echo ==============================================
echo   ResortsLite - Docker Build ^& Push
echo ==============================================
echo.

:: ---------------------------------------------------------------------------
:: Sanitise image name via PowerShell
:: ---------------------------------------------------------------------------
for /f "delims=" %%I in ('powershell -NoProfile -Command "$n = 'resortslite'; $n = $n.ToLower() -replace '[^a-z0-9]+','-'; $n = $n.Trim('-'); Write-Output $n"') do set "IMAGE_NAME=%%I"

:: ---------------------------------------------------------------------------
:: Prompt for image tag
:: ---------------------------------------------------------------------------
set /p "IMAGE_TAG_INPUT=Enter image tag [latest]: "
if "!IMAGE_TAG_INPUT!"=="" set "IMAGE_TAG_INPUT=latest"
for /f "delims=" %%T in ('powershell -NoProfile -Command "$t = '!IMAGE_TAG_INPUT!'; $t = $t.ToLower() -replace '[^a-z0-9._-]+','-'; $t = $t.Trim('-'); if ($t -eq '') { $t = 'latest' }; Write-Output $t"') do set "IMAGE_TAG=%%T"

echo.
echo Image name : !IMAGE_NAME!
echo Image tag  : !IMAGE_TAG!
echo.

:: ---------------------------------------------------------------------------
:: Registry selection
:: ---------------------------------------------------------------------------
echo Select container registry:
echo   1. Azure Container Registry (ACR)
echo   2. Docker Hub
set /p "REGISTRY_CHOICE=Enter choice [1]: "
if "!REGISTRY_CHOICE!"=="" set "REGISTRY_CHOICE=1"

if "!REGISTRY_CHOICE!"=="1" (
    set /p "ACR_NAME=Enter ACR name (e.g. myregistry): "
    if "!ACR_NAME!"=="" (
        echo ERROR: ACR name cannot be empty.
        exit /b 1
    )
    set "REGISTRY=!ACR_NAME!.azurecr.io"
    set "FULL_IMAGE_NAME=!REGISTRY!/!IMAGE_NAME!:!IMAGE_TAG!"

    echo.
    echo Logging in to Azure Container Registry: !ACR_NAME! ...
    az acr login --name !ACR_NAME!
    if !ERRORLEVEL! neq 0 (
        echo ERROR: ACR login failed.
        exit /b 1
    )
) else if "!REGISTRY_CHOICE!"=="2" (
    set /p "DOCKER_USERNAME=Enter Docker Hub username: "
    if "!DOCKER_USERNAME!"=="" (
        echo ERROR: Docker Hub username cannot be empty.
        exit /b 1
    )
    set /p "DOCKER_PASSWORD=Enter Docker Hub password/token: "
    if "!DOCKER_PASSWORD!"=="" (
        echo ERROR: Docker Hub password cannot be empty.
        exit /b 1
    )
    set "FULL_IMAGE_NAME=!DOCKER_USERNAME!/!IMAGE_NAME!:!IMAGE_TAG!"

    echo.
    echo Logging in to Docker Hub ...
    echo !DOCKER_PASSWORD! | docker login --username !DOCKER_USERNAME! --password-stdin
    if !ERRORLEVEL! neq 0 (
        echo ERROR: Docker Hub login failed.
        exit /b 1
    )
) else (
    echo ERROR: Invalid registry choice '!REGISTRY_CHOICE!'.
    exit /b 1
)

echo.
echo Full image : !FULL_IMAGE_NAME!
echo.

:: ---------------------------------------------------------------------------
:: Build
:: ---------------------------------------------------------------------------
echo Building Docker image ...
docker build -f Dockerfile -t "!FULL_IMAGE_NAME!" .
if !ERRORLEVEL! neq 0 (
    echo ERROR: Docker build failed.
    exit /b 1
)
echo Build successful.
echo.

:: ---------------------------------------------------------------------------
:: Push
:: ---------------------------------------------------------------------------
echo Pushing image to registry ...
docker push "!FULL_IMAGE_NAME!"
if !ERRORLEVEL! neq 0 (
    echo ERROR: Docker push failed.
    exit /b 1
)

echo.
echo ==============================================
echo   Image pushed successfully!
echo   !FULL_IMAGE_NAME!
echo ==============================================

endlocal
