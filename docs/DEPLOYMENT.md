# ResortsLite — Deployment Guide (Azure AKS)

## Table of Contents
1. [Overview](#overview)
2. [Prerequisites](#prerequisites)
3. [Project Structure](#project-structure)
4. [Local Development with Docker Compose](#local-development-with-docker-compose)
5. [Build & Push Docker Image](#build--push-docker-image)
6. [Azure AKS Deployment](#azure-aks-deployment)
7. [Kubernetes Manifest Reference](#kubernetes-manifest-reference)
8. [Configuration & Environment Variables](#configuration--environment-variables)
9. [Health Checks & Monitoring](#health-checks--monitoring)
10. [Scaling & Rolling Updates](#scaling--rolling-updates)
11. [Troubleshooting](#troubleshooting)
12. [Security Considerations](#security-considerations)

---

## Overview

**ResortsLite** is a Spring Boot 2.7.x / Java 8 resort booking microservice.  
It exposes a REST API (`/api/bookings/*`) and uses:

| Component | Technology |
|-----------|-----------|
| Framework | Spring Boot 2.7.18 |
| Java | 8 (eclipse-temurin:8-jre runtime) |
| Build | Maven 3.8.x |
| Session store | Azure Cache for Redis (Spring Session) |
| Health checks | Spring Boot Actuator `/actuator/health` |
| Target platform | Azure Kubernetes Service (AKS) |

---

## Prerequisites

### Local Development
| Tool | Minimum Version |
|------|----------------|
| Docker Desktop | 24.x |
| Docker Compose | v2.x |
| Java JDK | 8+ |
| Maven | 3.8+ |

### Azure AKS Deployment
| Tool | Notes |
|------|-------|
| Azure CLI (`az`) | `az login` before running scripts |
| `kubectl` | Configured via `az aks get-credentials` |
| Azure Subscription | With AKS cluster and ACR provisioned |
| Azure Cache for Redis | Required for session management |

Install Azure CLI: https://docs.microsoft.com/en-us/cli/azure/install-azure-cli  
Install kubectl: https://kubernetes.io/docs/tasks/tools/

---

## Project Structure

```
Comp java msslq new/
├── Dockerfile                    # Multi-stage build (Java 8)
├── docker-compose.yml            # Local development (app only)
├── .dockerignore                 # Excludes build artifacts & wrappers
├── pom.xml                       # Maven build descriptor
├── src/
│   └── main/
│       ├── java/com/demo/resortslite/
│       │   ├── ResortsLiteApplication.java
│       │   ├── BookingController.java
│       │   ├── BookingService.java
│       │   └── ReportService.java
│       └── resources/
│           └── application.properties
├── kubernetes/
│   ├── namespace.yaml
│   ├── deployment.yaml
│   ├── service.yaml
│   └── ingress.yaml
├── scripts/
│   ├── build-push.sh             # Linux/macOS build & push
│   ├── build-push.bat            # Windows build & push
│   ├── deploy-image.sh           # Linux/macOS AKS deploy
│   └── deploy-image.bat          # Windows AKS deploy
└── docs/
    └── DEPLOYMENT.md             # This file
```

---

## Local Development with Docker Compose

### 1. Configure environment variables

Create a `.env` file in the project root (never commit this file):

```env
REDIS_HOST=localhost
REDIS_PORT=6379
REDIS_PASSWORD=
REDIS_SSL=false
PAYMENT_API_URL=http://payment-service:9090/payments/charge
REPORT_BASE_PATH=/var/reports
BACKUP_PATH=/var/backups/nightly
```

### 2. Start the application

```bash
docker compose up --build
```

The application will be available at: http://localhost:8080

### 3. Verify health

```bash
curl http://localhost:8080/actuator/health
```

Expected response:
```json
{"status":"UP"}
```

### 4. Stop the application

```bash
docker compose down
```

---

## Build & Push Docker Image

### Linux / macOS

```bash
chmod +x scripts/build-push.sh
./scripts/build-push.sh
```

### Windows

```cmd
scripts\build-push.bat
```

The script will prompt you to:
1. Enter an image tag (defaults to `latest`)
2. Select registry type: **Azure ACR** or **Docker Hub**
3. Provide registry credentials

**Example ACR flow:**
```
Enter image tag (press Enter for 'latest'): v1.2.0
Select container registry:
  1. Azure Container Registry (ACR)
  2. Docker Hub
Enter choice [1 or 2]: 1
Enter ACR name (e.g. myregistry): mycompanyacr
Logging in to ACR: mycompanyacr ...
Building Docker image: mycompanyacr.azurecr.io/resortslite:v1.2.0
Pushing image ...
```

---

## Azure AKS Deployment

### Step 1: Provision Azure Resources (one-time)

```bash
# Login to Azure
az login

# Create resource group
az group create --name rg-resortslite --location eastus

# Create AKS cluster
az aks create \
  --resource-group rg-resortslite \
  --name aks-resortslite \
  --node-count 2 \
  --enable-addons monitoring \
  --generate-ssh-keys

# Create Azure Container Registry
az acr create \
  --resource-group rg-resortslite \
  --name resortsliteacr \
  --sku Basic

# Attach ACR to AKS
az aks update \
  --resource-group rg-resortslite \
  --name aks-resortslite \
  --attach-acr resortsliteacr

# Create Azure Cache for Redis
az redis create \
  --resource-group rg-resortslite \
  --name resortslite-redis \
  --sku Basic \
  --vm-size c0 \
  --location eastus
```

### Step 2: Build and push the image

```bash
./scripts/build-push.sh
# Select ACR, enter: resortsliteacr
# Tag: v1.0.0
# Full image: resortsliteacr.azurecr.io/resortslite:v1.0.0
```

### Step 3: Deploy to AKS

```bash
chmod +x scripts/deploy-image.sh
./scripts/deploy-image.sh
```

The script will prompt for:
- Azure Resource Group
- AKS Cluster name
- Full Docker image URI
- Redis connection details (host, port, password, SSL)
- Payment API URL

### Step 4: Verify deployment

```bash
# Get credentials
az aks get-credentials --resource-group rg-resortslite --name aks-resortslite

# Check pods
kubectl get pods -n resortslite

# Check services
kubectl get svc -n resortslite

# Check ingress
kubectl get ingress -n resortslite

# View logs
kubectl logs -l app=resortslite -n resortslite --tail=100
```

---

## Kubernetes Manifest Reference

### namespace.yaml
Creates the `resortslite` namespace to isolate all application resources.

### deployment.yaml
- **Replicas**: 2 (high availability)
- **Image**: `{{IMAGE_URI}}` — replaced by deploy script
- **Resources**: requests `250m CPU / 512Mi RAM`, limits `500m CPU / 1Gi RAM`
- **Liveness probe**: `GET /actuator/health` — initial delay 60s (JVM warm-up)
- **Readiness probe**: `GET /actuator/health` — initial delay 30s
- **Security**: runs as non-root user (UID 1000)
- **Volumes**: `emptyDir` for `/var/reports` and `/var/backups/nightly`

### service.yaml
- **Type**: `ClusterIP` — internal cluster access only
- **Port**: 80 → 8080 (container port)

### ingress.yaml
- **Class**: `azure/application-gateway` (AGIC)
- **Host**: `resortslite.example.com` — update to your actual domain
- **SSL redirect**: enabled via annotation

To update the ingress hostname:
```bash
kubectl patch ingress resortslite-ingress -n resortslite \
  --type='json' \
  -p='[{"op":"replace","path":"/spec/rules/0/host","value":"your-domain.com"}]'
```

---

## Configuration & Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `SERVER_PORT` | Application HTTP port | `8080` |
| `SPRING_PROFILES_ACTIVE` | Spring profile | `docker` |
| `REDIS_HOST` | Azure Cache for Redis hostname | `localhost` |
| `REDIS_PORT` | Redis port | `6379` |
| `REDIS_PASSWORD` | Redis access key | _(empty)_ |
| `REDIS_SSL` | Enable TLS for Redis | `true` |
| `PAYMENT_API_URL` | Payment service endpoint | `http://payment-service:9090/payments/charge` |
| `REPORT_BASE_PATH` | Report file directory | `/var/reports` |
| `BACKUP_PATH` | Backup file directory | `/var/backups/nightly` |
| `JAVA_OPTS` | JVM startup options | See Dockerfile |

### Using Azure Key Vault with Secrets Store CSI Driver

For production, inject Redis credentials from Azure Key Vault:

```bash
# Store Redis password in Key Vault
az keyvault secret set \
  --vault-name resortslite-kv \
  --name redis-password \
  --value "your-redis-access-key"
```

Then mount the secret via the CSI driver in your deployment manifest.

---

## Health Checks & Monitoring

### Actuator Endpoints

| Endpoint | Purpose |
|----------|---------|
| `GET /actuator/health` | Liveness & readiness probe |

```bash
# Check health from within cluster
kubectl exec -it $(kubectl get pod -l app=resortslite -n resortslite -o jsonpath='{.items[0].metadata.name}') \
  -n resortslite -- wget -qO- http://localhost:8080/actuator/health
```

### View Application Logs

```bash
# Stream logs from all pods
kubectl logs -f -l app=resortslite -n resortslite

# Logs from a specific pod
kubectl logs <pod-name> -n resortslite

# Previous container logs (after crash)
kubectl logs <pod-name> -n resortslite --previous
```

---

## Scaling & Rolling Updates

### Manual Scaling

```bash
kubectl scale deployment resortslite --replicas=4 -n resortslite
```

### Horizontal Pod Autoscaler

```bash
kubectl autoscale deployment resortslite \
  --cpu-percent=70 \
  --min=2 \
  --max=10 \
  -n resortslite
```

### Rolling Update (new image)

```bash
kubectl set image deployment/resortslite \
  resortslite=resortsliteacr.azurecr.io/resortslite:v1.1.0 \
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

### Pod not starting

```bash
# Describe pod for events
kubectl describe pod <pod-name> -n resortslite

# Check resource constraints
kubectl top pods -n resortslite
```

**Common causes:**
- `ImagePullBackOff` — ACR not attached to AKS, or wrong image URI
- `CrashLoopBackOff` — Application startup failure; check logs
- `OOMKilled` — Increase memory limits in `deployment.yaml`

### Redis connection failure

```bash
# Verify Redis env vars are set
kubectl exec -it <pod-name> -n resortslite -- env | grep REDIS
```

Ensure `REDIS_HOST`, `REDIS_PORT`, and `REDIS_PASSWORD` are correctly set.  
For Azure Cache for Redis, use port `6380` with `REDIS_SSL=true`.

### Ingress not routing traffic

```bash
# Check AGIC is installed
kubectl get pods -n kube-system | grep ingress

# Check ingress status
kubectl describe ingress resortslite-ingress -n resortslite
```

Ensure the Application Gateway Ingress Controller (AGIC) add-on is enabled on the AKS cluster:
```bash
az aks enable-addons \
  --resource-group rg-resortslite \
  --name aks-resortslite \
  --addons ingress-appgw \
  --appgw-name resortslite-appgw \
  --appgw-subnet-cidr "10.225.0.0/16"
```

### JVM memory issues

Increase memory limits in `kubernetes/deployment.yaml`:
```yaml
resources:
  requests:
    memory: "768Mi"
  limits:
    memory: "1536Mi"
```

And update `JAVA_OPTS`:
```
-Xmx1g -Xms512m -XX:MaxRAMPercentage=75.0
```

---

## Security Considerations

1. **Never commit secrets** — Use Azure Key Vault + Secrets Store CSI Driver
2. **Non-root container** — The application runs as UID 1000 (enforced in Dockerfile and pod spec)
3. **Redis TLS** — Always set `REDIS_SSL=true` in production (Azure Cache for Redis uses port 6380)
4. **Image scanning** — Enable Microsoft Defender for Containers on ACR
5. **Network policies** — Restrict pod-to-pod traffic with Kubernetes NetworkPolicy
6. **RBAC** — Use Azure AD Workload Identity for pod-level Azure resource access
7. **Dependency updates** — The project contains known CVEs (log4j 2.14.1, commons-collections 3.2.1); update these before production deployment

### Recommended dependency updates

| Dependency | Current | Recommended |
|-----------|---------|-------------|
| `log4j-core` | 2.14.1 (CVE-2021-44228) | 2.17.2+ |
| `commons-collections` | 3.2.1 (CVE-2015-6420) | 3.2.2+ |

---

*Generated for ResortsLite — Spring Boot 2.7.x / Java 8 — Azure AKS deployment*
