# Multi-stage build pro blockchain-service

# === Build stage ===
FROM eclipse-temurin:21-jdk AS builder

WORKDIR /app

COPY gradlew gradlew.bat settings.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY buildSrc ./buildSrc

COPY blockchain-service/build.gradle.kts blockchain-service/
COPY utils/build.gradle.kts utils/

RUN chmod +x gradlew && ./gradlew :blockchain-service:dependencies --no-daemon || true

COPY blockchain-service/src blockchain-service/src
COPY utils/src utils/src

RUN ./gradlew :blockchain-service:installDist --no-daemon

# === Runtime stage ===
FROM eclipse-temurin:21-jre

LABEL maintainer="majny"
LABEL description="Blockchain Service - Mempool.space API proxy"

WORKDIR /app

COPY --from=builder /app/blockchain-service/build/install/blockchain-service ./

ENV PORT=8086 \
    MEMPOOL_BASE_URL=https://mempool.space/api

EXPOSE 8086

HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
    CMD curl -f http://localhost:8086/health || exit 1

ENTRYPOINT ["bin/blockchain-service"]
