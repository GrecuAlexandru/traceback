"""Exercise the image's one-shot migration command on a NEW disposable local database."""
import subprocess
import uuid


def call(args):
    return subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True, timeout=90).stdout


def exercise():
    label = call(["docker", "inspect", "--format", '{{ index .Config.Labels "com.docker.compose.project" }}', "traceback-m17-postgres-1"])
    assert label.strip() == b"traceback-m17"
    database = "m17_migrate_" + uuid.uuid4().hex
    call(["docker", "exec", "traceback-m17-postgres-1", "createdb", "-U", "traceback_m17", database])
    command = ["docker", "run", "--rm", "--network", "traceback-m17_default", "--read-only", "--tmpfs", "/tmp:size=64m,uid=10001,gid=10001",
               "--memory", "512m", "--cpus", "1", "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
               "-e", "TRACEBACK_ENVIRONMENT=local-production-like",
               "-e", "DATABASE_URL=jdbc:postgresql://postgres:5432/" + database,
               "-e", "MIGRATION_USER=traceback_m17", "-e", "MIGRATION_PASSWORD=synthetic-local-only", "traceback-api:m17", "migrate"]
    call(command)
    call(command)  # second run must be a no-op with validated checksums
    count = call(["docker", "exec", "traceback-m17-postgres-1", "psql", "-U", "traceback_m17", "-d", database,
                  "-Atc", "SELECT count(*) FROM flyway_schema_history WHERE success"])
    assert count.strip() == b"8"
    print("PASS: one-shot image migration applies V1-V8 to a new disposable DB; repeat validates/no-ops")


if __name__ == "__main__":
    try:
        exercise()
    except Exception:
        raise SystemExit("Disposable one-shot migration verification failed; original database retained") from None
