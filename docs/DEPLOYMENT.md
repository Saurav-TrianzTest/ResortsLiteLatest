# ResortsLite — Deployment Guide

## Table of Contents
1. [Overview](#overview)
2. [Prerequisites](#prerequisites)
3. [Project Structure](#project-structure)
4. [Local Development with Docker Compose](#local-development-with-docker-compose)
5. [Building and Pushing the Docker Image](#building-and-pushing-the-docker-image)
6. [AWS EKS Deployment](#aws-eks-deployment)
7. [Configuration Management](#configuration-management)
8. [Kubernetes Resource Reference](#kubernetes-resource-reference)
9. [Scaling and Management](#scaling-and-management)
10. [Troubleshooting](#troubleshooting)
11. [Security Considerations](#security-considerations)

---

## Overview

**Application**: ResortsLite  
**Framework**: Spring Boot 2.7.x  
**Java Version**: Java 8  
**Build Tool**: Maven  
**Target Platform**: AWS EKS (Elastic Kubernetes Service)  
**Application Port**: 8080  
**Health Endpoint**: `/actuator/health`

ResortsLite is a legacy resort booking application modernised for cloud-native deployment on AWS EKS. It uses Amazon ElastiCache (Redis) for distributed session management and caching, and Oracle Database for persistent storage.

---

## Prerequisites

### Local Development
| Tool | Version | Purpose |
|------|---------|---------|
| Docker | 20.10+ | Container runtime |
| Docker Compose | 2.x | Local orchestration |
| Java JDK | 8+ | Local build (optional) |
| Maven | 3.8+ | Local build (optional) |

### AWS EKS Deployment
| Tool | Version | Purpose |
|------|---------|---------|
| AWS CLI | 2.x | AWS authentication and ECR |
| kubectl | 1.27+ | Kubernetes cluster management |
| eksctl | 0.150+ | EKS cluster creation (optional) |

### AWS IAM Permissions Required
```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "ecr:GetAuthorizationToken",
        "ecr:BatchCheckLayerAvailability",
        "ecr:GetDownloadUrlForLayer",
        "ecr:BatchGetImage",
        "ecr:PutImage",
        "ecr:InitiateLayerUpload",
        "ecr:UploadLayerPart",
        "ecr:CompleteLayerUpload",
        "ecr:CreateRepository",
        "ecr:DescribeRepositories",
        "eks:DescribeCluster",
        "eks:ListClusters"
      ],
      "Resource": "*"
    }
  ]
}
```

---

## Project Structure

```
Appdborc2/
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
│       │   └── entity/Doctor.java
│       └── resources/
│           └── application.properties
├── kubernetes/
│   ├── namespace.yaml            # Kubernetes namespace
│   ├── deployment.yaml           # Application deployment
│   ├── service.yaml              # ClusterIP service
│   └── ingress.yaml              # AWS ALB ingress
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

### 1. Configure Environment Variables

Create a `.env` file in the project root (never commit this file):

```bash
# Oracle Database
SPRING_DATASOURCE_URL=jdbc:oracle:thin:@your-oracle-host:1521:ORCL
SPRING_DATASOURCE_USERNAME=admin
SPRING_DATASOURCE_PASSWORD=your-secure-password

# Redis / ElastiCache
REDIS_HOST=localhost
REDIS_PORT=6379
REDIS_PASSWORD=
REDIS_SSL=false

# Internal service endpoints
PAYMENT_API_URL=http://payment-service:9090/payments/charge

# Application
BOOKING_CACHE_TTL_SECONDS=3600
REPORT_BASE_PATH=/var/reports
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
curl -X POST "http://localhost:8080/api/bookings/create?guestName=John&roomType=STANDARD&checkIn=2024-01-15&checkOut=2024-01-20"
```

---

## Building and Pushing the Docker Image

### Linux / macOS

```bash
# Make the script executable
chmod +x scripts/build-push.sh

# Run from project root
./scripts/build-push.sh
```

The script will prompt you to:
1. Select registry type (AWS ECR or Docker Hub)
2. Enter registry credentials and details
3. Specify an image tag (defaults to `latest`)

### Windows

```cmd
# Run from project root
scripts\build-push.bat
```

### Manual Docker Build

```bash
# Build image
docker build -t resortslite:latest .

# Tag for ECR
docker tag resortslite:latest 123456789012.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest

# Login to ECR
aws ecr get-login-password --region us-east-1 | \
  docker login --username AWS --password-stdin 123456789012.dkr.ecr.us-east-1.amazonaws.com

# Push
docker push 123456789012.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest
```

---

## AWS EKS Deployment

### Step 1: Configure AWS CLI

```bash
aws configure
# Enter: AWS Access Key ID, Secret Access Key, Region, Output format
```

### Step 2: Create or Connect to EKS Cluster

**Create a new cluster (if needed):**
```bash
eksctl create cluster \
  --name resortslite-cluster \
  --region us-east-1 \
  --nodegroup-name standard-workers \
  --node-type t3.medium \
  --nodes 2 \
  --nodes-min 1 \
  --nodes-max 4 \
  --managed
```

**Connect to existing cluster:**
```bash
aws eks update-kubeconfig --region us-east-1 --name your-cluster-name
kubectl cluster-info
```

### Step 3: Install AWS Load Balancer Controller

The ingress resource requires the AWS Load Balancer Controller:

```bash
# Create IAM policy
curl -O https://raw.githubusercontent.com/kubernetes-sigs/aws-load-balancer-controller/v2.6.0/docs/install/iam_policy.json
aws iam create-policy \
  --policy-name AWSLoadBalancerControllerIAMPolicy \
  --policy-document file://iam_policy.json

# Install via Helm
helm repo add eks https://aws.github.io/eks-charts
helm repo update
helm install aws-load-balancer-controller eks/aws-load-balancer-controller \
  -n kube-system \
  --set clusterName=your-cluster-name \
  --set serviceAccount.create=false \
  --set serviceAccount.name=aws-load-balancer-controller
```

### Step 4: Run the Deployment Script

**Linux / macOS:**
```bash
chmod +x scripts/deploy-image.sh
./scripts/deploy-image.sh
```

**Windows:**
```cmd
scripts\deploy-image.bat
```

The script will prompt for:
- AWS Region and EKS Cluster Name
- Full Docker image URI (from ECR or Docker Hub)
- All required environment variables (Oracle DB, Redis, service endpoints)

### Step 5: Verify Deployment

```bash
# Check all resources
kubectl get all -n resortslite

# Check pod logs
kubectl logs -l app=resortslite -n resortslite --tail=100

# Check ingress
kubectl get ingress -n resortslite

# Test health endpoint
kubectl port-forward svc/resortslite-service 8080:80 -n resortslite
curl http://localhost:8080/actuator/health
```

---

## Configuration Management

### Environment Variables Reference

| Variable | Description | Default |
|----------|-------------|---------|
| `SPRING_DATASOURCE_URL` | Oracle JDBC connection URL | `jdbc:oracle:thin:@localhost:1521:ORCL` |
| `SPRING_DATASOURCE_USERNAME` | Oracle DB username | `admin` |
| `SPRING_DATASOURCE_PASSWORD` | Oracle DB password | *(required)* |
| `REDIS_HOST` | Redis/ElastiCache hostname | `localhost` |
| `REDIS_PORT` | Redis port | `6379` |
| `REDIS_PASSWORD` | Redis authentication password | *(empty)* |
| `REDIS_SSL` | Enable Redis TLS | `false` |
| `PAYMENT_API_URL` | Payment service endpoint | `http://payment-service:9090/payments/charge` |
| `BOOKING_CACHE_TTL_SECONDS` | Redis cache TTL in seconds | `3600` |
| `REPORT_BASE_PATH` | Report file storage path | `/var/reports` |
| `SPRING_PROFILES_ACTIVE` | Active Spring profile | `docker` |
| `JAVA_OPTS` | JVM options | See Dockerfile |

### Using AWS Secrets Manager (Recommended for Production)

```bash
# Store database credentials
aws secretsmanager create-secret \
  --name resortslite/db-credentials \
  --secret-string '{"username":"admin","password":"your-password"}'

# Store Redis credentials
aws secretsmanager create-secret \
  --name resortslite/redis-credentials \
  --secret-string '{"password":"your-redis-password"}'
```

Use the AWS Secrets Manager CSI driver or External Secrets Operator to inject secrets into Kubernetes pods.

### Using Kubernetes Secrets

```bash
# Create secret for database credentials
kubectl create secret generic resortslite-db-secret \
  --from-literal=username=admin \
  --from-literal=password=your-secure-password \
  -n resortslite

# Create secret for Redis
kubectl create secret generic resortslite-redis-secret \
  --from-literal=password=your-redis-password \
  -n resortslite
```

---

## Kubernetes Resource Reference

### Namespace
- **Name**: `resortslite`
- **Purpose**: Isolates all ResortsLite resources

### Deployment
- **Replicas**: 2 (for high availability)
- **Image**: Configurable via `{{IMAGE_URI}}` placeholder
- **Resources**: 250m CPU / 512Mi memory (requests), 500m CPU / 1Gi memory (limits)
- **Liveness Probe**: `GET /actuator/health` — starts after 60s, every 30s
- **Readiness Probe**: `GET /actuator/health` — starts after 30s, every 15s

### Service
- **Type**: ClusterIP
- **Port**: 80 → 8080 (container)

### Ingress
- **Class**: AWS ALB (Application Load Balancer)
- **Scheme**: internet-facing
- **Health Check Path**: `/actuator/health`
- **Host**: `resortslite.example.com` *(update to your domain)*

---

## Scaling and Management

### Horizontal Pod Autoscaling

```bash
# Create HPA
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
  resortslite=123456789012.dkr.ecr.us-east-1.amazonaws.com/resortslite:v2.0.0 \
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

### Manual Scaling

```bash
# Scale to 4 replicas
kubectl scale deployment resortslite --replicas=4 -n resortslite
```

---

## Troubleshooting

### Pod Not Starting

```bash
# Check pod status
kubectl get pods -n resortslite

# Describe pod for events
kubectl describe pod <pod-name> -n resortslite

# Check logs
kubectl logs <pod-name> -n resortslite
kubectl logs <pod-name> -n resortslite --previous  # crashed container
```

**Common causes:**
- `CrashLoopBackOff`: Application startup failure — check logs for Oracle/Redis connection errors
- `ImagePullBackOff`: ECR authentication issue — verify IAM role for node group
- `OOMKilled`: Increase memory limits in `deployment.yaml`

### Database Connection Issues

```bash
# Verify datasource environment variable
kubectl exec -it <pod-name> -n resortslite -- env | grep SPRING_DATASOURCE

# Test Oracle connectivity from pod
kubectl exec -it <pod-name> -n resortslite -- sh
```

Ensure the Oracle DB security group allows inbound traffic from the EKS node group security group on port 1521.

### Redis / ElastiCache Connection Issues

```bash
# Verify Redis environment variables
kubectl exec -it <pod-name> -n resortslite -- env | grep REDIS

# Check ElastiCache security group
# Allow inbound port 6379 from EKS node group security group
```

### Ingress / ALB Not Provisioning

```bash
# Check AWS Load Balancer Controller logs
kubectl logs -n kube-system -l app.kubernetes.io/name=aws-load-balancer-controller

# Verify ingress annotations
kubectl describe ingress resortslite-ingress -n resortslite
```

Ensure the EKS cluster has the correct subnet tags:
- Public subnets: `kubernetes.io/role/elb = 1`
- Private subnets: `kubernetes.io/role/internal-elb = 1`

### Health Check Failures

```bash
# Test health endpoint directly
kubectl port-forward svc/resortslite-service 8080:80 -n resortslite
curl -v http://localhost:8080/actuator/health
```

JVM startup can take 30-60 seconds. If pods are being killed before startup completes, increase `initialDelaySeconds` in the liveness probe.

---

## Security Considerations

1. **Never commit secrets**: Use `.env` files locally and AWS Secrets Manager in production
2. **Non-root container**: The application runs as `appuser` (UID 1000) — never run as root
3. **Network policies**: Consider adding Kubernetes NetworkPolicy to restrict pod-to-pod traffic
4. **Image scanning**: Enable ECR image scanning to detect vulnerabilities
5. **IRSA**: Use IAM Roles for Service Accounts for fine-grained AWS permissions
6. **TLS**: Enable HTTPS on the ALB ingress using ACM certificates
7. **Secrets rotation**: Rotate Oracle and Redis credentials regularly using AWS Secrets Manager rotation

### Enable HTTPS on Ingress

Add the following annotations to `kubernetes/ingress.yaml`:
```yaml
annotations:
  alb.ingress.kubernetes.io/certificate-arn: arn:aws:acm:us-east-1:123456789012:certificate/your-cert-id
  alb.ingress.kubernetes.io/ssl-policy: ELBSecurityPolicy-TLS13-1-2-2021-06
```

---

## Java-Specific Notes

### JVM Memory Configuration

The container is configured with:
```
-XX:+UseContainerSupport        # Respect container memory limits
-XX:MaxRAMPercentage=75.0       # Use 75% of container memory for heap
-Xms256m                        # Initial heap size
-Xmx512m                        # Maximum heap size
```

With a 1Gi memory limit, the JVM will use approximately 768Mi for heap.

### Spring Boot Actuator

The `/actuator/health` endpoint is exposed and used for both liveness and readiness probes. The endpoint returns:
- `UP` — application is healthy
- `DOWN` — application has a health issue (triggers pod restart)

### Spring Session with Redis

The application uses Spring Session backed by Amazon ElastiCache (Redis) for distributed session management. All pod instances share session state, enabling horizontal scaling without sticky sessions.

### Graceful Shutdown

Spring Boot 2.7.x supports graceful shutdown. The container has a `terminationGracePeriodSeconds: 30` to allow in-flight requests to complete before the pod is terminated.
