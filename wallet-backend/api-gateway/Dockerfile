# Multi-stage build pro api-gateway

# Build stage
FROM eclipse-temurin:21-jdk AS builder

WORKDIR /app

COPY gradlew gradlew.bat settings.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY buildSrc ./buildSrc

COPY api-gateway/build.gradle.kts api-gateway/

RUN chmod +x gradlew && ./gradlew :api-gateway:dependencies --no-daemon || true

COPY api-gateway/src api-gateway/src

RUN ./gradlew :api-gateway:installDist --no-daemon

# Runtime stage
FROM eclipse-temurin:21-jre

LABEL maintainer="majny"
LABEL description="API Gateway - central entry point for wallet backend"

WORKDIR /app

COPY --from=builder /app/api-gateway/build/install/api-gateway ./

ENV PORT=8080

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
    CMD curl -f http://localhost:8080/health || exit 1

ENTRYPOINT ["bin/api-gateway"]
