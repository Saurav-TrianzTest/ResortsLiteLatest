# ResortsLite — Deployment Guide

## Table of Contents
1. [Overview](#overview)
2. [Prerequisites](#prerequisites)
3. [Project Structure](#project-structure)
4. [Local Development with Docker Compose](#local-development-with-docker-compose)
5. [Building and Pushing the Docker Image](#building-and-pushing-the-docker-image)
6. [AWS EKS Deployment](#aws-eks-deployment)
7. [Configuration Management](#configuration-management)
8. [Health Checks and Monitoring](#health-checks-and-monitoring)
9. [Scaling and Rolling Updates](#scaling-and-rolling-updates)
10. [Troubleshooting](#troubleshooting)
11. [Security Considerations](#security-considerations)

---

## Overview

**ResortsLite** is a Java 8 / Spring Boot 2.7.x resort booking application. It exposes a REST API for booking management and uses:

| Component | Technology |
|-----------|-----------|
| Language | Java 8 |
| Framework | Spring Boot 2.7.18 |
| Build tool | Maven 3 |
| Session store | Amazon ElastiCache (Redis) via Spring Session |
| Distributed cache | Amazon ElastiCache (Redis) via Spring Cache |
| Database | Oracle (JDBC) |
| Health endpoint | `/actuator/health` |
| Application port | `8080` |

---

## Prerequisites

### Local Development
| Tool | Minimum Version |
|------|----------------|
| Docker | 24.x |
| Docker Compose | 2.x |
| Java JDK | 8 |
| Maven | 3.8+ |

### AWS EKS Deployment
| Tool | Notes |
|------|-------|
| AWS CLI | v2, configured with appropriate IAM permissions |
| kubectl | Matching your EKS cluster version |
| eksctl | Optional — for cluster creation |
| Helm | Optional — for AWS Load Balancer Controller |

### Required IAM Permissions
- `ecr:GetAuthorizationToken`, `ecr:BatchCheckLayerAvailability`, `ecr:PutImage`
- `eks:DescribeCluster`, `eks:UpdateKubeconfig`
- `elasticache:DescribeCacheClusters` (for Redis connectivity verification)

---

## Project Structure

```
.
├── Dockerfile                  # Multi-stage build (Java 8 → amazoncorretto:8)
├── .dockerignore               # Excludes target/, .git/, wrapper files
├── docker-compose.yml          # Single-service local dev compose file
├── pom.xml                     # Maven build descriptor
├── src/
│   └── main/
│       ├── java/com/demo/resortslite/
│       └── resources/
│           └── application.properties
├── kubernetes/
│   ├── namespace.yaml
│   ├── deployment.yaml         # Contains {{PLACEHOLDER}} values
│   ├── service.yaml
│   └── ingress.yaml
├── scripts/
│   ├── build-push.sh           # Linux/macOS build & push
│   ├── build-push.bat          # Windows build & push
│   ├── deploy-image.sh         # Linux/macOS EKS deploy
│   └── deploy-image.bat        # Windows EKS deploy
└── docs/
    └── DEPLOYMENT.md           # This file
```

---

## Local Development with Docker Compose

### 1. Configure environment variables

Create a `.env` file in the project root (never commit this file):

```dotenv
SPRING_DATASOURCE_URL=jdbc:oracle:thin:@localhost:1521:ORCL
SPRING_DATASOURCE_USERNAME=admin
SPRING_DATASOURCE_PASSWORD=your_password

REDIS_HOST=localhost
REDIS_PORT=6379
CACHE_TTL_MINUTES=30

APP_PAYMENT_ENDPOINT=http://payment-svc:9090/charge
APP_INVENTORY_ENDPOINT=http://inventory-svc:8081/rooms
APP_NOTIFICATION_ENDPOINT=http://notify-svc:7070/send

REPORT_BASE_PATH=/var/reports
```

### 2. Start the application

```bash
# Build and start
docker compose up --build

# Start in background
docker compose up -d --build

# View logs
docker compose logs -f resortsLite

# Stop
docker compose down
```

### 3. Verify the application

```bash
# Health check
curl http://localhost:8080/actuator/health

# Create a booking
curl -X POST "http://localhost:8080/api/bookings/create?guestName=John&roomType=Suite&checkIn=2024-06-01&checkOut=2024-06-05"

# Check booking status
curl http://localhost:8080/api/bookings/status/BK001
```

---

## Building and Pushing the Docker Image

### Linux / macOS

```bash
chmod +x scripts/build-push.sh
bash scripts/build-push.sh
```

### Windows

```cmd
scripts\build-push.bat
```

Both scripts will interactively prompt for:
1. **Registry type**: AWS ECR or Docker Hub
2. **Image tag**: defaults to `latest`
3. **Registry credentials**: AWS account ID + region (ECR) or username/password (Docker Hub)

The scripts automatically:
- Sanitise the image name to lowercase with hyphens
- Create the ECR repository if it does not exist (ECR only)
- Build the Docker image from the project root
- Push the image to the selected registry

---

## AWS EKS Deployment

### Step 1 — EKS Cluster Setup (if not already created)

```bash
# Create cluster (eksctl)
eksctl create cluster \
  --name resortsLite-cluster \
  --region us-east-1 \
  --nodegroup-name standard-workers \
  --node-type t3.medium \
  --nodes 2 \
  --nodes-min 1 \
  --nodes-max 4

# Verify
kubectl get nodes
```

### Step 2 — Install AWS Load Balancer Controller

The Ingress manifest uses the AWS Load Balancer Controller (ALB). Install it via Helm:

```bash
# Add EKS Helm repo
helm repo add eks https://aws.github.io/eks-charts
helm repo update

# Install controller
helm install aws-load-balancer-controller eks/aws-load-balancer-controller \
  -n kube-system \
  --set clusterName=resortsLite-cluster \
  --set serviceAccount.create=false \
  --set serviceAccount.name=aws-load-balancer-controller
```

### Step 3 — Run the deployment script

```bash
# Linux / macOS
chmod +x scripts/deploy-image.sh
bash scripts/deploy-image.sh

# Windows
scripts\deploy-image.bat
```

The script will prompt for:
- AWS region and EKS cluster name
- Full Docker image URI (e.g. `123456789.dkr.ecr.us-east-1.amazonaws.com/resortsLite:latest`)
- All application environment variables (Oracle DB, Redis, service endpoints)

It then:
1. Configures `kubectl` via `aws eks update-kubeconfig`
2. Substitutes all `{{PLACEHOLDER}}` values in `deployment.yaml`
3. Applies manifests in order: namespace → deployment → service → ingress
4. Waits for the rollout to complete
5. Displays the ALB hostname

### Step 4 — Verify deployment

```bash
# Check pods
kubectl get pods -n resortsLite

# Check services
kubectl get svc -n resortsLite

# Check ingress / ALB hostname
kubectl get ingress -n resortsLite

# View pod logs
kubectl logs -l app=resortsLite -n resortsLite --tail=100

# Health check via ALB
curl http://<ALB_HOSTNAME>/actuator/health
```

---

## Configuration Management

### Environment Variables Reference

| Variable | Description | Default |
|----------|-------------|---------|
| `SPRING_DATASOURCE_URL` | Oracle JDBC URL | — |
| `SPRING_DATASOURCE_USERNAME` | Oracle username | — |
| `SPRING_DATASOURCE_PASSWORD` | Oracle password | — |
| `REDIS_HOST` | ElastiCache primary endpoint | `localhost` |
| `REDIS_PORT` | ElastiCache port | `6379` |
| `CACHE_TTL_MINUTES` | Booking cache TTL | `30` |
| `APP_PAYMENT_ENDPOINT` | Payment service URL | — |
| `APP_INVENTORY_ENDPOINT` | Inventory service URL | — |
| `APP_NOTIFICATION_ENDPOINT` | Notification service URL | — |
| `REPORT_BASE_PATH` | Report file base directory | `/var/reports` |
| `SPRING_PROFILES_ACTIVE` | Spring profile | `docker` |
| `JAVA_OPTS` | JVM flags | see Dockerfile |

### Using Kubernetes Secrets for sensitive values

```bash
kubectl create secret generic resortsLite-db-secret \
  --from-literal=SPRING_DATASOURCE_URL="jdbc:oracle:thin:@host:1521:SID" \
  --from-literal=SPRING_DATASOURCE_USERNAME="admin" \
  --from-literal=SPRING_DATASOURCE_PASSWORD="secret" \
  -n resortsLite
```

Reference the secret in `deployment.yaml`:

```yaml
env:
  - name: SPRING_DATASOURCE_PASSWORD
    valueFrom:
      secretKeyRef:
        name: resortsLite-db-secret
        key: SPRING_DATASOURCE_PASSWORD
```

---

## Health Checks and Monitoring

The application exposes Spring Boot Actuator at `/actuator/health`.

```bash
# Liveness probe (Kubernetes)
curl http://<pod-ip>:8080/actuator/health

# Expected response
{"status":"UP","components":{"db":{"status":"UP"},"redis":{"status":"UP"},"diskSpace":{"status":"UP"}}}
```

Kubernetes probes are configured in `deployment.yaml`:
- **Liveness probe**: `GET /actuator/health` — initial delay 60s, period 30s
- **Readiness probe**: `GET /actuator/health` — initial delay 30s, period 15s

The longer initial delay accounts for JVM startup time on Java 8.

---

## Scaling and Rolling Updates

### Manual scaling

```bash
kubectl scale deployment resortsLite --replicas=4 -n resortsLite
```

### Horizontal Pod Autoscaler

```bash
kubectl autoscale deployment resortsLite \
  --cpu-percent=70 \
  --min=2 \
  --max=8 \
  -n resortsLite
```

### Rolling update (new image)

```bash
kubectl set image deployment/resortsLite \
  resortsLite=<NEW_IMAGE_URI> \
  -n resortsLite

# Monitor rollout
kubectl rollout status deployment/resortsLite -n resortsLite
```

### Rollback

```bash
kubectl rollout undo deployment/resortsLite -n resortsLite

# Rollback to specific revision
kubectl rollout undo deployment/resortsLite --to-revision=2 -n resortsLite

# View rollout history
kubectl rollout history deployment/resortsLite -n resortsLite
```

---

## Troubleshooting

### Pod not starting

```bash
# Describe pod for events
kubectl describe pod -l app=resortsLite -n resortsLite

# View logs
kubectl logs -l app=resortsLite -n resortsLite --previous
```

**Common causes:**
- `CrashLoopBackOff`: Check `SPRING_DATASOURCE_URL` and `REDIS_HOST` are reachable from the pod
- `ImagePullBackOff`: Verify ECR permissions and image URI
- `OOMKilled`: Increase memory limit in `deployment.yaml`

### Redis connection failure

```bash
# Verify ElastiCache security group allows inbound on port 6379 from EKS node SG
# Test connectivity from a debug pod
kubectl run redis-test --image=redis:alpine --rm -it --restart=Never -n resortsLite -- \
  redis-cli -h <REDIS_HOST> -p 6379 ping
```

### Oracle database connection failure

```bash
# Verify RDS/Oracle security group allows inbound on port 1521 from EKS node SG
# Check SPRING_DATASOURCE_URL format: jdbc:oracle:thin:@<host>:<port>:<SID>
```

### Ingress / ALB not provisioning

```bash
# Check AWS Load Balancer Controller logs
kubectl logs -n kube-system -l app.kubernetes.io/name=aws-load-balancer-controller

# Verify IAM role for service account (IRSA) is configured correctly
```

### JVM memory issues

Adjust `JAVA_OPTS` in `deployment.yaml`:

```yaml
- name: JAVA_OPTS
  value: "-Xms512m -Xmx768m -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"
```

Also increase the container memory limit accordingly.

---

## Security Considerations

1. **Never commit secrets** — use Kubernetes Secrets or AWS Secrets Manager
2. **Non-root container** — the Dockerfile creates and uses `appuser` (non-root)
3. **Read-only filesystem** — consider adding `readOnlyRootFilesystem: true` to the security context
4. **Network policies** — restrict pod-to-pod traffic with Kubernetes NetworkPolicy
5. **Image scanning** — enable ECR image scanning on push
6. **Log4Shell** — `pom.xml` includes `log4j-core:2.14.1` which has CVE-2021-44228; upgrade to `2.17.2+` before production deployment
7. **commons-collections** — `3.2.1` has CVE-2015-6420; upgrade to `3.2.2+`
8. **TLS** — configure ALB with HTTPS listener and ACM certificate for production
9. **Secrets rotation** — use AWS Secrets Manager with automatic rotation for DB credentials
10. **Pod Security Standards** — apply `restricted` pod security standard to the namespace

---

*Generated for ResortsLite v1.0.0 — Java 8 / Spring Boot 2.7.18 — Target: AWS EKS*
