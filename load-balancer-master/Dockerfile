# ============================================================
# Master Node Dockerfile
# ============================================================
# Spring Boot application with ZGC for low-latency metrics aggregation.
# ============================================================

ARG JAVA_VERSION=21

# Build stage
FROM eclipse-temurin:${JAVA_VERSION}-jdk-alpine AS builder

WORKDIR /build

# Install Maven
RUN apk add --no-cache maven

# Copy root pom and all modules
COPY pom.xml .
COPY load-balancer-common ./load-balancer-common
COPY load-balancer-master ./load-balancer-master
COPY load-balancer-worker ./load-balancer-worker

# Build entire project
RUN mvn clean package -DskipTests -B

# Runtime stage
FROM eclipse-temurin:${JAVA_VERSION}-jre-alpine

ARG APP_NAME=load-balancer-master
ARG APP_VERSION=1.0.0

# Create non-root user
RUN addgroup -S loadtest && adduser -S master -G loadtest

WORKDIR /app

# Copy JAR
COPY --from=builder /build/target/${APP_NAME}-${APP_VERSION}.jar app.jar

# Create log directory
RUN mkdir -p /var/log/loadtest && chown -R master:loadtest /var/log/loadtest

USER master

# JVM configuration optimized for master node
ENV JAVA_OPTS="-Xms1g \
              -Xmx2g \
              -XX:+UseZGC \
              -XX:+ZGenerational \
              -XX:+UseContainerSupport \
              -XX:MaxRAMPercentage=75.0 \
              -XX:+ExitOnOutOfMemoryError \
              -XX:+HeapDumpOnOutOfMemoryError \
              -XX:HeapDumpPath=/var/log/loadtest \
              -Xlog:gc*:file=/var/log/loadtest/gc.log:time,uptime,level,tags \
              --enable-preview \
              -Dspring.profiles.active=container"

# Expose ports
EXPOSE 8080 9090

# Health check
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=5 \
  CMD wget -q --timeout=5 -O /dev/null http://localhost:8080/api/actuator/health || exit 1

# Entry point
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
