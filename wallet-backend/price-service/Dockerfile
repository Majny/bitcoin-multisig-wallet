# Multi-stage build pro price-service

# Build stage
FROM eclipse-temurin:21-jdk AS builder

WORKDIR /app

COPY gradlew gradlew.bat settings.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY buildSrc ./buildSrc

COPY price-service/build.gradle.kts price-service/

RUN chmod +x gradlew && ./gradlew :price-service:dependencies --no-daemon || true

COPY price-service/src price-service/src

RUN ./gradlew :price-service:installDist --no-daemon

# Runtime stage
FROM eclipse-temurin:21-jre

LABEL maintainer="majny"
LABEL description="Price Service - Bitcoin/fiat price data from CoinGecko"

WORKDIR /app

COPY --from=builder /app/price-service/build/install/price-service ./

ENV PORT=8087 \
    COINGECKO_BASE_URL=https://api.coingecko.com/api/v3

EXPOSE 8087

HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
    CMD curl -f http://localhost:8087/health || exit 1

ENTRYPOINT ["bin/price-service"]
