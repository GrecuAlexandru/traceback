"""Isolated management tests; never operate Docker or the user's demo directory."""
import contextlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import os
import subprocess

import manage


class DemoManagementTests(unittest.TestCase):
    def test_identity_is_private_and_rerun_preserves_it(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp) / "demo"
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                manage.initialize(directory)
            login_bytes = (directory / "login.json").read_bytes()
            login = json.loads(login_bytes)
            self.assertNotIn(login["password"], output.getvalue())
            self.assertEqual(directory.stat().st_mode & 0o777, 0o700)
            self.assertEqual((directory / "login.json").stat().st_mode & 0o777, 0o600)
            realm = json.loads((directory / "realm.json").read_text())
            self.assertEqual(realm["realm"], "traceback-demo")
            client = realm["clients"][0]
            self.assertEqual(client["redirectUris"], ["http://127.0.0.1:18766/callback"])
            self.assertFalse(client["serviceAccountsEnabled"])
            self.assertFalse(client["directAccessGrantsEnabled"])
            self.assertEqual(client["attributes"]["pkce.code.challenge.method"], "S256")
            manage.initialize(directory)
            self.assertEqual((directory / "login.json").read_bytes(), login_bytes)

    def test_partial_identity_is_not_reset(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp) / "demo"
            directory.mkdir()
            (directory / "login.json").write_text("preserve me")
            with self.assertRaises(RuntimeError):
                manage.initialize(directory)
            self.assertEqual((directory / "login.json").read_text(), "preserve me")

    def test_foreign_identity_fixture_is_not_repurposed(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp) / "demo"
            with contextlib.redirect_stdout(io.StringIO()):
                manage.initialize(directory)
            realm_path = directory / "realm.json"
            realm = json.loads(realm_path.read_text())
            realm["realm"] = "traceback-m17"
            realm_path.chmod(0o600)
            realm_path.write_text(json.dumps(realm))
            original = realm_path.read_bytes()
            with self.assertRaises(RuntimeError):
                manage.initialize(directory)
            self.assertEqual(realm_path.read_bytes(), original)

    def test_up_migrates_then_grants_before_runtime_without_removing_volumes(self):
        calls = []
        with tempfile.TemporaryDirectory() as temp, patch.object(manage, "DIRECTORY", Path(temp)), patch.object(manage, "check_chat_memory"), patch.object(manage, "initialize"), patch.object(manage, "command", side_effect=lambda args, **kw: calls.append((args, kw)) or ""):
            manage.up(recreate=True)
        commands = [args for args, _ in calls]
        self.assertTrue(all("traceback-m17" not in arg for args in commands for arg in args))
        self.assertTrue(all("down" not in args and "rm" not in args and "--volumes" not in args for args in commands))
        migration = next(i for i, args in enumerate(commands) if args[-1] == "migrate")
        grants = next(i for i, (_, kw) in enumerate(calls) if "data" in kw)
        runtime = next(i for i, args in enumerate(commands) if args[-2:] == ["api", "worker"] and "up" in args)
        self.assertLess(migration, grants)
        self.assertLess(grants, runtime)
        self.assertIn("worker_role=traceback_demo_worker", commands[grants])
        self.assertIn("GRANT UPDATE (modified_at) ON documents", calls[grants][1]["data"])

    def test_compose_persists_sources_identity_and_database_on_private_ports(self):
        result = subprocess.run(["docker", "compose", "--env-file", "/dev/null", "-p", "traceback-demo",
                                 "-f", "compose.demo.yaml", "config", "--format", "json"],
                                cwd=manage.ROOT, env={**os.environ, "DEMO_DIRECTORY": "/private/demo"},
                                capture_output=True, text=True, check=True)
        config = json.loads(result.stdout)
        services = config["services"]
        for service, target in [("postgres", "/var/lib/postgresql/data"), ("keycloak", "/opt/keycloak/data"),
                                ("api", "/var/lib/traceback/documents")]:
            self.assertTrue(any(v["type"] == "volume" and v["target"] == target for v in services[service]["volumes"]))
            self.assertFalse(any(target in v for v in services[service].get("tmpfs", [])))
        for service in services.values():
            self.assertEqual(service["restart"], "unless-stopped")
            for port in service.get("ports", []):
                self.assertEqual(port["host_ip"], "127.0.0.1")
        self.assertNotIn("ports", services["postgres"])
        self.assertNotIn("ports", services["localstack"])
        self.assertEqual(services["api"]["environment"]["DOCUMENT_STORAGE_PROVIDER"], "LOCAL")
        self.assertEqual(services["api"]["environment"]["SPRING_FLYWAY_ENABLED"], "false")
        self.assertEqual(services["worker"]["environment"]["SPRING_DATASOURCE_USERNAME"], "traceback_demo_worker")
        source_mount = next(v for v in services["worker"]["volumes"] if v["type"] == "volume")
        self.assertTrue(source_mount["read_only"])

    def test_small_vm_rejects_competing_stack_before_any_mutation(self):
        with patch.object(manage, "command", side_effect=[json.dumps({"MemTotal": 8 * 1024**3}), "api\nkeycloak\npostgres\n"]), \
                patch.object(manage, "initialize") as initialize, patch.object(manage, "compose") as compose:
            with self.assertRaisesRegex(RuntimeError, "before changing services"):
                manage.up()
            initialize.assert_not_called()
            compose.assert_not_called()

    def test_small_vm_allows_preserved_m17_database_and_emulator(self):
        with patch.object(manage, "command", side_effect=[json.dumps({"MemTotal": 8 * 1024**3}), "postgres\nlocalstack\nloopback\n"]):
            manage.check_chat_memory()

    def test_larger_vm_does_not_reject_coexistence(self):
        with patch.object(manage, "command", return_value=json.dumps({"MemTotal": 12 * 1024**3})) as command:
            manage.check_chat_memory()
            command.assert_called_once()

    def test_mcp_overlay_keeps_loopback_auth_and_pauses_ai(self):
        result = subprocess.run(['docker', 'compose', '--env-file', '/dev/null', '-p', 'traceback-demo',
                                 '-f', 'compose.demo.yaml', '-f', 'compose.demo.mcp.yaml', 'config', '--format', 'json'],
                                cwd=manage.ROOT, env={**os.environ, 'DEMO_DIRECTORY': '/private/demo'},
                                capture_output=True, text=True, check=True)
        services = json.loads(result.stdout)['services']
        env = services['api']['environment']
        self.assertEqual(env['SERVER_ADDRESS'], '127.0.0.1')
        self.assertEqual(env['SERVER_PORT'], '18081')
        self.assertEqual(env['MCP_RESOURCE'], 'http://127.0.0.1:18081/mcp')
        self.assertEqual(env['MCP_AUDIENCE'], env['MCP_RESOURCE'])
        self.assertEqual(env['MCP_ENABLED'], 'true')
        for flag in ['CHAT_ENABLED', 'INDEXING_ENABLED', 'LOCAL_AWS_ENABLED']:
            self.assertEqual(env[flag], 'false')
        self.assertNotIn('localstack', services['api']['depends_on'])
        for name in ['worker', 'localstack', 'ollama', 'chat-ollama']:
            self.assertNotIn(name, services)
        self.assertIn('--features=resource-indicators', services['keycloak']['command'])
        for port in services['loopback']['ports']:
            self.assertEqual(port['host_ip'], '127.0.0.1')

    def test_mcp_switch_stops_models_but_never_data_volumes_or_database(self):
        calls = []
        with tempfile.TemporaryDirectory() as temp, patch.object(manage, 'DIRECTORY', Path(temp)), \
                patch.object(manage, 'initialize'), patch.object(manage, 'compose', side_effect=lambda *args, **kw: calls.append(args)), \
                patch('mcp_setup.bootstrap', return_value={}) as bootstrap, patch('mcp_setup.configure') as configure:
            manage.up_mcp()
            stopped = next(args for args in calls if args[0] == 'stop')
            self.assertNotIn('postgres', stopped)
            self.assertIn('chat-ollama', stopped)
            self.assertIn('worker', stopped)
            self.assertFalse(any('down' in args or '--volumes' in args for args in calls))
            bootstrap.assert_called_once()
            configure.assert_called_once()
            self.assertEqual((Path(temp) / 'mode').read_text().strip(), 'mcp')


if __name__ == "__main__":
    unittest.main()
