# ============================================================
# Stage 1: Builder
# ============================================================
FROM maven:3.8.6-openjdk-8-slim AS builder

WORKDIR /workspace

# Copy dependency descriptor first for layer caching
COPY pom.xml .

# Download all dependencies (cached layer unless pom.xml changes)
RUN mvn dependency:go-offline -B

# Copy full source tree
COPY src ./src

# Build the application JAR (skip tests for Docker build)
RUN mvn clean package -DskipTests -B

# ============================================================
# Stage 2: Runtime
# ============================================================
FROM openjdk:8-jdk

# Metadata labels
LABEL maintainer="ResortsLite Team" \
      application="resortslite" \
      version="1.0.0"

# Set timezone
ENV TZ=UTC

# Create non-root user for security
RUN groupadd --gid 1001 appgroup && \
    useradd --uid 1001 --gid appgroup --shell /bin/sh --create-home appuser

WORKDIR /app

# Copy the built JAR from builder stage
COPY --from=builder /workspace/target/*.jar app.jar

# Set ownership
RUN chown -R appuser:appgroup /app

# Switch to non-root user
USER appuser

# Application port
EXPOSE 8080

# JVM optimizations for containerized environments
ENV JAVA_OPTS="-Xmx512m -Xms256m \
  -XX:+UseContainerSupport \
  -XX:MaxRAMPercentage=75.0 \
  -Djava.security.egd=file:/dev/./urandom \
  -Dfile.encoding=UTF-8 \
  -Duser.timezone=UTC"

# Spring profile
ENV SPRING_PROFILES_ACTIVE=docker

# Redis connection (injected at runtime from Azure Key Vault via Secrets Store CSI Driver)
ENV SPRING_REDIS_HOST=""
ENV SPRING_REDIS_PORT="6380"
ENV SPRING_REDIS_PASSWORD=""
ENV SPRING_REDIS_SSL="true"

# Booking cache TTL
ENV BOOKING_CACHE_TTL_SECONDS="3600"

# Report base path
ENV REPORT_BASE_PATH="/var/reports/"

# Payment API URL
ENV PAYMENT_API_URL="http://payment-service:9090/payments/charge"

# Entrypoint with JVM options
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
