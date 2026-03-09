# ─────────────────────────────────────────────────────────────────────────────
# Stage 1: Build
# eclipse-temurin:25-jdk-alpine — Java 25 JDK on Alpine (minimal)
# ─────────────────────────────────────────────────────────────────────────────
FROM eclipse-temurin:25-jdk-alpine AS builder

WORKDIR /workspace

# Copy dependency manifests first for better Docker layer caching
COPY pom.xml .
COPY .mvn .mvn
COPY mvnw .

# Download dependencies (cached unless pom.xml changes)
RUN chmod +x mvnw && \
    ./mvnw dependency:go-offline -q --no-transfer-progress

# Copy source and build — skip tests (run separately in CI)
COPY src src
RUN ./mvnw package -DskipTests -q --no-transfer-progress && \
    java -Djarmode=layertools -jar target/*.jar extract --destination target/extracted

# ─────────────────────────────────────────────────────────────────────────────
# Stage 2: Runtime
# eclipse-temurin:25-jdk-alpine
# • Runs as non-root
# ─────────────────────────────────────────────────────────────────────────────
FROM eclipse-temurin:25-jdk-alpine AS runtime

RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

# k3s: these labels are read by metadata tools and the GitHub Actions workflow
LABEL org.opencontainers.image.title="Multi-Tenant JWT Security Gateway"
LABEL org.opencontainers.image.description="Spring Boot 3.4 / Java 25 multi-tenant API gateway with PostgreSQL RLS"
LABEL org.opencontainers.image.source="https://github.com/YOUR_USERNAME/jwt-security-gateway"
LABEL org.opencontainers.image.vendor="Internal API"

WORKDIR /application

# Layered jar extraction for optimal Docker caching:
# dependencies (rarely change) are in earlier layers than application code
COPY --from=builder /workspace/target/extracted/dependencies/ ./
COPY --from=builder /workspace/target/extracted/spring-boot-loader/ ./
COPY --from=builder /workspace/target/extracted/snapshot-dependencies/ ./
COPY --from=builder /workspace/target/extracted/application/ ./

# ── JVM tuning for containerised environments ────────────────────────────────
# -XX:+UseContainerSupport — reads CPU/memory from cgroup (k3s resource limits)
# -XX:MaxRAMPercentage=75  — use 75% of container memory for heap
# -XX:+CompactObjectHeaders — Java 25 JEP 519: smaller object headers
# -Djava.security.egd    — faster secure random in Alpine containers
ENV JAVA_OPTS="-XX:+UseContainerSupport \
    -XX:MaxRAMPercentage=75.0 \
    -XX:+CompactObjectHeaders \
    -Djava.security.egd=file:/dev/./urandom \
    -Dspring.profiles.active=prod"

# Expose app port (overridable via SERVER_PORT env var)
EXPOSE 8080

# Spring Boot layered jar entrypoint
ENTRYPOINT ["java", "-cp", ".:BOOT-INF/lib/*", \
    "org.springframework.boot.loader.launch.JarLauncher"]
