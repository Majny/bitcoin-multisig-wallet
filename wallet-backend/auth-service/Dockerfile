# Multi-stage build pro auth-service

# Build stage
FROM eclipse-temurin:21-jdk AS builder

WORKDIR /app

COPY gradlew gradlew.bat settings.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY buildSrc ./buildSrc

COPY auth-service/build.gradle.kts auth-service/

RUN chmod +x gradlew && ./gradlew :auth-service:dependencies --no-daemon || true

COPY auth-service/src auth-service/src

RUN ./gradlew :auth-service:installDist --no-daemon

# Runtime stage
FROM eclipse-temurin:21-jre

LABEL maintainer="majny"
LABEL description="Auth Service - JWT authentication with RSA keys"

WORKDIR /app

COPY --from=builder /app/auth-service/build/install/auth-service ./

ENV PORT=8081 \
    JWT_ISSUER=wallet-auth \
    JWT_AUDIENCE=wallet-gateway

EXPOSE 8081

HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
    CMD curl -f http://localhost:8081/health || exit 1

ENTRYPOINT ["bin/auth-service"]
