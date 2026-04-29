# Multi-stage build pro psbt-service microservice

# Build stage
FROM eclipse-temurin:21-jdk AS builder

WORKDIR /app

# Kopíruj Gradle wrapper a hlavní konfiguraci
COPY gradlew gradlew.bat settings.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY buildSrc ./buildSrc

# Kopíruj modul dependencies
COPY psbt-service/build.gradle.kts psbt-service/

# Stáhni závislosti (cache layer)
RUN chmod +x gradlew && ./gradlew :psbt-service:dependencies --no-daemon || true

# Kopíruj zdrojový kód
COPY psbt-service/src psbt-service/src

# Builduj
RUN ./gradlew :psbt-service:installDist --no-daemon

# Runtime stage
FROM eclipse-temurin:21-jre

LABEL maintainer="majny"
LABEL description="PSBT Service - Bitcoin PSBT creation and management"

WORKDIR /app

# Kopíruj distribuci z build stage
COPY --from=builder /app/psbt-service/build/install/psbt-service ./

# Výchozí environment variables
ENV DATABASE_URL=jdbc:postgresql://localhost:5432/wallet_psbt \
    DATABASE_USER=wallet \
    DATABASE_PASSWORD=wallet \
    BLOCKCHAIN_SERVICE_URL=http://localhost:8086 \
    REGISTRY_URL=http://localhost:8082 \
    PORT=8085

# Exponuj port
EXPOSE 8085

# Health check
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD curl -f http://localhost:8085/health || exit 1

# Spusť aplikaci
ENTRYPOINT ["bin/psbt-service"]
