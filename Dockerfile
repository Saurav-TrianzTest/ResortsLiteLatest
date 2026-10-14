# =============================================================================
# Multi-stage Dockerfile for ResortsLite (Spring Boot 2.7.x / Java 8 / Maven)
# Builder : maven:3.8.6-eclipse-temurin-8
# Runtime : eclipse-temurin:8-jre  (explicit base image)
# =============================================================================

# ---------------------------------------------------------------------------
# Stage 1 – Build
# ---------------------------------------------------------------------------
FROM maven:3.8.6-eclipse-temurin-8 AS builder

WORKDIR /workspace

# Copy dependency descriptor first for layer-cache optimisation
COPY pom.xml .

# Pre-download all dependencies (cached unless pom.xml changes)
RUN mvn dependency:go-offline -B

# Copy the full source tree (wrapper files are excluded via .dockerignore)
COPY src ./src

# Build the fat JAR, skip tests
RUN mvn clean package -DskipTests -B

# ---------------------------------------------------------------------------
# Stage 2 – Runtime
# ---------------------------------------------------------------------------
FROM eclipse-temurin:8-jre

# Timezone
ENV TZ=UTC

# Create a non-root user for security
RUN groupadd --system appgroup && \
    useradd --system --gid appgroup --home /app --shell /bin/false appuser

WORKDIR /app

# Copy the fat JAR from the builder stage
COPY --from=builder /workspace/target/*.jar app.jar

# Ensure the non-root user owns the working directory
RUN chown -R appuser:appgroup /app

USER appuser

# Application port (from server.port=8080)
EXPOSE 8080

# JVM tuning: container-aware heap sizing, graceful shutdown signal handling
ENV JAVA_OPTS="-XX:+UseContainerSupport \
               -XX:MaxRAMPercentage=75.0 \
               -XX:+UnlockExperimentalVMOptions \
               -Xms256m \
               -Xmx512m \
               -Djava.security.egd=file:/dev/./urandom \
               -Dfile.encoding=UTF-8 \
               -Duser.timezone=UTC"

# Spring profile for containerised deployments
ENV SPRING_PROFILES_ACTIVE=docker

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
