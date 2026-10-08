# Container image for the PG Manager API.
# Two stages: the JDK + Gradle are only used to build; the final image contains just a JRE and the app.
# No configuration or secrets are baked in: everything comes from environment variables at runtime.

# ---------- build ----------
FROM eclipse-temurin:25-jdk AS build
WORKDIR /build

# Gradle wrapper and build files first, so the dependency download is cached until they change
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
RUN ./gradlew --no-daemon dependencies --configuration runtimeClasspath > /dev/null

COPY src ./src
# installDist (Gradle application plugin) creates build/install/pg-manager: bin/pg-manager + lib/*.jar
RUN ./gradlew --no-daemon installDist

# ---------- runtime ----------
FROM eclipse-temurin:25-jre

# curl only for the health check below
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Run as an unprivileged user, not root
RUN groupadd --system app && useradd --system --gid app --no-create-home app

WORKDIR /app
COPY --from=build /build/build/install/pg-manager ./
USER app

# Let the JVM size its heap from the container's memory limit
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
EXPOSE 8080

# Healthy when the API answers and PostgreSQL is reachable (/api/health answers 503 otherwise).
# Redis is deliberately not part of it: it is only a cache.
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=3 \
    CMD curl -fsS http://localhost:8080/api/health || exit 1

# The start script ends with "exec java ...", so Java gets stop signals directly
ENTRYPOINT ["./bin/pg-manager"]
