# syntax=docker/dockerfile:1
# Shipyard: one image containing the API, workers and the ENTERPRISE-UI frontend.

# ---------------------------------------------------------------- frontend
FROM node:22-alpine AS frontend
WORKDIR /src/frontend
COPY frontend/package.json frontend/package-lock.json ./
COPY frontend/vendor ./vendor
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

# ---------------------------------------------------------------- backend
FROM maven:3-eclipse-temurin-21 AS backend
WORKDIR /src/backend
COPY backend/pom.xml ./
RUN mvn -B -q dependency:go-offline
COPY backend/src ./src
COPY --from=frontend /src/frontend/dist ./src/main/resources/static
RUN mvn -B -q -DskipTests package

# ---------------------------------------------------------------- docker CLI (+ buildx for static exports)
FROM docker:27-cli AS dockercli

# ---------------------------------------------------------------- runtime
FROM eclipse-temurin:21-jre
RUN apt-get update \
 && apt-get install -y --no-install-recommends git ca-certificates tar curl \
 && rm -rf /var/lib/apt/lists/*
COPY --from=dockercli /usr/local/bin/docker /usr/local/bin/docker
COPY --from=dockercli /usr/local/libexec/docker/cli-plugins/docker-buildx /usr/local/libexec/docker/cli-plugins/docker-buildx
COPY --from=backend /src/backend/target/shipyard.jar /opt/shipyard/shipyard.jar
ENV SHIPYARD_DATA_DIR=/var/lib/shipyard \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
VOLUME /var/lib/shipyard
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s CMD curl -fsS http://localhost:8080/actuator/health >/dev/null || exit 1
ENTRYPOINT ["java", "-jar", "/opt/shipyard/shipyard.jar"]
