FROM eclipse-temurin:21-jre-alpine@sha256:51ab5e3302e7141ce665ca3ea85e8b5cd648eafbc3c0c90dd79d6537684e4555
COPY traceback-backend-0.0.1-SNAPSHOT-pdf-worker.jar /app/pdf-worker.jar
USER 65534:65534
ENTRYPOINT ["timeout", "-s", "KILL", "70", "java", "-Xmx160m", "-XX:MaxDirectMemorySize=16m", "-XX:ActiveProcessorCount=1", "-Djava.io.tmpdir=/tmp", "-jar", "/app/pdf-worker.jar"]
