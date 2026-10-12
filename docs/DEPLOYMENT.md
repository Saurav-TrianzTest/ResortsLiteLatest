# ResortsLite — Deployment Guide (AWS EKS)

## Table of Contents
1. [Overview](#overview)
2. [Prerequisites](#prerequisites)
3. [Project Structure](#project-structure)
4. [Local Development with Docker Compose](#local-development-with-docker-compose)
5. [Build & Push Docker Image](#build--push-docker-image)
6. [AWS EKS Deployment](#aws-eks-deployment)
7. [Kubernetes Manifest Reference](#kubernetes-manifest-reference)
8. [Configuration & Environment Variables](#configuration--environment-variables)
9. [Health Checks & Monitoring](#health-checks--monitoring)
10. [Scaling & Rolling Updates](#scaling--rolling-updates)
11. [Troubleshooting](#troubleshooting)
12. [Security Considerations](#security-considerations)

---

## Overview

**ResortsLite** is a Spring Boot 2.7.x / Java 8 resort booking application. It exposes a REST API (`/api/bookings`) and uses:

| Component | Technology |
|-----------|-----------|
| Framework | Spring Boot 2.7.18 |
| Java | Java 8 (eclipse-temurin:8-jre) |
| Build | Maven 3.8.x |
| Database | Oracle DB (via JDBC) |
| Session Store | Amazon ElastiCache for Redis |
| Cache | Amazon ElastiCache for Redis |
| Health | Spring Boot Actuator `/actuator/health` |

---

## Prerequisites

### Local Development
| Tool | Version | Install |
|------|---------|---------|
| Docker | 20.10+ | https://docs.docker.com/get-docker/ |
| Docker Compose | 2.x | Bundled with Docker Desktop |
| Java | 8+ | https://adoptium.net/ |
| Maven | 3.8+ | https://maven.apache.org/download.cgi |

### AWS EKS Deployment
| Tool | Version | Install |
|------|---------|---------|
| AWS CLI | v2 | https://docs.aws.amazon.com/cli/latest/userguide/install-cliv2.html |
| kubectl | 1.27+ | https://kubernetes.io/docs/tasks/tools/ |
| eksctl | 0.150+ | https://eksctl.io/installation/ |

### AWS IAM Permissions Required
```
ecr:GetAuthorizationToken
ecr:BatchCheckLayerAvailability
ecr:GetDownloadUrlForLayer
ecr:BatchGetImage
ecr:CreateRepository
ecr:DescribeRepositories
ecr:PutImage
eks:DescribeCluster
eks:ListClusters
```

---

## Project Structure

```
Valid/
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
│       │   ├── ReportService.java
│       │   ├── SessionConfig.java
│       │   └── entity/Doctor.java
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
│   ├── deploy-image.sh           # Linux/macOS EKS deploy
│   └── deploy-image.bat          # Windows EKS deploy
└── docs/
    └── DEPLOYMENT.md             # This file
```

---

## Local Development with Docker Compose

### 1. Create a `.env` file
```bash
cp .env.example .env   # or create manually
```

Populate `.env` with your local values:
```env
SPRING_DATASOURCE_URL=jdbc:oracle:thin:@localhost:1521:ORCL
SPRING_DATASOURCE_USERNAME=admin
SPRING_DATASOURCE_PASSWORD=yourpassword
REDIS_HOST=localhost
REDIS_PORT=6379
REDIS_PASSWORD=
PAYMENT_API_URL=http://payment-service:9090/payments/charge
APP_PAYMENT_ENDPOINT=http://payment-service:9090/charge
APP_INVENTORY_ENDPOINT=http://inventory-service:8081/rooms
APP_NOTIFICATION_ENDPOINT=http://notify-service:7070/send
REPORT_BASE_PATH=/var/reports
BACKUP_PATH=/var/backups
CACHE_TTL_SECONDS=3600
```

### 2. Start the application
```bash
docker-compose up --build
```

### 3. Verify the application is running
```bash
curl http://localhost:8080/actuator/health
```

Expected response:
```json
{"status":"UP"}
```

### 4. Stop the application
```bash
docker-compose down
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
1. Select registry type (AWS ECR or Docker Hub)
2. Enter registry credentials / AWS account details
3. Enter an image tag (defaults to `latest`)

The script automatically:
- Sanitizes the image name to lowercase with hyphens
- Creates the ECR repository if it does not exist (ECR only)
- Builds and pushes the Docker image

---

## AWS EKS Deployment

### Step 1: Configure AWS CLI
```bash
aws configure
# Enter: AWS Access Key ID, Secret Access Key, Region, Output format
```

### Step 2: Create or connect to an EKS Cluster
```bash
# Create a new cluster (if needed)
eksctl create cluster \
  --name resortslite-cluster \
  --region us-east-1 \
  --nodegroup-name standard-workers \
  --node-type t3.medium \
  --nodes 2 \
  --nodes-min 1 \
  --nodes-max 4

# Or update kubeconfig for an existing cluster
aws eks update-kubeconfig --region us-east-1 --name resortslite-cluster
```

### Step 3: Install AWS Load Balancer Controller (for Ingress)
```bash
# Add the EKS chart repo
helm repo add eks https://aws.github.io/eks-charts
helm repo update

# Install the controller
helm install aws-load-balancer-controller eks/aws-load-balancer-controller \
  -n kube-system \
  --set clusterName=resortslite-cluster \
  --set serviceAccount.create=false \
  --set serviceAccount.name=aws-load-balancer-controller
```

### Step 4: Run the deploy script
```bash
chmod +x scripts/deploy-image.sh
./scripts/deploy-image.sh
```

The script will prompt for:
- AWS Region
- EKS Cluster Name
- Full Docker image URI (e.g., `123456789.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest`)
- Application environment variables (Oracle DB, Redis, service endpoints)

### Step 5: Verify deployment
```bash
kubectl get pods -n resortslite
kubectl get svc -n resortslite
kubectl get ingress -n resortslite
```

### Step 6: Access the application
```bash
# Get the ALB hostname
kubectl get ingress resortslite-ingress -n resortslite \
  -o jsonpath='{.status.loadBalancer.ingress[0].hostname}'
```

---

## Kubernetes Manifest Reference

### namespace.yaml
Creates the `resortslite` namespace to isolate all application resources.

### deployment.yaml
- **Replicas**: 2 (for high availability)
- **Image**: `{{IMAGE_URI}}` — replaced by deploy script
- **Resources**: requests `250m CPU / 512Mi RAM`, limits `500m CPU / 1Gi RAM`
- **Liveness Probe**: `GET /actuator/health` — starts after 60s, every 30s
- **Readiness Probe**: `GET /actuator/health` — starts after 30s, every 15s
- **Volumes**: `emptyDir` for `/var/reports` and `/var/backups`

### service.yaml
- **Type**: `ClusterIP` — internal cluster access only
- **Port**: 80 → 8080 (container port)

### ingress.yaml
- **Class**: `alb` (AWS Load Balancer Controller)
- **Scheme**: `internet-facing`
- **Health check path**: `/actuator/health`
- **Host**: `resortslite.example.com` — update to your actual domain

---

## Configuration & Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `SPRING_PROFILES_ACTIVE` | Spring profile | `docker` |
| `SPRING_DATASOURCE_URL` | Oracle JDBC URL | — |
| `SPRING_DATASOURCE_USERNAME` | DB username | — |
| `SPRING_DATASOURCE_PASSWORD` | DB password | — |
| `REDIS_HOST` | ElastiCache endpoint | `localhost` |
| `REDIS_PORT` | Redis port | `6379` |
| `REDIS_PASSWORD` | Redis AUTH token | _(empty)_ |
| `PAYMENT_API_URL` | Payment service URL | cluster DNS |
| `APP_PAYMENT_ENDPOINT` | Payment charge endpoint | cluster DNS |
| `APP_INVENTORY_ENDPOINT` | Inventory service URL | cluster DNS |
| `APP_NOTIFICATION_ENDPOINT` | Notification service URL | cluster DNS |
| `REPORT_BASE_PATH` | Report file directory | `/var/reports` |
| `BACKUP_PATH` | Backup file directory | `/var/backups` |
| `CACHE_TTL_SECONDS` | Redis cache TTL | `3600` |
| `JAVA_OPTS` | JVM options | see Dockerfile |

### Using Kubernetes Secrets for Sensitive Values
```bash
kubectl create secret generic resortslite-secrets \
  --from-literal=SPRING_DATASOURCE_PASSWORD='yourpassword' \
  --from-literal=REDIS_PASSWORD='yourredistoken' \
  -n resortslite
```

Then reference in `deployment.yaml`:
```yaml
- name: SPRING_DATASOURCE_PASSWORD
  valueFrom:
    secretKeyRef:
      name: resortslite-secrets
      key: SPRING_DATASOURCE_PASSWORD
```

---

## Health Checks & Monitoring

### Spring Boot Actuator Endpoints
| Endpoint | Purpose |
|----------|---------|
| `GET /actuator/health` | Application health status |

### Check pod health manually
```bash
kubectl exec -it <pod-name> -n resortslite -- \
  wget -qO- http://localhost:8080/actuator/health
```

### View application logs
```bash
kubectl logs -f deployment/resortslite -n resortslite
```

---

## Scaling & Rolling Updates

### Manual scaling
```bash
kubectl scale deployment resortslite --replicas=4 -n resortslite
```

### Horizontal Pod Autoscaler (HPA)
```bash
kubectl autoscale deployment resortslite \
  --cpu-percent=70 \
  --min=2 \
  --max=10 \
  -n resortslite
```

### Rolling update (new image)
```bash
kubectl set image deployment/resortslite \
  resortslite=<NEW_IMAGE_URI> \
  -n resortslite

kubectl rollout status deployment/resortslite -n resortslite
```

### Rollback
```bash
kubectl rollout undo deployment/resortslite -n resortslite
```

---

## Troubleshooting

### Pods not starting
```bash
kubectl describe pod <pod-name> -n resortslite
kubectl logs <pod-name> -n resortslite --previous
```

**Common causes:**
- `ImagePullBackOff` — ECR credentials not configured; check IAM role for node group
- `CrashLoopBackOff` — Application startup failure; check logs for DB/Redis connection errors
- `OOMKilled` — Increase memory limits in `deployment.yaml`

### Cannot connect to Oracle DB
- Verify `SPRING_DATASOURCE_URL` points to a reachable Oracle endpoint from within the VPC
- Ensure security groups allow port 1521 from EKS node group

### Redis connection failures
- Verify `REDIS_HOST` is the ElastiCache primary endpoint
- Ensure ElastiCache security group allows port 6379 from EKS node group
- If AUTH is enabled, set `REDIS_PASSWORD` correctly

### Ingress not getting an ALB hostname
```bash
kubectl describe ingress resortslite-ingress -n resortslite
```
- Ensure AWS Load Balancer Controller is installed and running
- Verify the IAM policy for the controller includes `elasticloadbalancing:*` permissions

### JVM memory issues
Adjust `JAVA_OPTS` in `deployment.yaml`:
```yaml
- name: JAVA_OPTS
  value: "-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -Xms512m -Xmx768m"
```

---

## Security Considerations

1. **Secrets Management**: Store `SPRING_DATASOURCE_PASSWORD` and `REDIS_PASSWORD` in Kubernetes Secrets or AWS Secrets Manager — never in plain ConfigMaps or environment variables in source control.

2. **Non-root Container**: The Dockerfile runs the application as a non-root user (`appuser`). Do not override `runAsUser` in the pod spec.

3. **Network Policies**: Consider adding Kubernetes NetworkPolicies to restrict pod-to-pod communication.

4. **Image Scanning**: Enable ECR image scanning on push to detect vulnerabilities:
   ```bash
   aws ecr put-image-scanning-configuration \
     --repository-name resortslite \
     --image-scanning-configuration scanOnPush=true \
     --region us-east-1
   ```

5. **IRSA (IAM Roles for Service Accounts)**: Use IRSA to grant the application pod fine-grained AWS permissions without embedding credentials.

6. **TLS/HTTPS**: Update the Ingress to use HTTPS with ACM certificates:
   ```yaml
   annotations:
     alb.ingress.kubernetes.io/listen-ports: '[{"HTTPS": 443}]'
     alb.ingress.kubernetes.io/certificate-arn: arn:aws:acm:us-east-1:ACCOUNT:certificate/CERT-ID
   ```

7. **Dependency Vulnerabilities**: The `pom.xml` includes `log4j-core:2.14.1` (CVE-2021-44228) and `commons-collections:3.2.1` (CVE-2015-6420). **Upgrade these before production deployment.**
