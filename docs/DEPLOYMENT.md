# ResortsLite — Deployment Guide

## Overview

This guide covers building, containerizing, and deploying the **ResortsLite** Spring Boot application (Java 8) to **AWS EKS (Elastic Kubernetes Service)**.

- **Framework**: Spring Boot 2.7.x
- **Java Version**: 8 (eclipse-temurin:8-jre runtime)
- **Build Tool**: Maven
- **Application Port**: 8080
- **Health Endpoint**: `/actuator/health`
- **Session Store**: Spring Session + Amazon ElastiCache (Redis)
- **Database**: Oracle (via JDBC)

---

## Prerequisites

### Local Development
| Tool | Version | Purpose |
|------|---------|---------|
| Java JDK | 8+ | Local builds |
| Maven | 3.8+ | Build tool |
| Docker | 20.10+ | Container builds |
| Docker Compose | 2.x | Local orchestration |

### AWS EKS Deployment
| Tool | Version | Purpose |
|------|---------|---------|
| AWS CLI | 2.x | AWS authentication & ECR |
| kubectl | 1.27+ | Kubernetes management |
| eksctl | 0.150+ | EKS cluster management |

### IAM Permissions Required
- `ecr:GetAuthorizationToken`
- `ecr:BatchCheckLayerAvailability`
- `ecr:PutImage`
- `ecr:InitiateLayerUpload`
- `ecr:UploadLayerPart`
- `ecr:CompleteLayerUpload`
- `ecr:CreateRepository`
- `eks:DescribeCluster`
- `eks:ListClusters`

---

## Project Structure

```
Test_Comp1/
├── Dockerfile                    # Multi-stage Docker build
├── docker-compose.yml            # Local development compose
├── .dockerignore                 # Docker build exclusions
├── pom.xml                       # Maven build descriptor
├── src/
│   └── main/
│       ├── java/com/demo/resortslite/
│       │   ├── ResortsLiteApplication.java
│       │   ├── BookingController.java
│       │   ├── BookingService.java
│       │   ├── ReportService.java
│       │   ├── RedisConfig.java
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

## Local Development Setup

### 1. Build the Application Locally

```bash
mvn clean package -DskipTests
```

### 2. Run with Docker Compose

Create a `.env` file in the project root:

```env
SPRING_DATASOURCE_URL=jdbc:oracle:thin:@<your-db-host>:1521:ORCL
SPRING_DATASOURCE_USERNAME=admin
SPRING_DATASOURCE_PASSWORD=your_password
REDIS_HOST=localhost
REDIS_PORT=6379
REDIS_PASSWORD=
REDIS_SSL=false
PAYMENT_API_URL=http://payment-service:9090/payments/charge
REPORT_BASE_PATH=/var/reports
```

Start the application:

```bash
docker-compose up --build
```

Access the application at: `http://localhost:8080`

Health check: `http://localhost:8080/actuator/health`

### 3. Stop the Application

```bash
docker-compose down
```

---

## Docker Build

### Build the Image Manually

```bash
docker build -t resortslite:latest .
```

### Run the Container Manually

```bash
docker run -d \
  -p 8080:8080 \
  -e SPRING_DATASOURCE_URL="jdbc:oracle:thin:@<db-host>:1521:ORCL" \
  -e SPRING_DATASOURCE_USERNAME="admin" \
  -e SPRING_DATASOURCE_PASSWORD="your_password" \
  -e REDIS_HOST="<redis-host>" \
  -e REDIS_PORT="6379" \
  -e REDIS_PASSWORD="" \
  -e REDIS_SSL="false" \
  -e PAYMENT_API_URL="http://payment-service:9090/payments/charge" \
  -e REPORT_BASE_PATH="/var/reports" \
  --name resortslite-app \
  resortslite:latest
```

---

## Build & Push to Registry

### Linux/macOS

```bash
chmod +x scripts/build-push.sh
./scripts/build-push.sh
```

The script will prompt you to:
1. Enter an image tag (default: `latest`)
2. Select registry type (AWS ECR or Docker Hub)
3. Provide registry credentials

### Windows

```cmd
scripts\build-push.bat
```

### Manual ECR Push

```bash
# Authenticate
aws ecr get-login-password --region us-east-1 | \
  docker login --username AWS --password-stdin \
  <account-id>.dkr.ecr.us-east-1.amazonaws.com

# Create repository (first time only)
aws ecr create-repository --repository-name resortslite --region us-east-1

# Build and tag
docker build -t resortslite:latest .
docker tag resortslite:latest \
  <account-id>.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest

# Push
docker push <account-id>.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest
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
aws eks update-kubeconfig --region us-east-1 --name resortslite-cluster
```

### Step 3: Install AWS Load Balancer Controller (for Ingress)

```bash
# Add EKS Helm chart repository
helm repo add eks https://aws.github.io/eks-charts
helm repo update

# Install AWS Load Balancer Controller
helm install aws-load-balancer-controller eks/aws-load-balancer-controller \
  -n kube-system \
  --set clusterName=resortslite-cluster \
  --set serviceAccount.create=false \
  --set serviceAccount.name=aws-load-balancer-controller
```

### Step 4: Deploy Using Script

**Linux/macOS:**

```bash
chmod +x scripts/deploy-image.sh
./scripts/deploy-image.sh
```

**Windows:**

```cmd
scripts\deploy-image.bat
```

The script will prompt for:
- AWS Region
- EKS Cluster Name
- Docker image URI (full ECR path with tag)
- Application environment variables (Oracle DB, Redis, etc.)

### Step 5: Manual Deployment (Alternative)

Update `kubernetes/deployment.yaml` — replace all `{{PLACEHOLDER}}` values:

```bash
# Replace image URI
sed -i 's|{{IMAGE_URI}}|<account-id>.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest|g' \
  kubernetes/deployment.yaml

# Replace environment variables
sed -i 's|{{SPRING_DATASOURCE_URL}}|jdbc:oracle:thin:@<db-host>:1521:ORCL|g' kubernetes/deployment.yaml
sed -i 's|{{SPRING_DATASOURCE_USERNAME}}|admin|g' kubernetes/deployment.yaml
sed -i 's|{{SPRING_DATASOURCE_PASSWORD}}|your_password|g' kubernetes/deployment.yaml
sed -i 's|{{REDIS_HOST}}|<elasticache-endpoint>|g' kubernetes/deployment.yaml
sed -i 's|{{REDIS_PORT}}|6379|g' kubernetes/deployment.yaml
sed -i 's|{{REDIS_PASSWORD}}||g' kubernetes/deployment.yaml
sed -i 's|{{REDIS_SSL}}|false|g' kubernetes/deployment.yaml
sed -i 's|{{PAYMENT_API_URL}}|http://payment-service:9090/payments/charge|g' kubernetes/deployment.yaml
sed -i 's|{{REPORT_BASE_PATH}}|/var/reports|g' kubernetes/deployment.yaml

# Apply manifests
kubectl apply -f kubernetes/namespace.yaml
kubectl apply -f kubernetes/deployment.yaml
kubectl apply -f kubernetes/service.yaml
kubectl apply -f kubernetes/ingress.yaml
```

### Step 6: Verify Deployment

```bash
# Check pods
kubectl get pods -n resortslite

# Check services
kubectl get svc -n resortslite

# Check ingress
kubectl get ingress -n resortslite

# View pod logs
kubectl logs -f deployment/resortslite -n resortslite

# Describe deployment
kubectl describe deployment resortslite -n resortslite
```

---

## Kubernetes Manifest Descriptions

| File | Kind | Description |
|------|------|-------------|
| `namespace.yaml` | Namespace | Creates `resortslite` namespace |
| `deployment.yaml` | Deployment | 2 replicas, health probes, resource limits |
| `service.yaml` | Service | ClusterIP on port 80 → 8080 |
| `ingress.yaml` | Ingress | AWS ALB with internet-facing scheme |

### Resource Limits

```yaml
resources:
  requests:
    cpu: "250m"
    memory: "512Mi"
  limits:
    cpu: "500m"
    memory: "1Gi"
```

### Health Probes

- **Liveness**: `GET /actuator/health` — initial delay 60s, period 30s
- **Readiness**: `GET /actuator/health` — initial delay 30s, period 15s

> **Note**: Initial delay is set to 60s to allow JVM startup time for Java 8 applications.

---

## Configuration Management

### Environment Variables Reference

| Variable | Description | Example |
|----------|-------------|---------|
| `SPRING_DATASOURCE_URL` | Oracle JDBC connection URL | `jdbc:oracle:thin:@host:1521:ORCL` |
| `SPRING_DATASOURCE_USERNAME` | Database username | `admin` |
| `SPRING_DATASOURCE_PASSWORD` | Database password | (use Secrets Manager) |
| `REDIS_HOST` | ElastiCache Redis endpoint | `my-cluster.abc.cache.amazonaws.com` |
| `REDIS_PORT` | Redis port | `6379` |
| `REDIS_PASSWORD` | Redis auth token | (use Secrets Manager) |
| `REDIS_SSL` | Enable TLS for Redis | `true` |
| `PAYMENT_API_URL` | Payment service endpoint | `https://payment-svc/charge` |
| `REPORT_BASE_PATH` | Report file base path | `/var/reports` |
| `SPRING_PROFILES_ACTIVE` | Spring profile | `docker` |
| `JAVA_OPTS` | JVM options | `-Xms256m -Xmx512m` |

### Using AWS Secrets Manager (Recommended for Production)

```bash
# Store database credentials
aws secretsmanager create-secret \
  --name resortslite/db-credentials \
  --secret-string '{"username":"admin","password":"your_password"}'

# Store Redis credentials
aws secretsmanager create-secret \
  --name resortslite/redis-credentials \
  --secret-string '{"password":"your_redis_password"}'
```

Use the [AWS Secrets Manager CSI Driver](https://docs.aws.amazon.com/secretsmanager/latest/userguide/integrating_csi_driver.html) to mount secrets as environment variables in EKS pods.

---

## Scaling and Management

### Horizontal Pod Autoscaling

```bash
kubectl autoscale deployment resortslite \
  --cpu-percent=70 \
  --min=2 \
  --max=10 \
  -n resortslite
```

### Rolling Update

```bash
# Update image
kubectl set image deployment/resortslite \
  resortslite=<account-id>.dkr.ecr.us-east-1.amazonaws.com/resortslite:v2.0.0 \
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

| Issue | Cause | Solution |
|-------|-------|----------|
| `CrashLoopBackOff` | App startup failure | Check logs for DB/Redis connection errors |
| `ImagePullBackOff` | ECR auth failure | Verify IAM role has ECR pull permissions |
| `OOMKilled` | Memory limit exceeded | Increase memory limit or tune JVM heap |
| `Pending` pods | Insufficient node resources | Scale up node group |
| Health probe failing | JVM slow startup | Increase `initialDelaySeconds` |
| Redis connection refused | Wrong REDIS_HOST | Verify ElastiCache endpoint and security groups |
| Oracle connection refused | Wrong DB URL | Verify Oracle endpoint and VPC routing |

### Check Ingress / ALB

```bash
# Get ALB hostname
kubectl get ingress resortslite-ingress -n resortslite \
  -o jsonpath='{.status.loadBalancer.ingress[0].hostname}'

# Check ALB controller logs
kubectl logs -n kube-system deployment/aws-load-balancer-controller
```

### Check Application Health

```bash
# Port-forward for local testing
kubectl port-forward svc/resortslite-service 8080:80 -n resortslite

# Test health endpoint
curl http://localhost:8080/actuator/health
```

---

## Security Considerations

1. **Secrets Management**: Never store database passwords or Redis credentials in `deployment.yaml`. Use AWS Secrets Manager with the CSI driver or Kubernetes Secrets.

2. **Non-Root Container**: The Dockerfile runs the application as a non-root user (`appuser`) for security.

3. **Network Policies**: Consider adding Kubernetes NetworkPolicies to restrict pod-to-pod communication.

4. **Image Scanning**: Enable ECR image scanning to detect vulnerabilities:
   ```bash
   aws ecr put-image-scanning-configuration \
     --repository-name resortslite \
     --image-scanning-configuration scanOnPush=true \
     --region us-east-1
   ```

5. **IRSA (IAM Roles for Service Accounts)**: Use IRSA to grant the application pod fine-grained AWS permissions without static credentials.

6. **TLS**: The Ingress is configured with HTTPS redirect. Ensure an ACM certificate is associated with the ALB.

---

## Java-Specific Notes

### JVM Memory Configuration

The application uses container-aware JVM settings:

```
-Xms256m                    # Initial heap size
-Xmx512m                    # Maximum heap size
-XX:+UseContainerSupport    # Enable container memory awareness
-XX:MaxRAMPercentage=75.0   # Use 75% of container memory for heap
```

Adjust these in `kubernetes/deployment.yaml` under the `JAVA_OPTS` environment variable based on your workload.

### Spring Boot Actuator

Health endpoints are exposed at:
- **Health**: `http://<host>/actuator/health`

The management endpoints are configured in `application.properties`:
```properties
management.endpoints.web.exposure.include=health
management.endpoint.health.show-details=always
management.endpoints.web.base-path=/actuator
```

### Spring Session + Redis

The application uses Spring Session with Amazon ElastiCache (Redis) for distributed session management. This ensures sessions are preserved across pod restarts and shared across all replicas. Configure the ElastiCache endpoint via `REDIS_HOST` and `REDIS_PORT` environment variables.

### Oracle Database

The application connects to Oracle Database via JDBC. Ensure:
- The EKS node security group allows outbound traffic to the Oracle DB port (1521)
- The Oracle DB security group allows inbound traffic from the EKS node security group
- The `SPRING_DATASOURCE_URL` uses the correct Oracle JDBC format: `jdbc:oracle:thin:@<host>:<port>:<sid>`
