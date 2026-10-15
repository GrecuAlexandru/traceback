"""Manage only the persistent, synthetic traceback-demo project. Never removes volumes."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
DIRECTORY = Path.home() / ".local/share/traceback/demo"
sys.path.insert(0, str(ROOT / "scripts/deployment"))
from prepare_local import prepare  # noqa: E402


def initialize(directory):
    if directory.exists():
        if not all((directory / name).is_file() for name in ("realm.json", "login.json")):
            raise RuntimeError("Incomplete demo identity directory; restore it rather than replacing credentials")
        realm = json.loads((directory / "realm.json").read_text())
        login = json.loads((directory / "login.json").read_text())
        if (realm.get("realm") != "traceback-demo" or login.get("username") != "demo-engineer"
                or realm.get("clients", [{}])[0].get("redirectUris") != ["http://127.0.0.1:18766/callback"]):
            raise RuntimeError("Unexpected existing identity configuration; refusing to replace it")
        return
    prepare(directory)
    realm = json.loads((directory / "realm.json").read_text())
    realm["realm"] = "traceback-demo"
    client = realm["clients"][0]
    client["clientId"] = "traceback-demo-web"
    client["redirectUris"] = ["http://127.0.0.1:18766/callback"]
    user = realm["users"][0]
    user.update(username="demo-engineer", firstName="Demo", lastName="Engineer", email="demo@example.test")
    realm_path = directory / "realm.json"
    realm_path.chmod(0o600)
    realm_path.write_text(json.dumps(realm))
    realm_path.chmod(0o444)
    (directory / "login.json").write_text(json.dumps({"username": user["username"],
                                                     "password": user["credentials"][0]["value"]}))


def environment():
    env = os.environ.copy()
    env["DEMO_DIRECTORY"] = str(DIRECTORY)
    # Docker Desktop's daemon-side proxy; Linux can override with its normal socket.
    if sys.platform == "darwin":
        env.setdefault("DEMO_DOCKER_SOCKET", "/run/host-services/docker.proxy.sock")
    return env


def command(args, *, data=None):
    result = subprocess.run(args, cwd=ROOT, env=environment(), input=data, text=True,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=300)
    if result.returncode:
        # Never echo subprocess output: Keycloak import/HTTP errors may carry credentials.
        raise RuntimeError("Demo command failed: " + args[0] + " (exit " + str(result.returncode) + ")")
    return result.stdout


def active_mcp():
    marker = DIRECTORY / 'mode'
    return marker.is_file() and marker.read_text().strip() == 'mcp'


def compose(*args, mcp=None):
    files = ['-f', 'compose.demo.yaml']
    if active_mcp() if mcp is None else mcp:
        files.extend(['-f', 'compose.demo.mcp.yaml'])
    return command(["docker", "compose", "--env-file", "/dev/null", "-p", "traceback-demo",
                    *files, *args])


def check_chat_memory():
    """Reject the observed duplicate-stack hazard before changing running services."""
    info = json.loads(command(["docker", "info", "--format", "{{json .}}"]))
    if info["MemTotal"] >= 12 * 1024**3:
        return
    services = command(["docker", "ps", "--filter", "label=com.docker.compose.project=traceback-m17",
                        "--format", '{{.Label "com.docker.compose.service"}}']).splitlines()
    competing = {"api", "worker", "frontend", "keycloak", "ollama", "chat-ollama"}.intersection(services)
    if competing:
        raise RuntimeError(
            "Demo startup stopped before changing services: the small Docker VM is also running M17 "
            "application/identity/model services. Stop unused M17 services or allocate more Docker memory "
            "before starting the demo. Keep M17 postgres/localstack running to preserve their ephemeral data. "
            "See docs/LOCAL_DEMO.md (chat memory recovery).")


def up(recreate=False):
    check_chat_memory()
    initialize(DIRECTORY)
    compose("config", "--quiet", mcp=False)
    # Stop before migrating on subsequent runs; a single API owns upload recovery.
    compose("stop", "worker", "api", mcp=False)
    recreate_args = ["--force-recreate"] if recreate else []
    compose("up", "-d", *recreate_args, "--wait", "--wait-timeout", "180", "postgres", "keycloak", "localstack",
            "frontend", "ollama", "chat-ollama", mcp=False)
    command(["docker", "run", "--rm", "--network", "traceback-demo_default", "--read-only",
             "--tmpfs", "/tmp:size=64m,uid=10001,gid=10001", "--memory", "512m", "--cpus", "1",
             "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
             "-e", "TRACEBACK_ENVIRONMENT=local-production-like",
             "-e", "DATABASE_URL=jdbc:postgresql://postgres:5432/traceback_demo",
             "-e", "MIGRATION_USER=traceback_demo", "-e", "MIGRATION_PASSWORD=synthetic-local-only",
             "traceback-api:m17", "migrate"])
    sql = """DO $$ BEGIN
      IF NOT EXISTS(SELECT FROM pg_roles WHERE rolname='traceback_demo_api') THEN
        CREATE ROLE traceback_demo_api LOGIN;
      END IF;
      IF NOT EXISTS(SELECT FROM pg_roles WHERE rolname='traceback_demo_worker') THEN
        CREATE ROLE traceback_demo_worker LOGIN;
      END IF;
    END $$;
    """ + (ROOT / "scripts/deployment/grants.sql").read_text()
    command(["docker", "exec", "-i", "traceback-demo-postgres-1", "psql", "-U", "traceback_demo",
             "-d", "traceback_demo", "-v", "ON_ERROR_STOP=1", "-v", "app_role=traceback_demo_api",
             "-v", "worker_role=traceback_demo_worker"], data=sql)
    compose("up", "-d", "--no-deps", *recreate_args, "--wait", "--wait-timeout", "180", "api", "worker", mcp=False)
    (DIRECTORY / 'mode').write_text('ai\n')
    print("Persistent demo ready: http://127.0.0.1:18766")


def up_mcp():
    from mcp_setup import bootstrap, configure
    initialize(DIRECTORY)
    compose('config', '--quiet', mcp=True)
    # LOCAL source bytes/indexes stay on their durable volumes. Pending jobs resume in AI mode.
    compose('stop', 'worker', 'api', 'frontend', 'keycloak', 'loopback', 'ollama', 'chat-ollama', 'localstack', mcp=False)
    configured = DIRECTORY / 'mcp-configured.json'
    credentials = None if configured.is_file() else bootstrap()
    print('Starting low-memory MCP dependencies...', flush=True)
    compose('up', '-d', '--wait', '--wait-timeout', '180', 'postgres', 'keycloak', 'frontend', mcp=True)
    if credentials is not None:
        configure(credentials, DIRECTORY)
    compose('up', '-d', '--no-deps', '--wait', '--wait-timeout', '180', 'api', mcp=True)
    (DIRECTORY / 'mode').write_text('mcp\n')
    print('Read-only MCP ready: http://127.0.0.1:18081/mcp (AI and processing paused)')


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["up", "mcp", "ai", "stop", "status"])
    parser.add_argument("--recreate", action="store_true", help="Recreate containers; retain all named volumes")
    args = parser.parse_args()
    try:
        if args.action == "up":
            up_mcp() if active_mcp() else up(args.recreate)
        elif args.action == 'mcp':
            up_mcp()
        elif args.action == 'ai':
            up(args.recreate)
        elif args.action == "stop":
            compose("stop")
            print("Demo stopped; database, identity and files retained")
        else:
            print(compose("ps"))
    except RuntimeError as error:
        # RuntimeError messages above contain only fixed diagnostics/program names.
        raise SystemExit(str(error)) from None
    except (OSError, subprocess.SubprocessError):
        raise SystemExit("Demo operation failed. Inspect service health locally; credentials were not printed.") from None
