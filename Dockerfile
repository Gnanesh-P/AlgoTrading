# ─────────────────────────────────────────────────────────────────────────────
# Stage 1 — Build
#   Uses the official Maven + JDK 17 image (no mvnw needed).
#   Dependencies are resolved in a separate layer so Docker cache is reused
#   on every rebuild unless pom.xml changes.
# ─────────────────────────────────────────────────────────────────────────────
FROM maven:3.9.6-eclipse-temurin-17-alpine AS builder

WORKDIR /app

# 1. Copy only the POM first so the dependency-download layer is cached
#    independently of source changes.
COPY pom.xml .
RUN mvn dependency:go-offline -B -q

# 2. Copy source and build the fat JAR (skip tests — run them in CI, not image build)
COPY src ./src
RUN mvn package -DskipTests -B -q

# ─────────────────────────────────────────────────────────────────────────────
# Stage 2 — Runtime
#   Minimal JRE-only image; the JDK is NOT included in the final image.
#   Runs as a non-root user for security.
# ─────────────────────────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jre-alpine AS runtime

WORKDIR /app

# Non-root user (good practice, required by some registries)
RUN addgroup -S algo && adduser -S algo -G algo
USER algo

# Copy the fat JAR produced by Stage 1
COPY --from=builder /app/target/algo-trading-backend-*.jar app.jar

# Render injects PORT at runtime; Spring Boot reads server.port=${PORT:8080}
EXPOSE 8080

# JVM flags tuned for a container environment (Render free tier = 512 MB RAM):
#   -XX:+UseContainerSupport          — honour cgroup memory limits (default on JDK 11+)
#   -XX:MaxRAMPercentage=75.0         — use up to 75 % of container RAM for the JVM heap
#   -XX:+UseG1GC                      — G1 GC suits mixed-size heaps well
#   -Djava.security.egd=...urandom    — faster SecureRandom init on Linux (faster startup)
#   -Dspring.profiles.active=default  — explicit profile (override via env if needed)
ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-XX:+UseG1GC", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]
