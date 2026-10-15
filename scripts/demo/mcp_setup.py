"""Add only two dedicated MCP clients to the persisted local demo realm."""
import copy
import json
import os
from pathlib import Path
import secrets
import subprocess
import sys
import urllib.parse

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
from local_oidc import request, SafeError  # noqa: E402

ISSUER = 'http://127.0.0.1:18181/realms/traceback-demo'
RESOURCE = 'http://127.0.0.1:18081/mcp'
CALLBACK = 'http://127.0.0.1:8767/callback'
CLIENT_ID = 'traceback-demo-mcp'


def clients():
    fixture = json.loads((ROOT / 'dev/keycloak/m14-test-realm.json').read_text())
    result = copy.deepcopy(fixture['clients'][:2])
    result[0]['attributes']['resource_url'] = RESOURCE
    result[1]['clientId'] = CLIENT_ID
    result[1]['redirectUris'] = [CALLBACK]
    return result


def bootstrap():
    state = json.loads(subprocess.check_output(['docker', 'inspect', 'traceback-demo-keycloak-1']))[0]
    if state['State']['Running']:
        raise SafeError('Stop the demo Keycloak before its offline bootstrap command.')
    if state['Config']['Labels']['com.docker.compose.project'] != 'traceback-demo':
        raise SafeError('Unexpected identity container; refusing bootstrap.')
    credentials = {'username': 'demo-mcp-setup-' + secrets.token_hex(6), 'password': secrets.token_urlsafe(32)}
    env = {**os.environ, 'TRACEBACK_DEMO_BOOTSTRAP_PASSWORD': credentials['password']}
    result = subprocess.run(['docker', 'run', '--rm', '--network', 'none', '--memory', '768m', '--cpus', '1',
        '--mount', 'type=volume,src=traceback-demo_identity,dst=/opt/keycloak/data',
        '-e', 'TRACEBACK_DEMO_BOOTSTRAP_PASSWORD', 'quay.io/keycloak/keycloak:26.8.0',
        'bootstrap-admin', 'user', '--db=dev-file', '--username', credentials['username'],
        '--password:env', 'TRACEBACK_DEMO_BOOTSTRAP_PASSWORD', '--no-prompt'],
        env=env, capture_output=True, text=True, timeout=120)
    if result.returncode:
        raise SafeError('Offline demo client-setup bootstrap failed; no output/credentials displayed.')
    return credentials


def configure(credentials, directory):
    status, tokens = request('POST', 'http://127.0.0.1:18181/realms/master/protocol/openid-connect/token',
        form={'grant_type': 'password', 'client_id': 'admin-cli', **credentials})
    if status != 200 or not isinstance(tokens, dict) or not tokens.get('access_token'):
        raise SafeError('Temporary demo client-setup authentication failed.')
    token = tokens['access_token']
    tokens.clear()
    def admin(path, method='GET', payload=None, realm='traceback-demo'):
        status, body = request(method, 'http://127.0.0.1:18181/admin/realms/' + realm + path, token=token, payload=payload)
        if status not in (200, 201, 204):
            raise SafeError('Dedicated demo client setup failed (HTTP ' + str(status) + ').')
        return body
    for client in clients():
        found = admin('/clients?' + urllib.parse.urlencode({'clientId': client['clientId']}))
        exact = [c for c in found if c.get('clientId') == client['clientId']]
        if exact:
            if len(exact) != 1:
                raise SafeError('Ambiguous dedicated MCP client; refusing changes.')
            actual = exact[0]
            for key, expected in client.items():
                if key == 'attributes':
                    if any(actual.get(key, {}).get(k) != v for k, v in expected.items()):
                        raise SafeError('Existing dedicated MCP client attributes differ; refusing changes.')
                elif key == 'protocolMappers':
                    mappings = {m.get('name'): m for m in actual.get(key, [])}
                    for mapper in expected:
                        found = mappings.get(mapper['name'], {})
                        if found.get('protocolMapper') != mapper['protocolMapper'] or any(found.get('config', {}).get(k) != v for k, v in mapper['config'].items()):
                            raise SafeError('Existing dedicated MCP client mappings differ; refusing changes.')
                elif actual.get(key) != expected:
                    raise SafeError('Existing dedicated MCP client differs; refusing changes.')
        else:
            admin('/clients', 'POST', client)
    # Remove the temporary master administrator, never change the human demo user.
    users = admin('/users?' + urllib.parse.urlencode({'username': credentials['username'], 'exact': 'true'}), realm='master')
    if len(users) != 1:
        raise SafeError('Temporary setup identity lookup was ambiguous.')
    admin('/users/' + users[0]['id'], 'DELETE', realm='master')
    credentials.clear()
    # Keep the additive import descriptor consistent for a brand-new demo installation.
    realm_path = directory / 'realm.json'
    realm = json.loads(realm_path.read_text())
    known = {c['clientId'] for c in realm['clients']}
    realm['clients'].extend(c for c in clients() if c['clientId'] not in known)
    realm_path.chmod(0o600)
    realm_path.write_text(json.dumps(realm))
    realm_path.chmod(0o444)
    marker = directory / 'mcp-configured.json'
    with open(marker, 'x', opener=lambda p, flags: os.open(p, flags, 0o600)) as output:
        json.dump({'clientId': CLIENT_ID, 'resource': RESOURCE, 'callback': CALLBACK}, output)
    print('Dedicated MCP clients configured; temporary setup administrator removed.')
