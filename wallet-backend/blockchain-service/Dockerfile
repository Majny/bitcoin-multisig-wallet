# Multi-stage build pro blockchain-service

# Build stage
FROM eclipse-temurin:21-jdk AS builder

WORKDIR /app

COPY gradlew gradlew.bat settings.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY buildSrc ./buildSrc

COPY blockchain-service/build.gradle.kts blockchain-service/

RUN chmod +x gradlew && ./gradlew :blockchain-service:dependencies --no-daemon || true

COPY blockchain-service/src blockchain-service/src

RUN ./gradlew :blockchain-service:installDist --no-daemon

# Runtime stage
FROM eclipse-temurin:21-jre

LABEL maintainer="majny"
LABEL description="Blockchain Service - Mempool.space API proxy"

WORKDIR /app

COPY --from=builder /app/blockchain-service/build/install/blockchain-service ./

ENV PORT=8086 \
    MAINNET_MEMPOOL_URL=https://blockstream.info/api \
    TESTNET_MEMPOOL_URL=https://mempool.space/testnet4/api \
    MAINNET_FEES_URL=https://mempool.space/api \
    TESTNET_FEES_URL=https://mempool.space/testnet4/api

EXPOSE 8086

HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
    CMD curl -f http://localhost:8086/health || exit 1

ENTRYPOINT ["bin/blockchain-service"]
