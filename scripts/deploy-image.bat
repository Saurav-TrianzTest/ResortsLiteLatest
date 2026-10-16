@echo off
setlocal enabledelayedexpansion

set "APP_NAME=resortslite"
set "NAMESPACE=resortslite"

echo ============================================
echo   ResortsLite - Deploy to Azure AKS
echo ============================================
echo.

:: ── Azure / AKS credentials ──────────────────────────────────────────────────
set /p RESOURCE_GROUP="Enter Azure Resource Group name: "
if "!RESOURCE_GROUP!"=="" (
    echo ERROR: Resource group cannot be empty.
    exit /b 1
)

set /p CLUSTER_NAME="Enter AKS Cluster name: "
if "!CLUSTER_NAME!"=="" (
    echo ERROR: AKS cluster name cannot be empty.
    exit /b 1
)

:: ── Docker image URI ──────────────────────────────────────────────────────────
set /p IMAGE_URI="Enter full Docker image URI (e.g. myregistry.azurecr.io/resortslite:latest): "
if "!IMAGE_URI!"=="" (
    echo ERROR: Image URI cannot be empty.
    exit /b 1
)

:: ── Application environment variables ────────────────────────────────────────
echo.
echo --- Application Environment Variables ---
echo (Press Enter to keep placeholder value)

set /p REDIS_HOST_VAL="Enter REDIS_HOST (Azure Cache for Redis hostname): "
if "!REDIS_HOST_VAL!"=="" set "REDIS_HOST_VAL={{REDIS_HOST}}"

set /p REDIS_PORT_VAL="Enter REDIS_PORT [6380]: "
if "!REDIS_PORT_VAL!"=="" set "REDIS_PORT_VAL=6380"

set /p REDIS_PASSWORD_VAL="Enter REDIS_PASSWORD: "
if "!REDIS_PASSWORD_VAL!"=="" set "REDIS_PASSWORD_VAL={{REDIS_PASSWORD}}"

set /p REDIS_SSL_VAL="Enter REDIS_SSL [true]: "
if "!REDIS_SSL_VAL!"=="" set "REDIS_SSL_VAL=true"

set /p PAYMENT_API_URL_VAL="Enter PAYMENT_API_URL [http://payment-service:9090/payments/charge]: "
if "!PAYMENT_API_URL_VAL!"=="" set "PAYMENT_API_URL_VAL=http://payment-service:9090/payments/charge"

:: ── Configure kubectl ─────────────────────────────────────────────────────────
echo.
echo Configuring kubectl for AKS cluster: !CLUSTER_NAME! ...
az aks get-credentials --resource-group !RESOURCE_GROUP! --name !CLUSTER_NAME! --overwrite-existing
if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to get AKS credentials.
    exit /b 1
)

echo Verifying cluster connectivity ...
kubectl cluster-info
if !ERRORLEVEL! neq 0 (
    echo ERROR: Cannot connect to AKS cluster.
    exit /b 1
)

:: ── Copy manifests to temp directory ─────────────────────────────────────────
echo.
echo Updating Kubernetes manifests with deployment values ...
if exist kubernetes_deploy_tmp rmdir /s /q kubernetes_deploy_tmp
xcopy /e /i /q kubernetes kubernetes_deploy_tmp >nul

:: ── Patch deployment.yaml using PowerShell ────────────────────────────────────
powershell -Command ^
  "(Get-Content 'kubernetes_deploy_tmp\deployment.yaml') ^
   -replace '{{IMAGE_URI}}', '!IMAGE_URI!' ^
   -replace '{{REDIS_HOST}}', '!REDIS_HOST_VAL!' ^
   -replace '{{REDIS_PORT}}', '!REDIS_PORT_VAL!' ^
   -replace '{{REDIS_PASSWORD}}', '!REDIS_PASSWORD_VAL!' ^
   -replace '{{REDIS_SSL}}', '!REDIS_SSL_VAL!' ^
   -replace '{{PAYMENT_API_URL}}', '!PAYMENT_API_URL_VAL!' ^
   | Set-Content 'kubernetes_deploy_tmp\deployment.yaml'"
if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to patch deployment.yaml.
    exit /b 1
)

:: ── Apply manifests ───────────────────────────────────────────────────────────
echo.
echo Applying Kubernetes manifests ...

echo   [1/4] Applying namespace ...
kubectl apply -f kubernetes_deploy_tmp\namespace.yaml
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply namespace. & exit /b 1 )

echo   [2/4] Applying deployment ...
kubectl apply -f kubernetes_deploy_tmp\deployment.yaml
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply deployment. & exit /b 1 )

echo   [3/4] Applying service ...
kubectl apply -f kubernetes_deploy_tmp\service.yaml
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply service. & exit /b 1 )

echo   [4/4] Applying ingress ...
kubectl apply -f kubernetes_deploy_tmp\ingress.yaml
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply ingress. & exit /b 1 )

:: ── Wait for rollout ──────────────────────────────────────────────────────────
echo.
echo Waiting for deployment rollout ...
kubectl rollout status deployment/!APP_NAME! -n !NAMESPACE! --timeout=300s
if !ERRORLEVEL! neq 0 (
    echo ERROR: Deployment rollout failed.
    echo Rollback command: kubectl rollout undo deployment/!APP_NAME! -n !NAMESPACE!
    exit /b 1
)

:: ── Verify resources ──────────────────────────────────────────────────────────
echo.
echo Verifying deployed resources ...
kubectl get pods,svc,ingress -n !NAMESPACE!

echo.
echo ============================================
echo   Deployment Complete!
echo   Namespace : !NAMESPACE!
echo   Image     : !IMAGE_URI!
echo   App URL   : http://resortslite.example.com
echo ============================================
echo.
echo Rollback command (if needed):
echo   kubectl rollout undo deployment/!APP_NAME! -n !NAMESPACE!

:: ── Cleanup temp manifests ────────────────────────────────────────────────────
if exist kubernetes_deploy_tmp rmdir /s /q kubernetes_deploy_tmp

endlocal
