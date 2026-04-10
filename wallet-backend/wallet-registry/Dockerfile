# Multi-stage build pro wallet-registry

# === Build stage ===
FROM eclipse-temurin:21-jdk AS builder

WORKDIR /app

COPY gradlew gradlew.bat settings.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY buildSrc ./buildSrc

COPY wallet-registry/build.gradle.kts wallet-registry/

RUN chmod +x gradlew && ./gradlew :wallet-registry:dependencies --no-daemon || true

COPY wallet-registry/src wallet-registry/src

RUN ./gradlew :wallet-registry:installDist --no-daemon

# === Runtime stage ===
FROM eclipse-temurin:21-jre

LABEL maintainer="majny"
LABEL description="Wallet Registry - wallet, device and address management"

WORKDIR /app

COPY --from=builder /app/wallet-registry/build/install/wallet-registry ./

ENV REGISTRY_DB_URL=jdbc:postgresql://localhost:5432/wallet_registry \
    REGISTRY_DB_USER=wallet \
    REGISTRY_DB_PASSWORD=wallet

EXPOSE 8082

HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
    CMD curl -f http://localhost:8082/health || exit 1

ENTRYPOINT ["bin/wallet-registry"]
