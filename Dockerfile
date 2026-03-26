# -----------------------------------------------------------------------------
# Stage 1: Build
# eclipse-temurin:21-jdk-alpine - Java 21 JDK on Alpine (minimal)
# -----------------------------------------------------------------------------
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /workspace

# Copy dependency manifests first for better Docker layer caching
COPY pom.xml .
COPY .mvn .mvn
COPY mvnw .

# Download dependencies (cached unless pom.xml changes)
RUN chmod +x mvnw && \
    ./mvnw dependency:go-offline -q --no-transfer-progress

# Copy source and build - skip tests (run separately in CI)
COPY src src
RUN ./mvnw package -DskipTests -q --no-transfer-progress

# -----------------------------------------------------------------------------
# Stage 2: Runtime
# eclipse-temurin:21-jdk-alpine
# • Runs as non-root
# -----------------------------------------------------------------------------
FROM eclipse-temurin:21-jdk-alpine AS runtime

RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

# k3s: these labels are read by metadata tools and the GitHub Actions workflow
LABEL org.opencontainers.image.title="Multi-Tenant JWT Security Gateway"
LABEL org.opencontainers.image.description="Spring Boot 3.4 / Java 21 multi-tenant API gateway with PostgreSQL RLS"
LABEL org.opencontainers.image.source="https://github.com/AbdennasserBentaleb/jwt-security-gateway"
LABEL org.opencontainers.image.vendor="Internal API"

WORKDIR /application

# Layered jar caching strategy using copied dependencies and classes
COPY --from=builder /workspace/target/dependency/ ./lib/
COPY --from=builder /workspace/target/classes/ ./classes/

# -- JVM tuning for containerised environments --------------------------------
# -XX:+UseContainerSupport - reads CPU/memory from cgroup (k3s resource limits)
ENV JAVA_OPTS="-XX:+UseContainerSupport \
    -XX:MaxRAMPercentage=75.0 \
    -Djava.security.egd=file:/dev/./urandom \
    -Dspring.profiles.active=prod"

# Expose app port (overridable via SERVER_PORT env var)
EXPOSE 8080

# Run Spring Boot application directly from classes
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -cp \"classes:lib/*\" dev.gateway.JwtGatewayApplication"]
