# syntax=docker/dockerfile:1.7
FROM eclipse-temurin:21-jdk-alpine@sha256:0bfc69a4758a86710e5c474032d28400a8bd00874766f9e8b1642ac2fd293159 AS build
WORKDIR /build/backend
COPY backend/.mvn .mvn
COPY backend/mvnw backend/pom.xml ./
RUN --mount=type=cache,target=/root/.m2 ./mvnw --batch-mode dependency:go-offline
COPY backend/src src
# Tests and the sandbox-image build run in verification, not inside a Docker build.
RUN --mount=type=cache,target=/root/.m2 ./mvnw --batch-mode -DskipTests -Dparser.image.skip=true package

FROM docker:29-cli@sha256:1a4c7cb63513f349bdad01fcc6e0f3f2f67d37b9da86f14dc0d4a0942eecda00 AS docker-cli
FROM eclipse-temurin:21-jre-alpine@sha256:51ab5e3302e7141ce665ca3ea85e8b5cd648eafbc3c0c90dd79d6537684e4555 AS runtime
WORKDIR /app
RUN mkdir -p /var/lib/traceback/documents && chown -R 10001:10001 /var/lib/traceback
# Only trusted API/worker orchestration gets the CLI. The parser never gets a socket.
COPY --from=docker-cli /usr/local/bin/docker /usr/local/bin/docker
COPY --from=build /build/backend/target/traceback-backend-0.0.1-SNAPSHOT.jar /app/app.jar
USER 10001:10001
ENV SERVER_ADDRESS=0.0.0.0 SERVER_PORT=8080 TRACEBACK_ENVIRONMENT=production SPRING_PROFILES_ACTIVE=production JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=65 -XX:+ExitOnOutOfMemoryError -Djava.io.tmpdir=/tmp"
EXPOSE 8080
STOPSIGNAL SIGTERM
HEALTHCHECK --interval=30s --timeout=6s --start-period=60s --retries=3 CMD ["java", "-jar", "/app/app.jar", "health"]
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

FROM runtime AS api
ENV TRACEBACK_RUNTIME=api

FROM runtime AS worker
ENV SPRING_PROFILES_ACTIVE=production,worker TRACEBACK_RUNTIME=worker

FROM eclipse-temurin:21-jre-alpine@sha256:51ab5e3302e7141ce665ca3ea85e8b5cd648eafbc3c0c90dd79d6537684e4555 AS pdf
COPY --from=build /build/backend/target/traceback-backend-0.0.1-SNAPSHOT-pdf-worker.jar /app/pdf-worker.jar
USER 65534:65534
STOPSIGNAL SIGTERM
ENTRYPOINT ["timeout", "-s", "KILL", "70", "java", "-Xmx160m", "-XX:MaxDirectMemorySize=16m", "-XX:ActiveProcessorCount=1", "-Djava.io.tmpdir=/tmp", "-jar", "/app/pdf-worker.jar"]
