# Multi-stage build pro explorer-service

# === Build stage ===
FROM eclipse-temurin:21-jdk AS builder

WORKDIR /app

COPY gradlew gradlew.bat settings.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY buildSrc ./buildSrc

COPY explorer-service/build.gradle.kts explorer-service/
COPY utils/build.gradle.kts utils/

RUN chmod +x gradlew && ./gradlew :explorer-service:dependencies --no-daemon || true

COPY explorer-service/src explorer-service/src
COPY utils/src utils/src

RUN ./gradlew :explorer-service:installDist --no-daemon

# === Runtime stage ===
FROM eclipse-temurin:21-jre

LABEL maintainer="majny"
LABEL description="Explorer Service - wallet-level Bitcoin blockchain aggregation"

WORKDIR /app

COPY --from=builder /app/explorer-service/build/install/explorer-service ./

ENV PORT=8083 \
    REGISTRY_URL=http://localhost:8082 \
    BLOCKCHAIN_URL=http://localhost:8086

EXPOSE 8083

HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
    CMD curl -f http://localhost:8083/health || exit 1

ENTRYPOINT ["bin/explorer-service"]
