@echo off
setlocal enabledelayedexpansion

:: ============================================================
:: deploy-image.bat — Deploy ResortsLite to AWS EKS (Windows)
:: ============================================================

set APP_NAME=resortslite
set NAMESPACE=resortslite
set K8S_DIR=kubernetes
set TEMP_DIR=%TEMP%\resortslite-k8s-deploy

echo ==============================================
echo   ResortsLite - Deploy to AWS EKS
echo ==============================================

:: ── AWS / EKS configuration ────────────────────────────────
set /p AWS_REGION="Enter AWS region [us-east-1]: "
if "!AWS_REGION!"=="" set AWS_REGION=us-east-1

set /p CLUSTER_NAME="Enter EKS cluster name: "
if "!CLUSTER_NAME!"=="" (
    echo ERROR: EKS cluster name is required.
    exit /b 1
)

:: ── Docker image URI ───────────────────────────────────────
set /p IMAGE_URI="Enter full Docker image URI (e.g. 123456789.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest): "
if "!IMAGE_URI!"=="" (
    echo ERROR: Docker image URI is required.
    exit /b 1
)

:: ── Application-specific environment variables ─────────────
echo.
echo --- Application Configuration (press Enter to skip optional values) ---

set /p SPRING_DATASOURCE_URL="Enter Oracle datasource URL [jdbc:oracle:thin:@localhost:1521:ORCL]: "
if "!SPRING_DATASOURCE_URL!"=="" set SPRING_DATASOURCE_URL=jdbc:oracle:thin:@localhost:1521:ORCL

set /p REDIS_HOST="Enter Redis/ElastiCache host [localhost]: "
if "!REDIS_HOST!"=="" set REDIS_HOST=localhost

set /p PAYMENT_API_URL="Enter Payment API URL [http://payment-service:9090/payments/charge]: "
if "!PAYMENT_API_URL!"=="" set PAYMENT_API_URL=http://payment-service:9090/payments/charge

:: ── Configure kubectl ──────────────────────────────────────
echo.
echo Configuring kubectl for EKS cluster '!CLUSTER_NAME!' in region '!AWS_REGION!'...
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

:: ── Copy manifests to temp directory ──────────────────────
echo.
echo Preparing Kubernetes manifests...
if exist "!TEMP_DIR!" rmdir /s /q "!TEMP_DIR!"
xcopy /e /i /q "!K8S_DIR!" "!TEMP_DIR!" >nul
if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to copy Kubernetes manifests.
    exit /b 1
)

:: ── Update manifests using PowerShell sed-equivalent ──────
echo Updating manifests with deployment values...

powershell -Command "(Get-Content '!TEMP_DIR!\deployment.yaml') -replace '{{IMAGE_URI}}', '!IMAGE_URI!' | Set-Content '!TEMP_DIR!\deployment.yaml'"
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to update IMAGE_URI. & exit /b 1 )

powershell -Command "(Get-Content '!TEMP_DIR!\deployment.yaml') -replace '{{SPRING_DATASOURCE_URL}}', '!SPRING_DATASOURCE_URL!' | Set-Content '!TEMP_DIR!\deployment.yaml'"
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to update SPRING_DATASOURCE_URL. & exit /b 1 )

powershell -Command "(Get-Content '!TEMP_DIR!\deployment.yaml') -replace '{{REDIS_HOST}}', '!REDIS_HOST!' | Set-Content '!TEMP_DIR!\deployment.yaml'"
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to update REDIS_HOST. & exit /b 1 )

powershell -Command "(Get-Content '!TEMP_DIR!\deployment.yaml') -replace '{{PAYMENT_API_URL}}', '!PAYMENT_API_URL!' | Set-Content '!TEMP_DIR!\deployment.yaml'"
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to update PAYMENT_API_URL. & exit /b 1 )

:: ── Apply manifests ────────────────────────────────────────
echo.
echo Applying Kubernetes manifests...

echo   [1/4] Applying namespace...
kubectl apply -f "!TEMP_DIR!\namespace.yaml"
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply namespace. & exit /b 1 )

echo   [2/4] Applying deployment...
kubectl apply -f "!TEMP_DIR!\deployment.yaml"
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply deployment. & exit /b 1 )

echo   [3/4] Applying service...
kubectl apply -f "!TEMP_DIR!\service.yaml"
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply service. & exit /b 1 )

echo   [4/4] Applying ingress...
kubectl apply -f "!TEMP_DIR!\ingress.yaml"
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply ingress. & exit /b 1 )

:: ── Wait for rollout ───────────────────────────────────────
echo.
echo Waiting for deployment rollout...
kubectl rollout status deployment/!APP_NAME! -n !NAMESPACE! --timeout=300s
if !ERRORLEVEL! neq 0 (
    echo ERROR: Deployment rollout failed.
    echo Rollback command: kubectl rollout undo deployment/!APP_NAME! -n !NAMESPACE!
    exit /b 1
)

:: ── Verify resources ───────────────────────────────────────
echo.
echo Verifying deployed resources...
kubectl get pods,svc,ingress -n !NAMESPACE!

echo.
echo ==============================================
echo   Deployment complete!
echo   Health check: kubectl get pods -n !NAMESPACE!
echo.
echo   Rollback command (if needed):
echo   kubectl rollout undo deployment/!APP_NAME! -n !NAMESPACE!
echo ==============================================

:: ── Cleanup ────────────────────────────────────────────────
if exist "!TEMP_DIR!" rmdir /s /q "!TEMP_DIR!"

endlocal
