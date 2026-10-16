@echo off
setlocal enabledelayedexpansion

:: ============================================================
:: deploy-image.bat — Deploy ResortsLite to AWS EKS (Windows)
:: ============================================================

set "APP_NAME=resortslite"
set "NAMESPACE=resortslite"
set "K8S_DIR=kubernetes"
set "TMP_DIR=%TEMP%\k8s-deploy-resortslite"

echo ==============================================
echo   ResortsLite - AWS EKS Deployment Script
echo ==============================================
echo.

:: ── AWS / EKS configuration ──────────────────────────────────
set /p "AWS_REGION=Enter AWS Region [us-east-1]: "
if "!AWS_REGION!"=="" set "AWS_REGION=us-east-1"

set /p "CLUSTER_NAME=Enter EKS Cluster Name: "
if "!CLUSTER_NAME!"=="" (
    echo ERROR: EKS Cluster Name is required.
    exit /b 1
)

set /p "IMAGE_URI=Enter full Docker image URI: "
if "!IMAGE_URI!"=="" (
    echo ERROR: Docker image URI is required.
    exit /b 1
)

:: ── Application environment variables ────────────────────────
echo.
echo --- Application Environment Variables ---
echo (Press Enter to use the default value shown)
echo.

set /p "SPRING_DATASOURCE_URL=Enter SPRING_DATASOURCE_URL [jdbc:oracle:thin:@localhost:1521:ORCL]: "
if "!SPRING_DATASOURCE_URL!"=="" set "SPRING_DATASOURCE_URL=jdbc:oracle:thin:@localhost:1521:ORCL"

set /p "SPRING_DATASOURCE_USERNAME=Enter SPRING_DATASOURCE_USERNAME [admin]: "
if "!SPRING_DATASOURCE_USERNAME!"=="" set "SPRING_DATASOURCE_USERNAME=admin"

set /p "SPRING_DATASOURCE_PASSWORD=Enter SPRING_DATASOURCE_PASSWORD [changeme]: "
if "!SPRING_DATASOURCE_PASSWORD!"=="" set "SPRING_DATASOURCE_PASSWORD=changeme"

set /p "REDIS_HOST=Enter REDIS_HOST [localhost]: "
if "!REDIS_HOST!"=="" set "REDIS_HOST=localhost"

set /p "REDIS_PORT=Enter REDIS_PORT [6379]: "
if "!REDIS_PORT!"=="" set "REDIS_PORT=6379"

set /p "REDIS_PASSWORD=Enter REDIS_PASSWORD (leave blank if none): "

set /p "REDIS_SSL=Enter REDIS_SSL [false]: "
if "!REDIS_SSL!"=="" set "REDIS_SSL=false"

set /p "PAYMENT_API_URL=Enter PAYMENT_API_URL [http://payment-service:9090/payments/charge]: "
if "!PAYMENT_API_URL!"=="" set "PAYMENT_API_URL=http://payment-service:9090/payments/charge"

set /p "BOOKING_CACHE_TTL_SECONDS=Enter BOOKING_CACHE_TTL_SECONDS [3600]: "
if "!BOOKING_CACHE_TTL_SECONDS!"=="" set "BOOKING_CACHE_TTL_SECONDS=3600"

set /p "REPORT_BASE_PATH=Enter REPORT_BASE_PATH [/var/reports]: "
if "!REPORT_BASE_PATH!"=="" set "REPORT_BASE_PATH=/var/reports"

:: ── Configure kubectl ─────────────────────────────────────────
echo.
echo Configuring kubectl for EKS cluster '!CLUSTER_NAME!' in '!AWS_REGION!'...
aws eks update-kubeconfig --region !AWS_REGION! --name !CLUSTER_NAME!
if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to configure kubectl.
    exit /b 1
)
echo kubectl configured.

echo.
echo Verifying cluster connectivity...
kubectl cluster-info
if !ERRORLEVEL! neq 0 (
    echo ERROR: Cannot connect to EKS cluster.
    exit /b 1
)

:: ── Copy manifests to temp directory ─────────────────────────
echo.
echo Preparing Kubernetes manifests...
if exist "!TMP_DIR!" rmdir /s /q "!TMP_DIR!"
xcopy /e /i /q "!K8S_DIR!" "!TMP_DIR!" >nul
if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to copy manifests.
    exit /b 1
)

:: ── Substitute placeholders using PowerShell ─────────────────
echo Updating manifests with deployment values...
set "DEPLOY_YAML=!TMP_DIR!\deployment.yaml"

powershell -Command "(Get-Content '!DEPLOY_YAML!') -replace '\{\{IMAGE_URI\}\}', '!IMAGE_URI!' | Set-Content '!DEPLOY_YAML!'"
powershell -Command "(Get-Content '!DEPLOY_YAML!') -replace '\{\{SPRING_DATASOURCE_URL\}\}', '!SPRING_DATASOURCE_URL!' | Set-Content '!DEPLOY_YAML!'"
powershell -Command "(Get-Content '!DEPLOY_YAML!') -replace '\{\{SPRING_DATASOURCE_USERNAME\}\}', '!SPRING_DATASOURCE_USERNAME!' | Set-Content '!DEPLOY_YAML!'"
powershell -Command "(Get-Content '!DEPLOY_YAML!') -replace '\{\{SPRING_DATASOURCE_PASSWORD\}\}', '!SPRING_DATASOURCE_PASSWORD!' | Set-Content '!DEPLOY_YAML!'"
powershell -Command "(Get-Content '!DEPLOY_YAML!') -replace '\{\{REDIS_HOST\}\}', '!REDIS_HOST!' | Set-Content '!DEPLOY_YAML!'"
powershell -Command "(Get-Content '!DEPLOY_YAML!') -replace '\{\{REDIS_PORT\}\}', '!REDIS_PORT!' | Set-Content '!DEPLOY_YAML!'"
powershell -Command "(Get-Content '!DEPLOY_YAML!') -replace '\{\{REDIS_PASSWORD\}\}', '!REDIS_PASSWORD!' | Set-Content '!DEPLOY_YAML!'"
powershell -Command "(Get-Content '!DEPLOY_YAML!') -replace '\{\{REDIS_SSL\}\}', '!REDIS_SSL!' | Set-Content '!DEPLOY_YAML!'"
powershell -Command "(Get-Content '!DEPLOY_YAML!') -replace '\{\{PAYMENT_API_URL\}\}', '!PAYMENT_API_URL!' | Set-Content '!DEPLOY_YAML!'"
powershell -Command "(Get-Content '!DEPLOY_YAML!') -replace '\{\{BOOKING_CACHE_TTL_SECONDS\}\}', '!BOOKING_CACHE_TTL_SECONDS!' | Set-Content '!DEPLOY_YAML!'"
powershell -Command "(Get-Content '!DEPLOY_YAML!') -replace '\{\{REPORT_BASE_PATH\}\}', '!REPORT_BASE_PATH!' | Set-Content '!DEPLOY_YAML!'"
echo Manifests updated.

:: ── Apply manifests ───────────────────────────────────────────
echo.
echo Applying Kubernetes manifests...

echo   [1/4] Applying namespace...
kubectl apply -f "!TMP_DIR!\namespace.yaml"
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply namespace. & exit /b 1 )

echo   [2/4] Applying deployment...
kubectl apply -f "!TMP_DIR!\deployment.yaml"
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply deployment. & exit /b 1 )

echo   [3/4] Applying service...
kubectl apply -f "!TMP_DIR!\service.yaml"
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply service. & exit /b 1 )

echo   [4/4] Applying ingress...
kubectl apply -f "!TMP_DIR!\ingress.yaml"
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply ingress. & exit /b 1 )

:: ── Wait for rollout ──────────────────────────────────────────
echo.
echo Waiting for deployment rollout...
kubectl rollout status deployment/!APP_NAME! -n !NAMESPACE! --timeout=300s
if !ERRORLEVEL! neq 0 (
    echo ERROR: Deployment rollout failed.
    echo Rollback command: kubectl rollout undo deployment/!APP_NAME! -n !NAMESPACE!
    exit /b 1
)

:: ── Verify resources ──────────────────────────────────────────
echo.
echo Verifying deployed resources...
kubectl get pods,svc,ingress -n !NAMESPACE!

echo.
echo ==============================================
echo   Deployment Complete!
echo   Run the following to get the ingress URL:
echo   kubectl get ingress -n !NAMESPACE!
echo   Health Check: /actuator/health
echo ==============================================
echo.
echo Rollback command (if needed):
echo   kubectl rollout undo deployment/!APP_NAME! -n !NAMESPACE!

:: ── Cleanup ───────────────────────────────────────────────────
if exist "!TMP_DIR!" rmdir /s /q "!TMP_DIR!"

endlocal
