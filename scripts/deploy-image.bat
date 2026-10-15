@echo off
setlocal enabledelayedexpansion

echo ============================================
echo   ResortsLite - Deploy to Azure AKS
echo ============================================
echo.

rem ---- Collect Azure / AKS details ----
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

set /p IMAGE_URI="Enter full Docker image URI (e.g. myregistry.azurecr.io/resortslite:latest): "
if "!IMAGE_URI!"=="" (
    echo ERROR: Image URI cannot be empty.
    exit /b 1
)

echo.
echo ---- Application Environment Variables ----
echo Press Enter to skip any variable.
echo.

set /p SPRING_DATASOURCE_URL="Enter SPRING_DATASOURCE_URL (Oracle JDBC URL): "
set /p SPRING_DATASOURCE_USERNAME="Enter SPRING_DATASOURCE_USERNAME: "
set /p SPRING_DATASOURCE_PASSWORD="Enter SPRING_DATASOURCE_PASSWORD: "
set /p SPRING_REDIS_HOST="Enter SPRING_REDIS_HOST (Azure Cache for Redis hostname): "
set /p SPRING_REDIS_PORT="Enter SPRING_REDIS_PORT [default: 6380]: "
if "!SPRING_REDIS_PORT!"=="" set SPRING_REDIS_PORT=6380
set /p SPRING_REDIS_PASSWORD="Enter SPRING_REDIS_PASSWORD (Redis access key): "
set /p BOOKING_CACHE_TTL_SECONDS="Enter BOOKING_CACHE_TTL_SECONDS [default: 3600]: "
if "!BOOKING_CACHE_TTL_SECONDS!"=="" set BOOKING_CACHE_TTL_SECONDS=3600
set /p PAYMENT_API_URL="Enter PAYMENT_API_URL [default: http://payment-service:9090/payments/charge]: "
if "!PAYMENT_API_URL!"=="" set PAYMENT_API_URL=http://payment-service:9090/payments/charge

echo.
echo ---- Configuring kubectl for AKS ----
az aks get-credentials --resource-group !RESOURCE_GROUP! --name !CLUSTER_NAME! --overwrite-existing
if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to get AKS credentials.
    exit /b 1
)

echo Verifying cluster connectivity...
kubectl cluster-info
if !ERRORLEVEL! neq 0 (
    echo ERROR: Cannot connect to AKS cluster.
    exit /b 1
)

echo.
echo ---- Updating Kubernetes manifests ----

rem Use PowerShell to replace placeholders in deployment.yaml
powershell -Command "(Get-Content kubernetes\deployment.yaml) -replace '{{IMAGE_URI}}', '!IMAGE_URI!' | Set-Content kubernetes\deployment.yaml"
powershell -Command "(Get-Content kubernetes\deployment.yaml) -replace '{{SPRING_DATASOURCE_URL}}', '!SPRING_DATASOURCE_URL!' | Set-Content kubernetes\deployment.yaml"
powershell -Command "(Get-Content kubernetes\deployment.yaml) -replace '{{SPRING_DATASOURCE_USERNAME}}', '!SPRING_DATASOURCE_USERNAME!' | Set-Content kubernetes\deployment.yaml"
powershell -Command "(Get-Content kubernetes\deployment.yaml) -replace '{{SPRING_DATASOURCE_PASSWORD}}', '!SPRING_DATASOURCE_PASSWORD!' | Set-Content kubernetes\deployment.yaml"
powershell -Command "(Get-Content kubernetes\deployment.yaml) -replace '{{SPRING_REDIS_HOST}}', '!SPRING_REDIS_HOST!' | Set-Content kubernetes\deployment.yaml"
powershell -Command "(Get-Content kubernetes\deployment.yaml) -replace '{{SPRING_REDIS_PORT}}', '!SPRING_REDIS_PORT!' | Set-Content kubernetes\deployment.yaml"
powershell -Command "(Get-Content kubernetes\deployment.yaml) -replace '{{SPRING_REDIS_PASSWORD}}', '!SPRING_REDIS_PASSWORD!' | Set-Content kubernetes\deployment.yaml"
powershell -Command "(Get-Content kubernetes\deployment.yaml) -replace '{{BOOKING_CACHE_TTL_SECONDS}}', '!BOOKING_CACHE_TTL_SECONDS!' | Set-Content kubernetes\deployment.yaml"
powershell -Command "(Get-Content kubernetes\deployment.yaml) -replace '{{PAYMENT_API_URL}}', '!PAYMENT_API_URL!' | Set-Content kubernetes\deployment.yaml"

echo.
echo ---- Applying Kubernetes manifests ----
kubectl apply -f kubernetes\namespace.yaml
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply namespace. & exit /b 1 )
echo   [OK] Namespace applied

kubectl apply -f kubernetes\deployment.yaml
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply deployment. & exit /b 1 )
echo   [OK] Deployment applied

kubectl apply -f kubernetes\service.yaml
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply service. & exit /b 1 )
echo   [OK] Service applied

kubectl apply -f kubernetes\ingress.yaml
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply ingress. & exit /b 1 )
echo   [OK] Ingress applied

echo.
echo ---- Waiting for deployment rollout ----
kubectl rollout status deployment/resortslite -n resortslite --timeout=300s
if !ERRORLEVEL! neq 0 (
    echo ERROR: Deployment rollout failed.
    echo To rollback: kubectl rollout undo deployment/resortslite -n resortslite
    exit /b 1
)

echo.
echo ---- Verifying deployed resources ----
kubectl get pods,svc,ingress -n resortslite

echo.
echo ---- Application Access ----
echo   Application : http://resortslite.example.com
echo   Health Check: http://resortslite.example.com/actuator/health
echo.
echo ============================================
echo   SUCCESS: ResortsLite deployed to AKS!
echo ============================================
echo.
echo Rollback command (if needed):
echo   kubectl rollout undo deployment/resortslite -n resortslite

endlocal
