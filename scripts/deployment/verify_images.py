"""Check fail-closed images and singleton ownership against the owned local M17 stack."""
import subprocess
import uuid
import urllib.request


def run(arguments):
    return subprocess.run(arguments, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=40)


def verify():
    for image in ["traceback-api:m17", "traceback-frontend:m17"]:
        name = "traceback-m17-guard-" + uuid.uuid4().hex
        try:
            result = run(["docker", "run", "--rm", "--name", name, "--network", "none", "--memory", "512m", "--cpus", "1", image])
        finally:
            subprocess.run(["docker", "rm", "-f", name], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        assert result.returncode != 0, "Unconfigured production image must refuse startup"
    label = run(["docker", "inspect", "--format", '{{ index .Config.Labels "com.docker.compose.project" }}', "traceback-m17-loopback-1"])
    assert label.stdout.strip() == b"traceback-m17"
    assert urllib.request.urlopen("http://127.0.0.1:18080/actuator/health/readiness", timeout=5).status == 200
    name = "traceback-m17-second-writer-" + uuid.uuid4().hex
    try:
        result = run(["docker", "run", "--rm", "--name", name, "--read-only", "--tmpfs", "/tmp:rw,size=64m,uid=10001,gid=10001", "--cap-drop", "ALL",
                  "--security-opt", "no-new-privileges", "--memory", "768m", "--cpus", "1", "--pids-limit", "128",
                  "--network", "container:traceback-m17-loopback-1",
                  "-e", "SPRING_PROFILES_ACTIVE=default", "-e", "TRACEBACK_ENVIRONMENT=local-production-like",
                  "-e", "SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/traceback_m17",
                  "-e", "SPRING_DATASOURCE_USERNAME=traceback_m17", "-e", "SPRING_DATASOURCE_PASSWORD=",
                  "-e", "LOCAL_AWS_ENABLED=true", "-e", "LOCAL_AWS_ENDPOINT=http://localstack:4566",
                  "-e", "DOCUMENT_STORAGE_PROVIDER=S3", "-e", "TRACEBACK_DOCUMENTS_LEGACY_ENABLED=false", "traceback-api:m17"])
    finally:
        subprocess.run(["docker", "rm", "-f", name], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    assert result.returncode != 0 and b"Document runtime is already active for this database" in result.stdout, "Second S3-only writer must fail on DB ownership"
    print("PASS: unconfigured production API/frontend refuse startup; second S3-only API writer rejected")


if __name__ == "__main__":
    try:
        verify()
    except Exception:
        raise SystemExit("Image verification failed (details intentionally omit application logs)") from None
