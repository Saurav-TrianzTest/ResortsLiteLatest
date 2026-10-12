# ResortsLite — Deployment Guide (AWS EKS)

## Table of Contents
1. [Overview](#overview)
2. [Prerequisites](#prerequisites)
3. [Project Structure](#project-structure)
4. [Local Development with Docker Compose](#local-development-with-docker-compose)
5. [Build and Push Docker Image](#build-and-push-docker-image)
6. [AWS EKS Deployment](#aws-eks-deployment)
7. [Configuration Management](#configuration-management)
8. [Kubernetes Resource Reference](#kubernetes-resource-reference)
9. [Scaling and Management](#scaling-and-management)
10. [Troubleshooting](#troubleshooting)
11. [Security Considerations](#security-considerations)
12. [Java-Specific Notes](#java-specific-notes)

---

## Overview

**Application**: ResortsLite  
**Framework**: Spring Boot 2.7.x  
**Java Version**: Java 8  
**Build Tool**: Maven  
**Target Platform**: AWS EKS (Elastic Kubernetes Service)  
**Application Port**: 8080  
**Health Endpoint**: `/actuator/health`

ResortsLite is a legacy resort booking application modernised for cloud-native deployment on AWS EKS. It uses:
- **Spring Boot Actuator** for health checks
- **Spring Session + Amazon ElastiCache (Redis)** for distributed session management
- **Oracle Database** for persistent booking data
- **Spring Data JPA** for ORM

---

## Prerequisites

### Local Development
| Tool | Version | Purpose |
|------|---------|---------|
| Docker Desktop | 24.x+ | Container build and local run |
| Docker Compose | 2.x+ | Local multi-service orchestration |
| Java JDK | 8+ | Local build (optional) |
| Maven | 3.8+ | Local build (optional) |

### AWS EKS Deployment
| Tool | Version | Purpose |
|------|---------|---------|
| AWS CLI | 2.x | AWS authentication and ECR operations |
| kubectl | 1.27+ | Kubernetes cluster management |
| eksctl | 0.160+ | EKS cluster provisioning (optional) |

### AWS IAM Permissions Required
```
ecr:GetAuthorizationToken
ecr:BatchCheckLayerAvailability
ecr:GetDownloadUrlForLayer
ecr:BatchGetImage
ecr:CreateRepository
ecr:DescribeRepositories
ecr:PutImage
ecr:InitiateLayerUpload
ecr:UploadLayerPart
ecr:CompleteLayerUpload
eks:DescribeCluster
eks:ListClusters
```

---

## Project Structure

```
sad/
├── Dockerfile                    # Multi-stage Docker build (Java 8 / Maven)
├── docker-compose.yml            # Local development (application only)
├── .dockerignore                 # Excludes build artefacts and wrapper files
├── pom.xml                       # Maven build descriptor
├── src/
│   └── main/
│       ├── java/com/demo/resortslite/
│       │   ├── ResortsLiteApplication.java
│       │   ├── BookingController.java
│       │   ├── BookingService.java
│       │   ├── ReportService.java
│       │   ├── RedisSessionConfig.java
│       │   └── entity/Doctor.java
│       └── resources/
│           └── application.properties
├── kubernetes/
│   ├── namespace.yaml            # Kubernetes namespace
│   ├── deployment.yaml           # Application deployment (2 replicas)
│   ├── service.yaml              # ClusterIP service
│   └── ingress.yaml              # AWS ALB ingress
├── scripts/
│   ├── build-push.sh             # Linux/macOS: build and push to ECR/Docker Hub
│   ├── build-push.bat            # Windows: build and push to ECR/Docker Hub
│   ├── deploy-image.sh           # Linux/macOS: deploy to EKS
│   └── deploy-image.bat          # Windows: deploy to EKS
└── docs/
    └── DEPLOYMENT.md             # This file
```

---

## Local Development with Docker Compose

### 1. Configure Environment Variables

Create a `.env` file in the project root (never commit this file):

```bash
# Oracle datasource
SPRING_DATASOURCE_URL=jdbc:oracle:thin:@your-oracle-host:1521:ORCL
SPRING_DATASOURCE_USERNAME=admin
SPRING_DATASOURCE_PASSWORD=your-password

# Redis (local Redis or ElastiCache)
REDIS_HOST=localhost
REDIS_PORT=6379
REDIS_PASSWORD=

# Internal service endpoints
PAYMENT_API_URL=http://payment-service:9090/payments/charge
REPORT_BASE_PATH=/var/app/reports/
BOOKING_CACHE_TTL_SECONDS=3600
```

### 2. Start the Application

```bash
# Build and start
docker-compose up --build

# Start in background
docker-compose up -d --build

# View logs
docker-compose logs -f resortslite

# Stop
docker-compose down
```

### 3. Verify the Application

```bash
# Health check
curl http://localhost:8080/actuator/health

# Test booking endpoint
curl -X POST "http://localhost:8080/api/bookings/create?guestName=John&roomType=STANDARD&checkIn=2024-06-01&checkOut=2024-06-05"
```

---

## Build and Push Docker Image

### Linux / macOS

```bash
chmod +x scripts/build-push.sh
./scripts/build-push.sh
```

The script will prompt for:
1. Image tag (default: `latest`)
2. Registry type: `1` for AWS ECR, `2` for Docker Hub
3. Registry-specific credentials

### Windows

```cmd
scripts\build-push.bat
```

### Manual Build (ECR Example)

```bash
# Authenticate to ECR
aws ecr get-login-password --region us-east-1 | \
  docker login --username AWS --password-stdin \
  123456789012.dkr.ecr.us-east-1.amazonaws.com

# Create repository (first time only)
aws ecr create-repository --repository-name resortslite --region us-east-1

# Build and push
docker build -t 123456789012.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest .
docker push 123456789012.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest
```

---

## AWS EKS Deployment

### Step 1: Create or Connect to EKS Cluster

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

# Connect to existing cluster
aws eks update-kubeconfig --region us-east-1 --name resortslite-cluster
```

### Step 2: Install AWS Load Balancer Controller

The ingress manifest uses the AWS ALB Ingress Controller. Install it if not already present:

```bash
# Add the EKS chart repository
helm repo add eks https://aws.github.io/eks-charts
helm repo update

# Install the controller
helm install aws-load-balancer-controller eks/aws-load-balancer-controller \
  -n kube-system \
  --set clusterName=resortslite-cluster \
  --set serviceAccount.create=false \
  --set serviceAccount.name=aws-load-balancer-controller
```

### Step 3: Create Kubernetes Secrets

Before deploying, create secrets for sensitive values:

```bash
# Database credentials
kubectl create secret generic resortslite-db-secret \
  --namespace resortslite \
  --from-literal=username=admin \
  --from-literal=password='your-db-password'

# Redis auth token
kubectl create secret generic resortslite-redis-secret \
  --namespace resortslite \
  --from-literal=password='your-redis-auth-token'
```

> **Note**: Create the namespace first (`kubectl apply -f kubernetes/namespace.yaml`) before creating secrets.

### Step 4: Deploy Using Script

```bash
# Linux/macOS
chmod +x scripts/deploy-image.sh
./scripts/deploy-image.sh

# Windows
scripts\deploy-image.bat
```

The script will prompt for:
- AWS region
- EKS cluster name
- Full Docker image URI
- Oracle datasource URL
- Redis/ElastiCache host
- Payment API URL

### Step 5: Manual Deployment (Alternative)

```bash
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

### Step 6: Verify Deployment

```bash
# Check pod status
kubectl get pods -n resortslite

# Check pod logs
kubectl logs -l app=resortslite -n resortslite --tail=100

# Check ingress and get ALB hostname
kubectl get ingress -n resortslite

# Health check via ALB
curl http://<ALB_HOSTNAME>/actuator/health
```

---

## Configuration Management

### Environment Variables Reference

| Variable | Default | Description |
|----------|---------|-------------|
| `SPRING_PROFILES_ACTIVE` | `docker` | Active Spring profile |
| `SPRING_DATASOURCE_URL` | `jdbc:oracle:thin:@localhost:1521:ORCL` | Oracle JDBC URL |
| `SPRING_DATASOURCE_USERNAME` | `admin` | Database username (use Secret) |
| `SPRING_DATASOURCE_PASSWORD` | _(empty)_ | Database password (use Secret) |
| `REDIS_HOST` | `localhost` | ElastiCache primary endpoint |
| `REDIS_PORT` | `6379` | Redis port |
| `REDIS_PASSWORD` | _(empty)_ | Redis auth token (use Secret) |
| `PAYMENT_API_URL` | `http://payment-service:9090/payments/charge` | Payment service endpoint |
| `REPORT_BASE_PATH` | `/var/app/reports/` | Report file base path |
| `BOOKING_CACHE_TTL_SECONDS` | `3600` | Booking cache TTL in seconds |
| `JAVA_OPTS` | See Dockerfile | JVM tuning flags |

### Using AWS Secrets Manager with EKS

For production, use the AWS Secrets Manager CSI driver to inject secrets:

```bash
# Install Secrets Store CSI Driver
helm repo add secrets-store-csi-driver \
  https://kubernetes-sigs.github.io/secrets-store-csi-driver/charts
helm install csi-secrets-store \
  secrets-store-csi-driver/secrets-store-csi-driver \
  --namespace kube-system
```

---

## Kubernetes Resource Reference

### Namespace (`kubernetes/namespace.yaml`)
Creates the `resortslite` namespace to isolate all application resources.

### Deployment (`kubernetes/deployment.yaml`)
- **Replicas**: 2 (for high availability)
- **Image**: `{{IMAGE_URI}}` (replaced at deploy time)
- **Resources**: 250m CPU / 512Mi memory (requests); 500m CPU / 1Gi memory (limits)
- **Liveness Probe**: `GET /actuator/health` — initial delay 60s, period 30s
- **Readiness Probe**: `GET /actuator/health` — initial delay 30s, period 15s
- **Graceful Shutdown**: `terminationGracePeriodSeconds: 30`

### Service (`kubernetes/service.yaml`)
- **Type**: ClusterIP (internal only; ALB handles external traffic)
- **Port**: 80 → 8080 (container port)

### Ingress (`kubernetes/ingress.yaml`)
- **Controller**: AWS ALB (internet-facing)
- **Health Check Path**: `/actuator/health`
- **TLS**: SSL redirect enabled (port 443)
- **Host**: `resortslite.example.com` (update to your domain)

---

## Scaling and Management

### Horizontal Pod Autoscaler (HPA)

```bash
kubectl autoscale deployment resortslite \
  --namespace resortslite \
  --cpu-percent=70 \
  --min=2 \
  --max=10
```

### Rolling Update

```bash
# Update image
kubectl set image deployment/resortslite \
  resortslite=<NEW_IMAGE_URI> \
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

### Scale Manually

```bash
kubectl scale deployment resortslite --replicas=4 -n resortslite
```

---

## Troubleshooting

### Pod Not Starting

```bash
# Describe pod for events
kubectl describe pod -l app=resortslite -n resortslite

# Check logs
kubectl logs -l app=resortslite -n resortslite --previous
```

**Common causes**:
- `CrashLoopBackOff`: Check application logs for startup errors (DB/Redis connection failures)
- `ImagePullBackOff`: Verify ECR permissions and image URI
- `Pending`: Check node resource availability

### Database Connection Issues

```bash
# Verify secret exists
kubectl get secret resortslite-db-secret -n resortslite

# Test connectivity from a pod
kubectl run -it --rm debug --image=busybox --restart=Never -n resortslite -- \
  nc -zv <oracle-host> 1521
```

### Redis / ElastiCache Connection Issues

```bash
# Verify Redis secret
kubectl get secret resortslite-redis-secret -n resortslite

# Test Redis connectivity
kubectl run -it --rm redis-test --image=redis:alpine --restart=Never -n resortslite -- \
  redis-cli -h <elasticache-endpoint> -p 6379 ping
```

### Ingress / ALB Issues

```bash
# Check ingress status
kubectl describe ingress resortslite-ingress -n resortslite

# Check ALB controller logs
kubectl logs -n kube-system -l app.kubernetes.io/name=aws-load-balancer-controller
```

### Health Check Failures

```bash
# Check actuator health directly
kubectl port-forward svc/resortslite-service 8080:80 -n resortslite
curl http://localhost:8080/actuator/health
```

---

## Security Considerations

1. **Never commit secrets**: Use Kubernetes Secrets or AWS Secrets Manager for all credentials
2. **Non-root container**: The Dockerfile runs the application as `appuser` (non-root)
3. **Network policies**: Consider adding Kubernetes NetworkPolicies to restrict pod-to-pod traffic
4. **Image scanning**: Enable ECR image scanning to detect vulnerabilities
5. **IRSA**: Use IAM Roles for Service Accounts (IRSA) for fine-grained AWS permissions
6. **TLS**: The ingress is configured with SSL redirect; ensure a valid ACM certificate is attached
7. **Secrets rotation**: Rotate database and Redis credentials regularly via AWS Secrets Manager

---

## Java-Specific Notes

### JVM Memory Configuration

The container is configured with container-aware JVM flags:
```
-XX:+UseContainerSupport       # Respect container memory limits
-XX:MaxRAMPercentage=75.0      # Use 75% of container memory for heap
-Xms256m                       # Initial heap size
-Xmx512m                       # Maximum heap size
-Dfile.encoding=UTF-8
-Duser.timezone=UTC
```

With a 1Gi memory limit, the JVM will use approximately 768Mi for heap.

### Spring Boot Actuator

Health endpoints are exposed at:
- **Liveness**: `GET /actuator/health`
- **Readiness**: `GET /actuator/health`

The `management.endpoints.web.exposure.include=health` property ensures only the health endpoint is exposed publicly.

### Spring Session + ElastiCache

Session data is stored in Amazon ElastiCache (Redis) via Spring Session. This enables:
- Stateless horizontal scaling (no sticky sessions required)
- Session persistence across pod restarts
- 30-minute session TTL (configurable via `spring.session.timeout`)

### Java 8 Compatibility

This application targets Java 8. The Docker image uses `eclipse-temurin:8-jdk` as the explicit base image. When upgrading to Java 11+, update:
1. `pom.xml`: `<java.version>11</java.version>`
2. `Dockerfile`: Change runtime image to `eclipse-temurin:11-jre-alpine`
3. `Dockerfile`: Change builder image to `maven:3.9.4-eclipse-temurin-11`
