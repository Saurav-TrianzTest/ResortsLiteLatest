# ResortsLite – Deployment Guide

## Table of Contents
1. [Overview](#overview)
2. [Prerequisites](#prerequisites)
3. [Project Analysis](#project-analysis)
4. [Local Development with Docker Compose](#local-development-with-docker-compose)
5. [Build and Push Docker Image](#build-and-push-docker-image)
6. [Azure AKS Deployment](#azure-aks-deployment)
7. [Kubernetes Manifest Reference](#kubernetes-manifest-reference)
8. [Configuration Management](#configuration-management)
9. [Scaling and Management](#scaling-and-management)
10. [Troubleshooting](#troubleshooting)
11. [Security Considerations](#security-considerations)
12. [Java-Specific Notes](#java-specific-notes)

---

## Overview

**Application**: ResortsLite  
**Framework**: Spring Boot 2.7.x  
**Java Version**: 8  
**Build Tool**: Maven  
**Package Type**: Executable JAR  
**Application Port**: 8080  
**Health Endpoint**: `/actuator/health`  
**Target Platform**: Azure Kubernetes Service (AKS)

ResortsLite is a legacy resort booking application modernised for cloud-native deployment on Azure AKS. It uses:
- **Oracle Database** for persistent booking data
- **Azure Cache for Redis** for distributed session management (Spring Session) and caching
- **Spring Boot Actuator** for health monitoring

---

## Prerequisites

### Local Development
| Tool | Version | Purpose |
|------|---------|---------|
| Docker Desktop | 24.x+ | Container build and local run |
| Docker Compose | 2.x+ | Local multi-container orchestration |
| Java JDK | 8+ | Local build (optional) |
| Maven | 3.8+ | Local build (optional) |

### Azure AKS Deployment
| Tool | Version | Purpose |
|------|---------|---------|
| Azure CLI | 2.50+ | Azure resource management |
| kubectl | 1.27+ | Kubernetes cluster management |
| Docker | 24.x+ | Image build and push |
| Azure Subscription | — | AKS cluster and ACR |

### Install Azure CLI
```bash
# macOS
brew install azure-cli

# Ubuntu/Debian
curl -sL https://aka.ms/InstallAzureCLIDeb | sudo bash

# Windows
winget install Microsoft.AzureCLI
```

### Install kubectl
```bash
# macOS
brew install kubectl

# Ubuntu/Debian
sudo az aks install-cli

# Windows
az aks install-cli
```

---

## Project Analysis

| Property | Value |
|----------|-------|
| Artifact ID | resortsLite |
| Group ID | com.demo |
| Version | 1.0.0 |
| Spring Boot | 2.7.18 |
| Java | 8 |
| Builder Image | maven:3.8.6-eclipse-temurin-8 |
| Runtime Image | eclipse-temurin:8-jre |
| Application Port | 8080 |
| Management Port | 8080 (shared) |
| Health Endpoint | /actuator/health |
| Session Store | Azure Cache for Redis |
| Database | Oracle (ojdbc8) |

---

## Local Development with Docker Compose

### 1. Configure Environment Variables

Create a `.env` file in the project root (never commit this file):

```dotenv
# Oracle Database
SPRING_DATASOURCE_URL=jdbc:oracle:thin:@localhost:1521:ORCL
SPRING_DATASOURCE_USERNAME=admin
SPRING_DATASOURCE_PASSWORD=your_db_password

# Redis
REDIS_HOST=localhost
REDIS_PORT=6380
REDIS_PASSWORD=your_redis_password
REDIS_SSL=true

# Internal service endpoints
PAYMENT_API_URL=http://payment-service:9090/payments/charge
APP_PAYMENT_ENDPOINT=http://payment-svc:9090/charge
APP_INVENTORY_ENDPOINT=http://inventory-svc:8081/rooms
APP_NOTIFICATION_ENDPOINT=http://notify-svc:7070/send

# Report storage
REPORT_BASE_PATH=/app/reports/
```

### 2. Build and Start the Application

```bash
# Build the Docker image
docker compose build

# Start the application
docker compose up -d

# View logs
docker compose logs -f resortslite

# Stop the application
docker compose down
```

### 3. Verify the Application

```bash
# Health check
curl http://localhost:8080/actuator/health

# Test booking endpoint
curl -X POST "http://localhost:8080/api/bookings/create?guestName=John&roomType=STANDARD&checkIn=2024-01-15&checkOut=2024-01-20"

# Check availability
curl "http://localhost:8080/api/bookings/availability?roomType=DELUXE"
```

---

## Build and Push Docker Image

### Linux / macOS

```bash
# Make the script executable
chmod +x scripts/build-push.sh

# Run from repository root
./scripts/build-push.sh
```

The script will prompt you to:
1. Select registry type (Azure ACR or Docker Hub)
2. Enter registry credentials
3. Specify an image tag (defaults to `latest`)

### Windows

```cmd
scripts\build-push.bat
```

### Manual Build (Azure ACR)

```bash
# Login to ACR
az acr login --name <your-acr-name>

# Build and tag
docker build -t <your-acr-name>.azurecr.io/resortslite:1.0.0 .

# Push
docker push <your-acr-name>.azurecr.io/resortslite:1.0.0
```

---

## Azure AKS Deployment

### Step 1: Create Azure Resources

```bash
# Set variables
RESOURCE_GROUP="rg-resortslite-prod"
LOCATION="eastus"
AKS_CLUSTER="aks-resortslite"
ACR_NAME="acrresortslite"

# Create resource group
az group create --name $RESOURCE_GROUP --location $LOCATION

# Create Azure Container Registry
az acr create --resource-group $RESOURCE_GROUP \
              --name $ACR_NAME \
              --sku Basic

# Create AKS cluster with ACR integration
az aks create \
  --resource-group $RESOURCE_GROUP \
  --name $AKS_CLUSTER \
  --node-count 2 \
  --node-vm-size Standard_DS2_v2 \
  --attach-acr $ACR_NAME \
  --enable-managed-identity \
  --generate-ssh-keys

# Create Azure Cache for Redis
az redis create \
  --resource-group $RESOURCE_GROUP \
  --name redis-resortslite \
  --location $LOCATION \
  --sku Basic \
  --vm-size c0
```

### Step 2: Configure kubectl

```bash
az aks get-credentials \
  --resource-group $RESOURCE_GROUP \
  --name $AKS_CLUSTER

# Verify connection
kubectl cluster-info
kubectl get nodes
```

### Step 3: Build and Push Image

```bash
# Login to ACR
az acr login --name $ACR_NAME

# Build and push
docker build -t ${ACR_NAME}.azurecr.io/resortslite:1.0.0 .
docker push ${ACR_NAME}.azurecr.io/resortslite:1.0.0
```

### Step 4: Deploy to AKS

#### Option A – Automated Script (Recommended)

```bash
chmod +x scripts/deploy-image.sh
./scripts/deploy-image.sh
```

The script will prompt for all required values and substitute them into the Kubernetes manifests before applying.

#### Option B – Manual Deployment

```bash
# 1. Update deployment.yaml with your image URI
sed -i 's|{{IMAGE_URI}}|'${ACR_NAME}'.azurecr.io/resortslite:1.0.0|g' kubernetes/deployment.yaml

# 2. Update all environment variable placeholders
sed -i 's|{{SPRING_DATASOURCE_URL}}|jdbc:oracle:thin:@your-db:1521:ORCL|g' kubernetes/deployment.yaml
sed -i 's|{{SPRING_DATASOURCE_USERNAME}}|admin|g' kubernetes/deployment.yaml
sed -i 's|{{SPRING_DATASOURCE_PASSWORD}}|your_password|g' kubernetes/deployment.yaml
sed -i 's|{{REDIS_HOST}}|your-redis.redis.cache.windows.net|g' kubernetes/deployment.yaml
sed -i 's|{{REDIS_PORT}}|6380|g' kubernetes/deployment.yaml
sed -i 's|{{REDIS_PASSWORD}}|your_redis_key|g' kubernetes/deployment.yaml
sed -i 's|{{REDIS_SSL}}|true|g' kubernetes/deployment.yaml
sed -i 's|{{PAYMENT_API_URL}}|http://payment-service:9090/payments/charge|g' kubernetes/deployment.yaml
sed -i 's|{{APP_PAYMENT_ENDPOINT}}|http://payment-svc:9090/charge|g' kubernetes/deployment.yaml
sed -i 's|{{APP_INVENTORY_ENDPOINT}}|http://inventory-svc:8081/rooms|g' kubernetes/deployment.yaml
sed -i 's|{{APP_NOTIFICATION_ENDPOINT}}|http://notify-svc:7070/send|g' kubernetes/deployment.yaml
sed -i 's|{{REPORT_BASE_PATH}}|/app/reports/|g' kubernetes/deployment.yaml

# 3. Apply manifests in order
kubectl apply -f kubernetes/namespace.yaml
kubectl apply -f kubernetes/deployment.yaml
kubectl apply -f kubernetes/service.yaml
kubectl apply -f kubernetes/ingress.yaml

# 4. Wait for rollout
kubectl rollout status deployment/resortslite -n resortslite

# 5. Verify
kubectl get pods,svc,ingress -n resortslite
```

### Step 5: Configure Ingress (Application Gateway)

```bash
# Enable Application Gateway Ingress Controller (AGIC)
az aks enable-addons \
  --resource-group $RESOURCE_GROUP \
  --name $AKS_CLUSTER \
  --addons ingress-appgw \
  --appgw-name agw-resortslite \
  --appgw-subnet-cidr "10.225.0.0/16"

# Update ingress host in ingress.yaml to your actual domain
# Then re-apply:
kubectl apply -f kubernetes/ingress.yaml
```

---

## Kubernetes Manifest Reference

### namespace.yaml
Creates the `resortslite` namespace to isolate all application resources.

### deployment.yaml
- **Replicas**: 2 (high availability)
- **Image**: Pulled from ACR using `{{IMAGE_URI}}` placeholder
- **Resources**: 250m CPU / 512Mi memory (requests); 500m CPU / 1Gi memory (limits)
- **Liveness Probe**: `GET /actuator/health` – restarts pod if unhealthy (initial delay: 90s)
- **Readiness Probe**: `GET /actuator/health` – removes pod from load balancer if not ready (initial delay: 60s)
- **Security Context**: Runs as non-root user (UID 1000)
- **Graceful Shutdown**: `terminationGracePeriodSeconds: 60`

### service.yaml
- **Type**: ClusterIP (internal only)
- **Port**: 80 → 8080 (container port)
- **Selector**: `app: resortslite`

### ingress.yaml
- **Class**: `azure/application-gateway`
- **SSL Redirect**: Enabled
- **Host**: `resortslite.example.com` (update to your domain)
- **Path**: `/` → `resortslite-service:80`

---

## Configuration Management

### Environment Variables Reference

| Variable | Description | Example |
|----------|-------------|---------|
| `SPRING_PROFILES_ACTIVE` | Spring profile | `docker` |
| `SPRING_DATASOURCE_URL` | Oracle JDBC URL | `jdbc:oracle:thin:@host:1521:SID` |
| `SPRING_DATASOURCE_USERNAME` | DB username | `admin` |
| `SPRING_DATASOURCE_PASSWORD` | DB password | (from Key Vault) |
| `REDIS_HOST` | Redis hostname | `myredis.redis.cache.windows.net` |
| `REDIS_PORT` | Redis port | `6380` |
| `REDIS_PASSWORD` | Redis access key | (from Key Vault) |
| `REDIS_SSL` | Enable Redis SSL | `true` |
| `PAYMENT_API_URL` | Payment service URL | `http://payment-service:9090/payments/charge` |
| `APP_PAYMENT_ENDPOINT` | Payment endpoint | `http://payment-svc:9090/charge` |
| `APP_INVENTORY_ENDPOINT` | Inventory endpoint | `http://inventory-svc:8081/rooms` |
| `APP_NOTIFICATION_ENDPOINT` | Notification endpoint | `http://notify-svc:7070/send` |
| `REPORT_BASE_PATH` | Report file path | `/app/reports/` |
| `JAVA_OPTS` | JVM options | `-Xms256m -Xmx512m` |

### Using Azure Key Vault with AKS

```bash
# Create Key Vault
az keyvault create \
  --resource-group $RESOURCE_GROUP \
  --name kv-resortslite \
  --location $LOCATION

# Store secrets
az keyvault secret set --vault-name kv-resortslite --name "db-password" --value "your_password"
az keyvault secret set --vault-name kv-resortslite --name "redis-password" --value "your_redis_key"

# Enable Key Vault CSI Driver on AKS
az aks enable-addons \
  --addons azure-keyvault-secrets-provider \
  --name $AKS_CLUSTER \
  --resource-group $RESOURCE_GROUP
```

### Using Kubernetes Secrets (Alternative)

```bash
kubectl create secret generic resortslite-secrets \
  --namespace resortslite \
  --from-literal=db-password='your_db_password' \
  --from-literal=redis-password='your_redis_key'
```

---

## Scaling and Management

### Manual Scaling

```bash
# Scale to 3 replicas
kubectl scale deployment resortslite --replicas=3 -n resortslite

# Check scaling status
kubectl get pods -n resortslite -w
```

### Horizontal Pod Autoscaler (HPA)

```bash
kubectl autoscale deployment resortslite \
  --namespace resortslite \
  --cpu-percent=70 \
  --min=2 \
  --max=10
```

### Rolling Updates

```bash
# Update image
kubectl set image deployment/resortslite \
  resortslite=<acr-name>.azurecr.io/resortslite:2.0.0 \
  -n resortslite

# Monitor rollout
kubectl rollout status deployment/resortslite -n resortslite

# View rollout history
kubectl rollout history deployment/resortslite -n resortslite
```

### Rollback

```bash
# Rollback to previous version
kubectl rollout undo deployment/resortslite -n resortslite

# Rollback to specific revision
kubectl rollout undo deployment/resortslite --to-revision=2 -n resortslite
```

---

## Troubleshooting

### Pod Not Starting

```bash
# Check pod status
kubectl get pods -n resortslite

# Describe pod for events
kubectl describe pod <pod-name> -n resortslite

# View pod logs
kubectl logs <pod-name> -n resortslite

# View previous pod logs (if crashed)
kubectl logs <pod-name> -n resortslite --previous
```

### Common Issues

#### 1. OOMKilled (Out of Memory)
```bash
# Check resource usage
kubectl top pods -n resortslite

# Increase memory limits in deployment.yaml
# limits.memory: "2Gi"
```

#### 2. CrashLoopBackOff
```bash
# Check logs for startup errors
kubectl logs <pod-name> -n resortslite --previous

# Common causes:
# - Oracle DB connection failure (check SPRING_DATASOURCE_URL)
# - Redis connection failure (check REDIS_HOST, REDIS_PASSWORD)
# - Missing environment variables
```

#### 3. Readiness Probe Failing
```bash
# Check if actuator is accessible
kubectl exec -it <pod-name> -n resortslite -- \
  wget -qO- http://localhost:8080/actuator/health

# JVM startup can be slow – increase initialDelaySeconds in deployment.yaml
```

#### 4. Ingress Not Routing Traffic
```bash
# Check ingress status
kubectl describe ingress resortslite-ingress -n resortslite

# Check Application Gateway health
az network application-gateway show-backend-health \
  --resource-group $RESOURCE_GROUP \
  --name agw-resortslite
```

#### 5. Redis Connection Issues
```bash
# Verify Redis connectivity from pod
kubectl exec -it <pod-name> -n resortslite -- \
  sh -c "nc -zv $REDIS_HOST $REDIS_PORT"

# Check Redis SSL setting (Azure Cache for Redis uses port 6380 with SSL)
```

#### 6. Oracle DB Connection Issues
```bash
# Check datasource URL format
# Correct: jdbc:oracle:thin:@hostname:1521:SID
# Or:      jdbc:oracle:thin:@//hostname:1521/SERVICE_NAME

# Verify network connectivity from AKS to Oracle DB
kubectl exec -it <pod-name> -n resortslite -- \
  sh -c "nc -zv <db-host> 1521"
```

### Useful Diagnostic Commands

```bash
# Get all resources in namespace
kubectl get all -n resortslite

# Check events
kubectl get events -n resortslite --sort-by='.lastTimestamp'

# Check resource consumption
kubectl top pods -n resortslite
kubectl top nodes

# Port-forward for local debugging
kubectl port-forward deployment/resortslite 8080:8080 -n resortslite
curl http://localhost:8080/actuator/health
```

---

## Security Considerations

1. **Never commit secrets** to source control – use Azure Key Vault or Kubernetes Secrets
2. **Non-root container**: The application runs as UID 1000 (appuser)
3. **Redis SSL**: Always use SSL (port 6380) for Azure Cache for Redis in production
4. **Network policies**: Consider adding Kubernetes NetworkPolicy to restrict pod-to-pod traffic
5. **Image scanning**: Enable ACR vulnerability scanning:
   ```bash
   az acr task create --registry $ACR_NAME --name scan-on-push \
     --image resortslite:{{.Run.ID}} --context /dev/null \
     --file /dev/null --commit-trigger-enabled false
   ```
6. **RBAC**: Use Azure RBAC for AKS and limit service account permissions
7. **Secrets rotation**: Rotate Oracle DB and Redis credentials regularly via Key Vault
8. **SQL Injection**: The existing `BookingService` uses string concatenation for SQL – migrate to parameterised queries before production use

---

## Java-Specific Notes

### JVM Memory Configuration
The container is configured with:
```
-XX:+UseContainerSupport       # Respect container memory limits
-XX:MaxRAMPercentage=75.0      # Use 75% of container memory for heap
-Xms256m                       # Initial heap size
-Xmx512m                       # Maximum heap size
-Dfile.encoding=UTF-8          # Character encoding
-Duser.timezone=UTC            # Timezone
```

With a 1Gi memory limit, the JVM will use up to 768Mi for heap.

### Spring Boot Actuator
The `/actuator/health` endpoint is exposed and used for both liveness and readiness probes. The endpoint shows detailed health information including:
- Database connectivity
- Redis connectivity
- Disk space

### Spring Session with Redis
The application uses Spring Session backed by Azure Cache for Redis. This ensures:
- Session data is shared across all pod replicas
- Sessions survive pod restarts
- Horizontal scaling works correctly

### Oracle JDBC
The application uses `ojdbc8` for Java 8 compatibility. Ensure:
- Oracle DB is accessible from the AKS cluster (VNet peering or private endpoint)
- The JDBC URL uses the correct format for your Oracle version
- Connection pool settings are appropriate for the number of replicas

### Startup Time
Spring Boot applications with Oracle and Redis connections may take 60-90 seconds to start. The Kubernetes probes are configured with appropriate initial delays:
- Liveness probe: 90s initial delay
- Readiness probe: 60s initial delay

Adjust these values if your environment requires longer startup times.
