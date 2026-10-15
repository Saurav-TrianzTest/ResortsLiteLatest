# ResortsLite — Deployment Guide (Azure AKS)

## Table of Contents
1. [Overview](#overview)
2. [Prerequisites](#prerequisites)
3. [Project Structure](#project-structure)
4. [Local Development with Docker Compose](#local-development-with-docker-compose)
5. [Build and Push Docker Image](#build-and-push-docker-image)
6. [Azure AKS Deployment](#azure-aks-deployment)
7. [Kubernetes Manifest Reference](#kubernetes-manifest-reference)
8. [Configuration Management](#configuration-management)
9. [Scaling and Management](#scaling-and-management)
10. [Troubleshooting](#troubleshooting)
11. [Security Considerations](#security-considerations)

---

## Overview

**Application**: ResortsLite  
**Framework**: Spring Boot 2.7.x  
**Java Version**: 8  
**Build Tool**: Maven  
**Target Platform**: Azure Kubernetes Service (AKS)  
**Package Type**: Executable JAR  

ResortsLite is a legacy resort booking application modernized for cloud-native deployment on Azure AKS. It uses Azure Cache for Redis for distributed session management and booking cache, and Oracle Database for persistent storage.

---

## Prerequisites

### Local Development
- Docker Desktop 24.x or later
- Docker Compose v2.x or later
- Java 8 JDK (for local builds outside Docker)
- Maven 3.8.x (for local builds outside Docker)

### Azure AKS Deployment
- Azure CLI (`az`) 2.50.0 or later — [Install Guide](https://docs.microsoft.com/en-us/cli/azure/install-azure-cli)
- `kubectl` 1.27 or later — [Install Guide](https://kubernetes.io/docs/tasks/tools/)
- Active Azure subscription with:
  - AKS cluster provisioned
  - Azure Container Registry (ACR) or Docker Hub access
  - Azure Cache for Redis instance
  - Oracle Database (or Azure Database compatible)
  - Azure Key Vault (recommended for secrets)

### Verify Tools
```bash
az --version
kubectl version --client
docker --version
```

---

## Project Structure

```
resortslite/
├── Dockerfile                    # Multi-stage Docker build
├── docker-compose.yml            # Local development compose file
├── .dockerignore                 # Docker build exclusions
├── pom.xml                       # Maven build descriptor
├── src/
│   └── main/
│       ├── java/com/demo/resortslite/
│       │   ├── ResortsLiteApplication.java
│       │   ├── BookingController.java
│       │   ├── BookingService.java
│       │   ├── RedisConfig.java
│       │   └── RedisHttpSessionConfig.java
│       └── resources/
│           └── application.properties
├── kubernetes/
│   ├── namespace.yaml            # Kubernetes namespace
│   ├── deployment.yaml           # Application deployment
│   ├── service.yaml              # ClusterIP service
│   └── ingress.yaml              # Azure Application Gateway ingress
├── scripts/
│   ├── build-push.sh             # Linux/macOS build & push script
│   ├── build-push.bat            # Windows build & push script
│   ├── deploy-image.sh           # Linux/macOS AKS deploy script
│   └── deploy-image.bat          # Windows AKS deploy script
└── docs/
    └── DEPLOYMENT.md             # This file
```

---

## Local Development with Docker Compose

### 1. Configure Environment Variables

Create a `.env` file in the project root (never commit this file):

```env
# Oracle Database
SPRING_DATASOURCE_URL=jdbc:oracle:thin:@localhost:1521:ORCL
SPRING_DATASOURCE_USERNAME=admin
SPRING_DATASOURCE_PASSWORD=your_db_password

# Azure Cache for Redis
SPRING_REDIS_HOST=your-redis.redis.cache.windows.net
SPRING_REDIS_PORT=6380
SPRING_REDIS_PASSWORD=your_redis_access_key
SPRING_REDIS_SSL=true

# Application settings
BOOKING_CACHE_TTL_SECONDS=3600
PAYMENT_API_URL=http://payment-service:9090/payments/charge
REPORT_BASE_PATH=/var/reports/
```

### 2. Build and Start

```bash
# Build and start the application container
docker-compose up --build

# Run in background
docker-compose up -d --build

# View logs
docker-compose logs -f resortslite

# Stop
docker-compose down
```

### 3. Verify Application

```bash
# Health check
curl http://localhost:8080/actuator/health

# Test booking endpoint
curl -X POST "http://localhost:8080/api/bookings/create?guestName=John&roomType=STANDARD&checkIn=2024-01-15&checkOut=2024-01-20"
```

---

## Build and Push Docker Image

### Linux / macOS

```bash
# Make script executable
chmod +x scripts/build-push.sh

# Run from project root
./scripts/build-push.sh
```

The script will prompt you to:
1. Select registry type (Azure ACR or Docker Hub)
2. Enter registry credentials
3. Enter image tag (defaults to `latest`)

### Windows

```cmd
scripts\build-push.bat
```

### Manual Build

```bash
# Build image
docker build -t resortslite:latest .

# Tag for ACR
docker tag resortslite:latest myregistry.azurecr.io/resortslite:1.0.0

# Push to ACR
az acr login --name myregistry
docker push myregistry.azurecr.io/resortslite:1.0.0
```

---

## Azure AKS Deployment

### Step 1: Provision AKS Cluster (if not already done)

```bash
# Login to Azure
az login

# Create resource group
az group create --name resortslite-rg --location eastus

# Create AKS cluster
az aks create \
  --resource-group resortslite-rg \
  --name resortslite-aks \
  --node-count 2 \
  --node-vm-size Standard_DS2_v2 \
  --enable-addons monitoring \
  --generate-ssh-keys

# Attach ACR to AKS (if using ACR)
az aks update \
  --resource-group resortslite-rg \
  --name resortslite-aks \
  --attach-acr myregistry
```

### Step 2: Install Application Gateway Ingress Controller (AGIC)

```bash
# Enable AGIC add-on
az aks enable-addons \
  --resource-group resortslite-rg \
  --name resortslite-aks \
  --addons ingress-appgw \
  --appgw-name resortslite-appgw \
  --appgw-subnet-cidr "10.225.0.0/16"
```

### Step 3: Deploy Using Script

#### Linux / macOS
```bash
chmod +x scripts/deploy-image.sh
./scripts/deploy-image.sh
```

#### Windows
```cmd
scripts\deploy-image.bat
```

The script will prompt for:
- Azure Resource Group name
- AKS Cluster name
- Docker image URI (full path with tag)
- All required environment variables (database, Redis, etc.)

### Step 4: Manual Deployment (Alternative)

```bash
# Get AKS credentials
az aks get-credentials --resource-group resortslite-rg --name resortslite-aks

# Update image URI in deployment.yaml
sed -i 's|{{IMAGE_URI}}|myregistry.azurecr.io/resortslite:1.0.0|g' kubernetes/deployment.yaml

# Apply manifests in order
kubectl apply -f kubernetes/namespace.yaml
kubectl apply -f kubernetes/deployment.yaml
kubectl apply -f kubernetes/service.yaml
kubectl apply -f kubernetes/ingress.yaml

# Wait for rollout
kubectl rollout status deployment/resortslite -n resortslite

# Verify
kubectl get pods,svc,ingress -n resortslite
```

---

## Kubernetes Manifest Reference

### namespace.yaml
Creates the `resortslite` namespace to isolate all application resources.

### deployment.yaml
- **Replicas**: 2 (horizontal scaling)
- **Image**: Pulled from registry using `{{IMAGE_URI}}` placeholder
- **Resources**: 250m CPU / 512Mi memory (requests); 500m CPU / 1Gi memory (limits)
- **Liveness Probe**: `GET /actuator/health` — restarts container if unhealthy
- **Readiness Probe**: `GET /actuator/health` — removes pod from load balancer if not ready
- **JVM Options**: `-Xmx512m -Xms256m -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0`
- **Security**: Runs as non-root user (UID 1001)

### service.yaml
- **Type**: ClusterIP (internal access only)
- **Port**: 80 → 8080 (container port)

### ingress.yaml
- **Class**: `azure/application-gateway` (AGIC)
- **Host**: `resortslite.example.com` (update to your actual domain)
- **Path**: `/` (all traffic routed to application)

---

## Configuration Management

### Environment Variables Reference

| Variable | Description | Default |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | Spring profile | `docker` |
| `SPRING_DATASOURCE_URL` | Oracle JDBC URL | — |
| `SPRING_DATASOURCE_USERNAME` | DB username | — |
| `SPRING_DATASOURCE_PASSWORD` | DB password | — |
| `SPRING_REDIS_HOST` | Azure Cache for Redis hostname | — |
| `SPRING_REDIS_PORT` | Redis port (TLS) | `6380` |
| `SPRING_REDIS_PASSWORD` | Redis access key | — |
| `SPRING_REDIS_SSL` | Enable TLS for Redis | `true` |
| `BOOKING_CACHE_TTL_SECONDS` | Booking cache TTL | `3600` |
| `PAYMENT_API_URL` | Payment service URL | `http://payment-service:9090/payments/charge` |
| `REPORT_BASE_PATH` | Report file base path | `/var/reports/` |
| `JAVA_OPTS` | JVM options | See Dockerfile |

### Using Azure Key Vault with Secrets Store CSI Driver

For production deployments, inject secrets from Azure Key Vault:

```bash
# Install Secrets Store CSI Driver
az aks enable-addons \
  --addons azure-keyvault-secrets-provider \
  --name resortslite-aks \
  --resource-group resortslite-rg

# Create Key Vault secrets
az keyvault secret set --vault-name resortslite-kv --name spring-redis-password --value "your_redis_key"
az keyvault secret set --vault-name resortslite-kv --name spring-datasource-password --value "your_db_password"
```

### Using Kubernetes ConfigMap for Non-Sensitive Config

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: resortslite-config
  namespace: resortslite
data:
  BOOKING_CACHE_TTL_SECONDS: "3600"
  SPRING_REDIS_PORT: "6380"
  SPRING_REDIS_SSL: "true"
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
# Create HPA (scale between 2-10 pods at 70% CPU)
kubectl autoscale deployment resortslite \
  --cpu-percent=70 \
  --min=2 \
  --max=10 \
  -n resortslite

# Check HPA status
kubectl get hpa -n resortslite
```

### Rolling Updates

```bash
# Update image
kubectl set image deployment/resortslite \
  resortslite=myregistry.azurecr.io/resortslite:2.0.0 \
  -n resortslite

# Monitor rollout
kubectl rollout status deployment/resortslite -n resortslite
```

### Rollback

```bash
# Rollback to previous version
kubectl rollout undo deployment/resortslite -n resortslite

# Rollback to specific revision
kubectl rollout history deployment/resortslite -n resortslite
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

# View previous container logs (if crashed)
kubectl logs <pod-name> -n resortslite --previous
```

### Common Issues

**1. ImagePullBackOff**
```bash
# Verify ACR attachment
az aks check-acr --resource-group resortslite-rg --name resortslite-aks --acr myregistry.azurecr.io

# Re-attach ACR
az aks update --resource-group resortslite-rg --name resortslite-aks --attach-acr myregistry
```

**2. CrashLoopBackOff (JVM / Application errors)**
```bash
# Check application logs
kubectl logs <pod-name> -n resortslite --tail=100

# Common causes:
# - Missing environment variables (SPRING_REDIS_HOST, SPRING_DATASOURCE_URL)
# - Redis connection failure (check SPRING_REDIS_HOST, SPRING_REDIS_PASSWORD)
# - Oracle DB unreachable (check SPRING_DATASOURCE_URL, network policies)
```

**3. OOMKilled (Out of Memory)**
```bash
# Increase memory limits in deployment.yaml
# resources.limits.memory: "2Gi"
# Also adjust JAVA_OPTS: -Xmx1536m -Xms512m

kubectl apply -f kubernetes/deployment.yaml
```

**4. Readiness Probe Failing**
```bash
# Check actuator health endpoint
kubectl exec -it <pod-name> -n resortslite -- wget -qO- http://localhost:8080/actuator/health

# Increase initialDelaySeconds in deployment.yaml if JVM startup is slow
```

**5. Ingress Not Accessible**
```bash
# Check ingress status
kubectl describe ingress resortslite-ingress -n resortslite

# Check AGIC logs
kubectl logs -n kube-system -l app=ingress-appgw

# Verify Application Gateway health
az network application-gateway show-backend-health \
  --resource-group resortslite-rg \
  --name resortslite-appgw
```

### Useful Commands

```bash
# Get all resources in namespace
kubectl get all -n resortslite

# Execute shell in running pod
kubectl exec -it <pod-name> -n resortslite -- /bin/sh

# Port-forward for local testing
kubectl port-forward svc/resortslite-service 8080:80 -n resortslite

# View resource usage
kubectl top pods -n resortslite
kubectl top nodes
```

---

## Security Considerations

1. **Secrets Management**: Never store credentials in Kubernetes manifests or ConfigMaps. Use Azure Key Vault with Secrets Store CSI Driver.

2. **Non-Root Container**: The application runs as UID 1001 (non-root). Do not override `runAsUser` in pod spec.

3. **Network Policies**: Restrict pod-to-pod communication using Kubernetes NetworkPolicy resources.

4. **Image Scanning**: Scan Docker images with Azure Defender for Containers or Trivy before deployment.

5. **RBAC**: Apply least-privilege RBAC roles for the application service account.

6. **TLS**: Enable TLS on the ingress for production. Update `ingress.yaml` with TLS certificate configuration.

7. **Redis TLS**: Azure Cache for Redis enforces TLS on port 6380. Ensure `SPRING_REDIS_SSL=true`.

8. **Dependency Vulnerabilities**: The current pom.xml includes known vulnerable dependencies (log4j 2.14.1 CVE-2021-44228, commons-collections 3.2.1 CVE-2015-6420). These **must** be upgraded before production deployment:
   - Upgrade `log4j-core` to 2.17.2 or later
   - Upgrade `commons-collections` to 3.2.2 or later

---

## Java-Specific Notes

- **JVM Startup**: Spring Boot 2.7.x with Java 8 may take 30-60 seconds to start. The `initialDelaySeconds` in liveness/readiness probes is set to 60s/30s accordingly.
- **Container Support**: `-XX:+UseContainerSupport` ensures the JVM respects container memory limits rather than host memory.
- **MaxRAMPercentage**: Set to 75% to leave headroom for non-heap memory (Metaspace, thread stacks, direct buffers).
- **Graceful Shutdown**: `terminationGracePeriodSeconds: 30` allows in-flight requests to complete before pod termination.
- **Redis Session**: Spring Session with Redis enables stateless horizontal scaling — no sticky sessions required.
