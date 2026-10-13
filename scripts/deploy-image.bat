@echo off
setlocal enabledelayedexpansion

:: =============================================================
:: deploy-image.bat — Deploy ResortsLite to AWS EKS (Windows)
:: =============================================================

set "APP_NAME=resortslite"
set "NAMESPACE=resortslite"

echo ==============================================
echo   ResortsLite - Deploy to AWS EKS
echo ==============================================
echo.

:: Prompt for AWS configuration
set /p AWS_REGION="Enter AWS Region (e.g. us-east-1): "
if "!AWS_REGION!"=="" (
    echo ERROR: AWS Region is required.
    exit /b 1
)

set /p CLUSTER_NAME="Enter EKS Cluster Name: "
if "!CLUSTER_NAME!"=="" (
    echo ERROR: EKS Cluster Name is required.
    exit /b 1
)

set /p IMAGE_URI="Enter full Docker image URI: "
if "!IMAGE_URI!"=="" (
    echo ERROR: Docker image URI is required.
    exit /b 1
)

echo.
echo --- Application Environment Variables ---
echo Press Enter to skip any variable.
echo.

set /p SPRING_DATASOURCE_URL="Enter SPRING_DATASOURCE_URL (Oracle JDBC URL): "
set /p SPRING_DATASOURCE_USERNAME="Enter SPRING_DATASOURCE_USERNAME: "
set /p SPRING_DATASOURCE_PASSWORD="Enter SPRING_DATASOURCE_PASSWORD: "
set /p REDIS_HOST="Enter REDIS_HOST (ElastiCache endpoint): "
set /p REDIS_PORT="Enter REDIS_PORT [6379]: "
if "!REDIS_PORT!"=="" set "REDIS_PORT=6379"
set /p REDIS_PASSWORD="Enter REDIS_PASSWORD (leave blank if none): "
set /p REDIS_SSL="Enter REDIS_SSL [false]: "
if "!REDIS_SSL!"=="" set "REDIS_SSL=false"
set /p PAYMENT_API_URL="Enter PAYMENT_API_URL: "
set /p REPORT_BASE_PATH="Enter REPORT_BASE_PATH [/var/reports]: "
if "!REPORT_BASE_PATH!"=="" set "REPORT_BASE_PATH=/var/reports"

echo.
echo Configuring kubectl for EKS cluster: !CLUSTER_NAME! in !AWS_REGION! ...
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

echo.
echo Copying manifests to temporary directory...
if exist kubernetes_deploy_tmp rmdir /s /q kubernetes_deploy_tmp
xcopy /s /e /i /q kubernetes kubernetes_deploy_tmp >nul

echo Updating Kubernetes manifests with provided values...

:: Replace IMAGE_URI
powershell -Command "(Get-Content kubernetes_deploy_tmp\deployment.yaml) -replace '{{IMAGE_URI}}', '!IMAGE_URI!' | Set-Content kubernetes_deploy_tmp\deployment.yaml"

:: Replace env var placeholders
if not "!SPRING_DATASOURCE_URL!"=="" (
    powershell -Command "(Get-Content kubernetes_deploy_tmp\deployment.yaml) -replace '{{SPRING_DATASOURCE_URL}}', '!SPRING_DATASOURCE_URL!' | Set-Content kubernetes_deploy_tmp\deployment.yaml"
)
if not "!SPRING_DATASOURCE_USERNAME!"=="" (
    powershell -Command "(Get-Content kubernetes_deploy_tmp\deployment.yaml) -replace '{{SPRING_DATASOURCE_USERNAME}}', '!SPRING_DATASOURCE_USERNAME!' | Set-Content kubernetes_deploy_tmp\deployment.yaml"
)
if not "!SPRING_DATASOURCE_PASSWORD!"=="" (
    powershell -Command "(Get-Content kubernetes_deploy_tmp\deployment.yaml) -replace '{{SPRING_DATASOURCE_PASSWORD}}', '!SPRING_DATASOURCE_PASSWORD!' | Set-Content kubernetes_deploy_tmp\deployment.yaml"
)
if not "!REDIS_HOST!"=="" (
    powershell -Command "(Get-Content kubernetes_deploy_tmp\deployment.yaml) -replace '{{REDIS_HOST}}', '!REDIS_HOST!' | Set-Content kubernetes_deploy_tmp\deployment.yaml"
)
powershell -Command "(Get-Content kubernetes_deploy_tmp\deployment.yaml) -replace '{{REDIS_PORT}}', '!REDIS_PORT!' | Set-Content kubernetes_deploy_tmp\deployment.yaml"
powershell -Command "(Get-Content kubernetes_deploy_tmp\deployment.yaml) -replace '{{REDIS_PASSWORD}}', '!REDIS_PASSWORD!' | Set-Content kubernetes_deploy_tmp\deployment.yaml"
powershell -Command "(Get-Content kubernetes_deploy_tmp\deployment.yaml) -replace '{{REDIS_SSL}}', '!REDIS_SSL!' | Set-Content kubernetes_deploy_tmp\deployment.yaml"
if not "!PAYMENT_API_URL!"=="" (
    powershell -Command "(Get-Content kubernetes_deploy_tmp\deployment.yaml) -replace '{{PAYMENT_API_URL}}', '!PAYMENT_API_URL!' | Set-Content kubernetes_deploy_tmp\deployment.yaml"
)
powershell -Command "(Get-Content kubernetes_deploy_tmp\deployment.yaml) -replace '{{REPORT_BASE_PATH}}', '!REPORT_BASE_PATH!' | Set-Content kubernetes_deploy_tmp\deployment.yaml"

echo.
echo Applying Kubernetes manifests...

echo   [1/4] Applying namespace...
kubectl apply -f kubernetes_deploy_tmp\namespace.yaml
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply namespace. & exit /b 1 )

echo   [2/4] Applying deployment...
kubectl apply -f kubernetes_deploy_tmp\deployment.yaml
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply deployment. & exit /b 1 )

echo   [3/4] Applying service...
kubectl apply -f kubernetes_deploy_tmp\service.yaml
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply service. & exit /b 1 )

echo   [4/4] Applying ingress...
kubectl apply -f kubernetes_deploy_tmp\ingress.yaml
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply ingress. & exit /b 1 )

:: Clean up
rmdir /s /q kubernetes_deploy_tmp

echo.
echo Waiting for deployment rollout...
kubectl rollout status deployment/!APP_NAME! -n !NAMESPACE! --timeout=300s
if !ERRORLEVEL! neq 0 (
    echo WARNING: Rollout did not complete within timeout.
    echo Rollback command: kubectl rollout undo deployment/!APP_NAME! -n !NAMESPACE!
    exit /b 1
)

echo.
echo Verifying deployed resources...
kubectl get pods,svc,ingress -n !NAMESPACE!

echo.
echo ==============================================
echo   Deployment Complete!
echo   Namespace : !NAMESPACE!
echo   App       : !APP_NAME!
echo   Image     : !IMAGE_URI!
echo ==============================================
echo.
echo Rollback command (if needed):
echo   kubectl rollout undo deployment/!APP_NAME! -n !NAMESPACE!

endlocal
