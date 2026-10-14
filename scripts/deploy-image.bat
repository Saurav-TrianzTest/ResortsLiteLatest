@echo off
setlocal enabledelayedexpansion

:: =============================================================================
:: deploy-image.bat — Deploy ResortsLite to AWS EKS (Windows)
:: Usage: scripts\deploy-image.bat   (run from repository root)
:: =============================================================================

set "APP_NAME=resortsLite"
set "NAMESPACE=resortsLite"
set "K8S_DIR=kubernetes"

echo =============================================
echo   ResortsLite -- AWS EKS Deployment
echo =============================================
echo.

:: ---------------------------------------------------------------------------
:: Collect deployment inputs
:: ---------------------------------------------------------------------------
set /p "AWS_REGION=Enter AWS region (e.g. us-east-1): "
if "!AWS_REGION!"=="" (
    echo ERROR: AWS region is required.
    exit /b 1
)

set /p "CLUSTER_NAME=Enter EKS cluster name: "
if "!CLUSTER_NAME!"=="" (
    echo ERROR: EKS cluster name is required.
    exit /b 1
)

set /p "IMAGE_URI=Enter full Docker image URI: "
if "!IMAGE_URI!"=="" (
    echo ERROR: Docker image URI is required.
    exit /b 1
)

echo.
echo --- Application Environment Variables ---
echo Press Enter to skip any variable.
echo.

set /p "SPRING_DATASOURCE_URL=Enter SPRING_DATASOURCE_URL: "
set /p "SPRING_DATASOURCE_USERNAME=Enter SPRING_DATASOURCE_USERNAME: "
set /p "SPRING_DATASOURCE_PASSWORD=Enter SPRING_DATASOURCE_PASSWORD: "
set /p "REDIS_HOST=Enter REDIS_HOST (ElastiCache endpoint): "
set /p "REDIS_PORT=Enter REDIS_PORT [6379]: "
if "!REDIS_PORT!"=="" set "REDIS_PORT=6379"
set /p "CACHE_TTL_MINUTES=Enter CACHE_TTL_MINUTES [30]: "
if "!CACHE_TTL_MINUTES!"=="" set "CACHE_TTL_MINUTES=30"
set /p "APP_PAYMENT_ENDPOINT=Enter APP_PAYMENT_ENDPOINT: "
set /p "APP_INVENTORY_ENDPOINT=Enter APP_INVENTORY_ENDPOINT: "
set /p "APP_NOTIFICATION_ENDPOINT=Enter APP_NOTIFICATION_ENDPOINT: "
set /p "REPORT_BASE_PATH=Enter REPORT_BASE_PATH [/var/reports]: "
if "!REPORT_BASE_PATH!"=="" set "REPORT_BASE_PATH=/var/reports"

:: ---------------------------------------------------------------------------
:: Configure kubectl for EKS
:: ---------------------------------------------------------------------------
echo.
echo Configuring kubectl for EKS cluster: !CLUSTER_NAME! ...
aws eks update-kubeconfig --region !AWS_REGION! --name !CLUSTER_NAME!
if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to configure kubectl.
    exit /b 1
)

echo Verifying cluster connectivity...
kubectl cluster-info
if !ERRORLEVEL! neq 0 (
    echo ERROR: Cannot connect to EKS cluster.
    exit /b 1
)

:: ---------------------------------------------------------------------------
:: Prepare a working copy of manifests in a temp directory
:: ---------------------------------------------------------------------------
echo.
echo Preparing Kubernetes manifests...
set "TMP_DIR=%TEMP%\resortsLite-deploy-%RANDOM%"
mkdir "!TMP_DIR!"
xcopy /E /I /Q "!K8S_DIR!" "!TMP_DIR!" >nul

set "DEPLOYMENT_YAML=!TMP_DIR!\deployment.yaml"

:: Use PowerShell to perform sed-like replacements
powershell -NoProfile -Command ^
  "(Get-Content '!DEPLOYMENT_YAML!') ^
   -replace '{{IMAGE_URI}}','!IMAGE_URI!' ^
   -replace '{{SPRING_DATASOURCE_URL}}','!SPRING_DATASOURCE_URL!' ^
   -replace '{{SPRING_DATASOURCE_USERNAME}}','!SPRING_DATASOURCE_USERNAME!' ^
   -replace '{{SPRING_DATASOURCE_PASSWORD}}','!SPRING_DATASOURCE_PASSWORD!' ^
   -replace '{{REDIS_HOST}}','!REDIS_HOST!' ^
   -replace '{{REDIS_PORT}}','!REDIS_PORT!' ^
   -replace '{{CACHE_TTL_MINUTES}}','!CACHE_TTL_MINUTES!' ^
   -replace '{{APP_PAYMENT_ENDPOINT}}','!APP_PAYMENT_ENDPOINT!' ^
   -replace '{{APP_INVENTORY_ENDPOINT}}','!APP_INVENTORY_ENDPOINT!' ^
   -replace '{{APP_NOTIFICATION_ENDPOINT}}','!APP_NOTIFICATION_ENDPOINT!' ^
   -replace '{{REPORT_BASE_PATH}}','!REPORT_BASE_PATH!' ^
   | Set-Content '!DEPLOYMENT_YAML!'"
if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to substitute placeholders in deployment.yaml.
    exit /b 1
)

:: ---------------------------------------------------------------------------
:: Apply manifests in order
:: ---------------------------------------------------------------------------
echo.
echo Applying Kubernetes manifests...

echo   [1/4] Namespace...
kubectl apply -f "!TMP_DIR!\namespace.yaml"
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply namespace. & exit /b 1 )

echo   [2/4] Deployment...
kubectl apply -f "!DEPLOYMENT_YAML!"
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply deployment. & exit /b 1 )

echo   [3/4] Service...
kubectl apply -f "!TMP_DIR!\service.yaml"
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply service. & exit /b 1 )

echo   [4/4] Ingress...
kubectl apply -f "!TMP_DIR!\ingress.yaml"
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply ingress. & exit /b 1 )

:: ---------------------------------------------------------------------------
:: Wait for rollout
:: ---------------------------------------------------------------------------
echo.
echo Waiting for deployment rollout...
kubectl rollout status deployment/!APP_NAME! -n !NAMESPACE! --timeout=300s
if !ERRORLEVEL! neq 0 (
    echo ERROR: Deployment rollout did not complete successfully.
    echo Rollback command: kubectl rollout undo deployment/!APP_NAME! -n !NAMESPACE!
    exit /b 1
)

:: ---------------------------------------------------------------------------
:: Verify
:: ---------------------------------------------------------------------------
echo.
echo Deployed resources:
kubectl get pods,svc,ingress -n !NAMESPACE!

:: ---------------------------------------------------------------------------
:: Cleanup temp dir
:: ---------------------------------------------------------------------------
rmdir /S /Q "!TMP_DIR!"

echo.
echo =============================================
echo   Deployment complete!
echo =============================================
echo.
echo Rollback command (if needed):
echo   kubectl rollout undo deployment/!APP_NAME! -n !NAMESPACE!

endlocal
