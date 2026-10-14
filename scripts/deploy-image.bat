@echo off
setlocal enabledelayedexpansion

:: =============================================================================
:: deploy-image.bat – Deploy ResortsLite to Azure AKS (Windows)
:: Usage: scripts\deploy-image.bat
:: Run from the repository root directory.
:: =============================================================================

set "APP_NAME=resortslite"
set "NAMESPACE=resortslite"

echo ==============================================
echo   ResortsLite - Deploy to Azure AKS
echo ==============================================
echo.

:: ---------------------------------------------------------------------------
:: Collect Azure / AKS details
:: ---------------------------------------------------------------------------
set /p "RESOURCE_GROUP=Enter Azure Resource Group name: "
if "!RESOURCE_GROUP!"=="" (
    echo ERROR: Resource group cannot be empty.
    exit /b 1
)

set /p "CLUSTER_NAME=Enter AKS Cluster name: "
if "!CLUSTER_NAME!"=="" (
    echo ERROR: AKS cluster name cannot be empty.
    exit /b 1
)

set /p "IMAGE_URI=Enter full Docker image URI (e.g. myregistry.azurecr.io/resortslite:latest): "
if "!IMAGE_URI!"=="" (
    echo ERROR: Image URI cannot be empty.
    exit /b 1
)

echo.
echo Resource Group : !RESOURCE_GROUP!
echo AKS Cluster    : !CLUSTER_NAME!
echo Image URI      : !IMAGE_URI!
echo.

:: ---------------------------------------------------------------------------
:: Prompt for application-specific environment variables
:: ---------------------------------------------------------------------------
echo --- Application Environment Variables ---
echo (Press Enter to keep default placeholder value)
echo.

set /p "SPRING_DATASOURCE_URL=Enter SPRING_DATASOURCE_URL [jdbc:oracle:thin:@db-host:1521:ORCL]: "
if "!SPRING_DATASOURCE_URL!"=="" set "SPRING_DATASOURCE_URL=jdbc:oracle:thin:@db-host:1521:ORCL"

set /p "SPRING_DATASOURCE_USERNAME=Enter SPRING_DATASOURCE_USERNAME [admin]: "
if "!SPRING_DATASOURCE_USERNAME!"=="" set "SPRING_DATASOURCE_USERNAME=admin"

set /p "SPRING_DATASOURCE_PASSWORD=Enter SPRING_DATASOURCE_PASSWORD: "
if "!SPRING_DATASOURCE_PASSWORD!"=="" set "SPRING_DATASOURCE_PASSWORD=changeme"

set /p "REDIS_HOST=Enter REDIS_HOST: "
if "!REDIS_HOST!"=="" set "REDIS_HOST=redis-host"

set /p "REDIS_PORT=Enter REDIS_PORT [6380]: "
if "!REDIS_PORT!"=="" set "REDIS_PORT=6380"

set /p "REDIS_PASSWORD=Enter REDIS_PASSWORD: "

set /p "REDIS_SSL=Enter REDIS_SSL [true]: "
if "!REDIS_SSL!"=="" set "REDIS_SSL=true"

set /p "PAYMENT_API_URL=Enter PAYMENT_API_URL [http://payment-service:9090/payments/charge]: "
if "!PAYMENT_API_URL!"=="" set "PAYMENT_API_URL=http://payment-service:9090/payments/charge"

set /p "APP_PAYMENT_ENDPOINT=Enter APP_PAYMENT_ENDPOINT [http://payment-svc:9090/charge]: "
if "!APP_PAYMENT_ENDPOINT!"=="" set "APP_PAYMENT_ENDPOINT=http://payment-svc:9090/charge"

set /p "APP_INVENTORY_ENDPOINT=Enter APP_INVENTORY_ENDPOINT [http://inventory-svc:8081/rooms]: "
if "!APP_INVENTORY_ENDPOINT!"=="" set "APP_INVENTORY_ENDPOINT=http://inventory-svc:8081/rooms"

set /p "APP_NOTIFICATION_ENDPOINT=Enter APP_NOTIFICATION_ENDPOINT [http://notify-svc:7070/send]: "
if "!APP_NOTIFICATION_ENDPOINT!"=="" set "APP_NOTIFICATION_ENDPOINT=http://notify-svc:7070/send"

set /p "REPORT_BASE_PATH=Enter REPORT_BASE_PATH [/app/reports/]: "
if "!REPORT_BASE_PATH!"=="" set "REPORT_BASE_PATH=/app/reports/"

echo.

:: ---------------------------------------------------------------------------
:: Copy manifests to temp directory and substitute placeholders via PowerShell
:: ---------------------------------------------------------------------------
echo Updating Kubernetes manifests with provided values ...

if exist kubernetes_deploy_tmp rmdir /s /q kubernetes_deploy_tmp
xcopy /e /i /q kubernetes kubernetes_deploy_tmp >nul

powershell -NoProfile -Command ^
  "$content = Get-Content 'kubernetes_deploy_tmp\deployment.yaml' -Raw; ^
   $content = $content -replace '\{\{IMAGE_URI\}\}', '!IMAGE_URI!'; ^
   $content = $content -replace '\{\{SPRING_DATASOURCE_URL\}\}', '!SPRING_DATASOURCE_URL!'; ^
   $content = $content -replace '\{\{SPRING_DATASOURCE_USERNAME\}\}', '!SPRING_DATASOURCE_USERNAME!'; ^
   $content = $content -replace '\{\{SPRING_DATASOURCE_PASSWORD\}\}', '!SPRING_DATASOURCE_PASSWORD!'; ^
   $content = $content -replace '\{\{REDIS_HOST\}\}', '!REDIS_HOST!'; ^
   $content = $content -replace '\{\{REDIS_PORT\}\}', '!REDIS_PORT!'; ^
   $content = $content -replace '\{\{REDIS_PASSWORD\}\}', '!REDIS_PASSWORD!'; ^
   $content = $content -replace '\{\{REDIS_SSL\}\}', '!REDIS_SSL!'; ^
   $content = $content -replace '\{\{PAYMENT_API_URL\}\}', '!PAYMENT_API_URL!'; ^
   $content = $content -replace '\{\{APP_PAYMENT_ENDPOINT\}\}', '!APP_PAYMENT_ENDPOINT!'; ^
   $content = $content -replace '\{\{APP_INVENTORY_ENDPOINT\}\}', '!APP_INVENTORY_ENDPOINT!'; ^
   $content = $content -replace '\{\{APP_NOTIFICATION_ENDPOINT\}\}', '!APP_NOTIFICATION_ENDPOINT!'; ^
   $content = $content -replace '\{\{REPORT_BASE_PATH\}\}', '!REPORT_BASE_PATH!'; ^
   Set-Content 'kubernetes_deploy_tmp\deployment.yaml' $content"

if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to update deployment manifest.
    rmdir /s /q kubernetes_deploy_tmp
    exit /b 1
)
echo Manifests updated.
echo.

:: ---------------------------------------------------------------------------
:: Configure kubectl for AKS
:: ---------------------------------------------------------------------------
echo Configuring kubectl for AKS cluster: !CLUSTER_NAME! ...
az aks get-credentials --resource-group "!RESOURCE_GROUP!" --name "!CLUSTER_NAME!" --overwrite-existing
if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to get AKS credentials.
    rmdir /s /q kubernetes_deploy_tmp
    exit /b 1
)

echo Verifying cluster connectivity ...
kubectl cluster-info
if !ERRORLEVEL! neq 0 (
    echo ERROR: Cannot connect to AKS cluster.
    rmdir /s /q kubernetes_deploy_tmp
    exit /b 1
)
echo.

:: ---------------------------------------------------------------------------
:: Apply manifests in order
:: ---------------------------------------------------------------------------
echo Applying Kubernetes manifests ...

echo   [1/4] Applying namespace ...
kubectl apply -f kubernetes_deploy_tmp\namespace.yaml
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply namespace. & rmdir /s /q kubernetes_deploy_tmp & exit /b 1 )

echo   [2/4] Applying deployment ...
kubectl apply -f kubernetes_deploy_tmp\deployment.yaml
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply deployment. & rmdir /s /q kubernetes_deploy_tmp & exit /b 1 )

echo   [3/4] Applying service ...
kubectl apply -f kubernetes_deploy_tmp\service.yaml
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply service. & rmdir /s /q kubernetes_deploy_tmp & exit /b 1 )

echo   [4/4] Applying ingress ...
kubectl apply -f kubernetes_deploy_tmp\ingress.yaml
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply ingress. & rmdir /s /q kubernetes_deploy_tmp & exit /b 1 )

echo.

:: ---------------------------------------------------------------------------
:: Wait for rollout
:: ---------------------------------------------------------------------------
echo Waiting for deployment rollout ...
kubectl rollout status deployment/!APP_NAME! -n !NAMESPACE! --timeout=300s
if !ERRORLEVEL! neq 0 (
    echo ERROR: Deployment rollout failed. Rolling back ...
    kubectl rollout undo deployment/!APP_NAME! -n !NAMESPACE!
    rmdir /s /q kubernetes_deploy_tmp
    exit /b 1
)
echo.

:: ---------------------------------------------------------------------------
:: Verify resources
:: ---------------------------------------------------------------------------
echo Verifying deployed resources ...
kubectl get pods,svc,ingress -n !NAMESPACE!
echo.

:: ---------------------------------------------------------------------------
:: Display application URL
:: ---------------------------------------------------------------------------
echo ==============================================
echo   Deployment Successful!
echo ==============================================
echo   Namespace : !NAMESPACE!
echo   Image     : !IMAGE_URI!
echo   Host      : http://resortslite.example.com
echo   Health    : http://resortslite.example.com/actuator/health
echo.
echo   Rollback  : kubectl rollout undo deployment/!APP_NAME! -n !NAMESPACE!
echo ==============================================

rmdir /s /q kubernetes_deploy_tmp
endlocal
