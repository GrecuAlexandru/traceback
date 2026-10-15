"""Small native MCP client for the persisted demo; real PKCE, no saved bearer tokens."""
import argparse
import base64
import hashlib
import json
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
import secrets
import sys
import time
import urllib.parse
import webbrowser

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
from local_oidc import request, SafeError  # noqa: E402
from mcp_setup import ISSUER, RESOURCE, CALLBACK, CLIENT_ID  # noqa: E402


def callback_values(target, state):
    parsed = urllib.parse.urlsplit(target)
    values = urllib.parse.parse_qs(parsed.query, keep_blank_values=True)
    if (len(target) > 8192 or parsed.path != '/callback' or ((parsed.scheme or parsed.netloc) and not target.startswith(CALLBACK + '?')) or parsed.fragment
            or values.get('state') != [state] or values.get('iss') != [ISSUER]
            or len(values.get('code', [])) != 1 or not values.get('code', [''])[0] or 'error' in values):
        raise SafeError('OAuth callback rejected; no response details printed.')
    return values['code'][0]


def login(headless=False):
    verifier = secrets.token_urlsafe(64)
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b'=').decode()
    state, nonce = secrets.token_urlsafe(32), secrets.token_urlsafe(32)
    params = {'client_id': CLIENT_ID, 'redirect_uri': CALLBACK, 'response_type': 'code', 'scope': 'openid',
        'code_challenge': challenge, 'code_challenge_method': 'S256', 'state': state, 'nonce': nonce,
        'resource': RESOURCE, 'login_hint': 'demo-engineer'}
    url = ISSUER + '/protocol/openid-connect/auth?' + urllib.parse.urlencode(params)
    received = []
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args): pass
        def do_GET(self):
            try:
                if self.headers.get('Host') != '127.0.0.1:8767':
                    raise SafeError('Unexpected callback host.')
                received.append(callback_values(self.path, state))
                status, body = 200, b'Connected to Traceback. Return to the terminal; this tab can close.'
            except SafeError:
                status, body = 400, b'Callback rejected. Continue the original login.'
            self.send_response(status)
            self.send_header('Content-Type', 'text/plain; charset=utf-8')
            self.send_header('Cache-Control', 'no-store')
            self.send_header('Content-Security-Policy', "default-src 'none'")
            self.send_header('Content-Length', str(len(body)))
            self.end_headers(); self.wfile.write(body)
    import threading
    with HTTPServer(('127.0.0.1', 8767), Handler) as server:
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            if headless:
                import subprocess
                result = subprocess.run(['node', str(ROOT / 'scripts/demo/mcp_login.mjs')],
                    input=json.dumps({'url': url, 'callback': CALLBACK}), text=True, capture_output=True, timeout=60)
                if result.returncode:
                    raise SafeError('Synthetic browser PKCE login failed; browser details suppressed.')
            else:
                print('Opening local Keycloak login. Sign in as demo-engineer.', flush=True)
                if not webbrowser.open(url):
                    raise SafeError('Browser could not open the local login.')
                deadline = time.monotonic() + 300
                while not received and time.monotonic() < deadline:
                    time.sleep(0.1)
        finally:
            server.shutdown()
            thread.join()
    if not received:
        raise SafeError('Login timed out. No application data changed.')
    code = received[0]
    status, tokens = request('POST', ISSUER + '/protocol/openid-connect/token', form={
        'grant_type': 'authorization_code', 'client_id': CLIENT_ID, 'redirect_uri': CALLBACK,
        'code': code, 'code_verifier': verifier, 'resource': RESOURCE})
    if status != 200 or not isinstance(tokens, dict) or not tokens.get('access_token'):
        raise SafeError('PKCE token exchange failed; no token response printed.')
    access = tokens['access_token']
    def claims(token):
        part = token.split('.')[1]
        return json.loads(base64.urlsafe_b64decode(part + '=' * (-len(part) % 4)))
    identity, authorization = claims(tokens['id_token']), claims(access)
    audience = authorization.get('aud')
    if isinstance(audience, str): audience = [audience]
    if (identity.get('nonce') != nonce or identity.get('iss') != ISSUER or audience != [RESOURCE]
            or authorization.get('traceback_principal_type') != 'human' or authorization.get('exp', 0) <= time.time()):
        raise SafeError('OAuth token claims do not match the dedicated MCP flow.')
    tokens.clear()
    return access  # Spring's real decoder validates signature/issuer/audience on every request.


class Client:
    def __init__(self, token):
        self.token, self.sequence = token, 0
    def rpc(self, method, params, notification=False):
        import urllib.request
        from local_oidc import NoRedirect
        self.sequence += 1
        payload = {'jsonrpc': '2.0', 'id': self.sequence, 'method': method, 'params': params}
        if notification: payload.pop('id')
        headers = {'Content-Type': 'application/json', 'Accept': 'application/json, text/event-stream',
            'Authorization': 'Bearer ' + self.token, 'MCP-Protocol-Version': '2025-11-25'}
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
        try:
            with opener.open(urllib.request.Request(RESOURCE, data=json.dumps(payload).encode(), headers=headers), timeout=15) as response:
                raw = response.read(131073)
                if notification:
                    if response.status != 202: raise SafeError('MCP notification failed.')
                    return None
        except Exception:
            raise SafeError('MCP HTTP request failed; no HTTP bodies or tokens printed.') from None
        if len(raw) > 131072:
            raise SafeError('MCP response exceeded the client limit.')
        result = json.loads(raw)
        if result.get('id') != self.sequence or 'error' in result:
            raise SafeError('MCP protocol request failed.')
        return result['result']
    def initialize(self):
        result = self.rpc('initialize', {'protocolVersion': '2025-11-25', 'capabilities': {},
            'clientInfo': {'name': 'traceback-demo-cli', 'version': '1.0'}})
        self.rpc('notifications/initialized', {}, notification=True)
        return result
    def call(self, tool, arguments):
        result = self.rpc('tools/call', {'name': tool, 'arguments': arguments})
        if result.get('isError'):
            raise SafeError('MCP tool returned a bounded application error.')
        return result['structuredContent']


def demo(client):
    initialized = client.initialize()
    tools = client.rpc('tools/list', {})['tools']
    print('Connected; protocol ' + initialized['protocolVersion'])
    print('Available read-only tools: ' + ', '.join(t['name'] for t in tools))
    organizations = client.call('traceback_list_organizations', {})['organizations']
    for org in organizations:
        print('Organization: ' + org['untrustedContent']['name'] + ' (' + org['id'] + ')')
    org = next((o for o in organizations if o['untrustedContent']['name'] == 'Northstar Engineering · Demo'), None)
    if org is None:
        raise SafeError('The synthetic Engineering organization is missing.')
    args = {'organizationId': org['id']}
    incidents = client.call('traceback_list_incidents', {**args, 'page': 0, 'size': 5})
    for item in incidents['items']:
        print('Incident: ' + item['status'] + ' — ' + item['untrustedContent']['title'])
    if incidents['items']:
        detail = client.call('traceback_get_incident', {**args, 'incidentId': incidents['items'][0]['id']})
        print('Read incident detail: ' + detail['untrustedContent']['title'])
    matches = client.call('traceback_search_knowledge', {**args, 'query': 'database', 'mode': 'keyword', 'limit': 3})
    print('Keyword search returned ' + str(len(matches['results'])) + ' passages without an AI model.')
    if matches['results']:
        source = matches['results'][0]['source']
        client.call('traceback_get_document', {**args, 'documentId': source['documentId']})
        client.call('traceback_get_source', {**args, **{k: source[k] for k in ('documentId', 'revisionId', 'generationId', 'chunkId', 'configuration')}})
        print('Read document metadata and resolved the exact source passage.')
    print('PASS: MCP tools completed; no incidents, documents or conversations changed.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['demo', 'tools', 'organizations', 'incidents', 'incident', 'search'])
    parser.add_argument('--organization')
    parser.add_argument('--incident')
    parser.add_argument('--query', default='database')
    parser.add_argument('--headless', action='store_true', help='Synthetic account verification through existing Playwright')
    args = parser.parse_args()
    try:
        client = Client(login(args.headless))
        if args.action == 'demo':
            demo(client)
        else:
            client.initialize()
            if args.action == 'tools': result = client.rpc('tools/list', {})
            elif args.action == 'organizations': result = client.call('traceback_list_organizations', {})
            else:
                if not args.organization:
                    parser.error('--organization UUID is required')
                params = {'organizationId': args.organization}
                if args.action == 'incidents': result = client.call('traceback_list_incidents', {**params, 'page': 0, 'size': 5})
                elif args.action == 'incident':
                    if not args.incident: parser.error('--incident UUID is required')
                    result = client.call('traceback_get_incident', {**params, 'incidentId': args.incident})
                else: result = client.call('traceback_search_knowledge', {**params, 'query': args.query, 'mode': 'keyword', 'limit': 3})
            print(json.dumps(result, ensure_ascii=False, indent=2))
    except (SafeError, OSError, ValueError, KeyError):
        raise SystemExit('MCP demo failed safely; confirm MCP mode is running and complete local login. No tokens printed.') from None
