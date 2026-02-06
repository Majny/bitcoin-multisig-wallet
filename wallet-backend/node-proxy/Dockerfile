# Multi-stage build pro node-proxy microservice
# Optimalizováno pro ARM64 (Raspberry Pi)

# === Build stage ===
FROM eclipse-temurin:21-jdk AS builder

WORKDIR /app

# Kopíruj Gradle wrapper a konfiguraci
COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle

# Stáhni závislosti (cache layer)
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon || true

# Kopíruj zdrojový kód
COPY src ./src

# Builduj shadow JAR
RUN ./gradlew shadowJar --no-daemon

# === Runtime stage ===
FROM eclipse-temurin:21-jre

LABEL maintainer="majny"
LABEL description="Bitcoin Node Proxy - JSON-RPC proxy pro Bitcoin Core"

WORKDIR /app

# Kopíruj fat JAR z build stage
COPY --from=builder /app/build/libs/*-all.jar app.jar

# Výchozí environment variables (přepsat pomocí docker run -e nebo .env)
ENV RPC_URL=http://127.0.0.1:8332/ \
    RPC_USER=backend \
    RPC_PASS=changeme \
    API_KEY=changeme \
    BIND_HOST=0.0.0.0 \
    PORT=8088 \
    UPSTREAM_CONNECT_TIMEOUT_MS=4000 \
    UPSTREAM_SOCKET_TIMEOUT_MS=8000 \
    UPSTREAM_RETRIES=2

# Exponuj port
EXPOSE 8088

# Health check
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD curl -f http://localhost:8088/health || exit 1

# Spusť aplikaci
ENTRYPOINT ["java", "-jar", "app.jar"]
