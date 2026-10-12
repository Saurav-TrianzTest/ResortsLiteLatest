# ============================================================
# Stage 1: Builder
# ============================================================
FROM maven:3.8.6-openjdk-8-slim AS builder

WORKDIR /workspace

# Copy Maven build descriptor first for dependency layer caching
COPY pom.xml .

# Download all dependencies (cached layer unless pom.xml changes)
RUN mvn dependency:go-offline -B

# Copy the full source tree
COPY src ./src

# Build the application JAR (skip tests for Docker build)
RUN mvn clean package -DskipTests -B

# ============================================================
# Stage 2: Runtime
# ============================================================
FROM eclipse-temurin:8-jdk

# Timezone configuration
ENV TZ=UTC

WORKDIR /app

# Create a non-root user for security
RUN groupadd --system appgroup && \
    useradd --system --gid appgroup --no-create-home appuser

# Copy the built JAR from the builder stage
COPY --from=builder /workspace/target/*.jar app.jar

# Set ownership
RUN chown appuser:appgroup app.jar

USER appuser

# Application port (from application.properties: server.port=8080)
EXPOSE 8080

# JVM tuning: container-aware memory settings
ENV JAVA_OPTS="-XX:+UseContainerSupport \
               -XX:MaxRAMPercentage=75.0 \
               -XX:+UnlockExperimentalVMOptions \
               -Xms256m \
               -Xmx512m \
               -Dfile.encoding=UTF-8 \
               -Duser.timezone=UTC"

# Spring profile and application environment variables
ENV SPRING_PROFILES_ACTIVE=docker

# Redis / ElastiCache connection (supplied at runtime via Kubernetes Secrets/ConfigMaps)
ENV REDIS_HOST=localhost
ENV REDIS_PORT=6379
ENV REDIS_PASSWORD=

# Oracle datasource (supplied at runtime via Kubernetes Secrets)
ENV SPRING_DATASOURCE_URL=jdbc:oracle:thin:@localhost:1521:ORCL
ENV SPRING_DATASOURCE_USERNAME=admin
ENV SPRING_DATASOURCE_PASSWORD=

# Internal service endpoints (supplied at runtime via Kubernetes ConfigMaps)
ENV PAYMENT_API_URL=http://payment-service:9090/payments/charge
ENV REPORT_BASE_PATH=/var/app/reports/
ENV BOOKING_CACHE_TTL_SECONDS=3600

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
