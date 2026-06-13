# Optimized for minimal footprint and maximum I/O performance.
# Uses Eclipse Temurin (OpenJDK) 21 LTS with ZGC.

ARG JAVA_VERSION=21

# Build stage
FROM eclipse-temurin:${JAVA_VERSION}-jdk-alpine AS builder

WORKDIR /build

# Install Maven and build tools
RUN apk add --no-cache maven build-base protobuf protobuf-dev gcompat libc6-compat

# Copy root pom and all modules
COPY pom.xml .
COPY load-balancer-common ./load-balancer-common
COPY load-balancer-master ./load-balancer-master
COPY load-balancer-worker ./load-balancer-worker

# Build entire project
RUN mvn clean package -DskipTests -B

# Runtime stage - minimal image
FROM eclipse-temurin:${JAVA_VERSION}-jre-alpine

# Build arguments
ARG APP_NAME=load-balancer-worker
ARG APP_VERSION=1.0.0

# Create non-root user for security
RUN addgroup -S loadtest && adduser -S worker -G loadtest

WORKDIR /app

# Copy JAR from builder
COPY --from=builder /build/target/${APP_NAME}-${APP_VERSION}.jar app.jar

# Create log directory
RUN mkdir -p /var/log/loadtest && chown -R worker:loadtest /var/log/loadtest

# Switch to non-root user
USER worker

# JVM configuration for load generation
ENV JAVA_OPTS="-XX:+UseZGC \
  -XX:+ZGenerational \
  -XX:+UnlockExperimentalVMOptions \
  -XX:ZAllocationSpikeTolerance=5 \
  -XX:+ExitOnOutOfMemoryError \
  -Xms512m \
  -Xmx1g \
  -XX:+HeapDumpOnOutOfMemoryError \
  -XX:HeapDumpPath=/var/log/loadtest \
  -Xlog:gc*:file=/var/log/loadtest/gc.log:time,uptime,level,tags \
  --enable-preview \
  -Djava.util.concurrent.ForkJoinPool.common.parallelism=2"

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
  CMD pgrep -f "load-balancer-worker" || exit 1

# Expose gRPC port
EXPOSE 50051

# Entry point
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
