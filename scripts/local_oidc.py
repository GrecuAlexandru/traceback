"""Local-only HTTP/admin helpers. Secrets remain in memory; error bodies are never logged."""
import json
import subprocess
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ISSUER = "http://localhost:8180/realms/traceback"
CALLBACK = "http://127.0.0.1:8765/callback"


class SafeError(Exception):
    """Messages contain no HTTP bodies, request URLs/queries, or credentials."""


def require_loopback(url):
    parsed = urllib.parse.urlsplit(url)
    if (parsed.scheme != "http" or parsed.hostname not in ("localhost", "127.0.0.1", "::1")
            or parsed.username is not None or parsed.password is not None or parsed.fragment):
        raise SafeError("Only credential-free HTTP loopback URLs are allowed.")
    return url


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise SafeError("HTTP redirects are refused by the local HTTP client.")


def request(method, url, *, token=None, form=None, payload=None):
    require_loopback(url)
    headers = {"Accept": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    data = None
    if form is not None:
        data = urllib.parse.urlencode(form).encode("ascii")
        headers["Content-Type"] = "application/x-www-form-urlencoded"
    if payload is not None:
        data = json.dumps(payload).encode("utf-8")
        headers["Content-Type"] = "application/json"
    # Ignore environment proxies: credentials and tokens never leave local services.
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    try:
        with opener.open(urllib.request.Request(url, data=data, headers=headers, method=method), timeout=10) as response:
            status, raw = response.status, response.read(1_000_001)
    except urllib.error.HTTPError as error:
        # Bodies can contain credentials/user information; do not log or propagate them.
        status, raw = error.code, b""
        error.close()
    except (urllib.error.URLError, TimeoutError, OSError):
        raise SafeError("A local HTTP request failed or timed out.") from None
    if len(raw) > 1_000_000:
        raise SafeError("Local HTTP response exceeded the size limit.")
    if not raw:
        return status, None
    try:
        return status, json.loads(raw)
    except (ValueError, UnicodeError):
        raise SafeError("A local service returned a non-JSON response.") from None


class LocalAdmin:
    def __init__(self):
        try:
            container = subprocess.check_output(["docker", "compose", "ps", "-q", "keycloak"],
                    cwd=ROOT, text=True, stderr=subprocess.DEVNULL).strip()
            if not container or "\n" in container:
                raise SafeError("Exactly one local Compose Keycloak container is required.")
            inspected = json.loads(subprocess.check_output(["docker", "inspect", container],
                    cwd=ROOT, text=True, stderr=subprocess.DEVNULL))
            env = dict(item.split("=", 1) for item in inspected[0]["Config"]["Env"] if "=" in item)
            credentials = {"grant_type": "password", "client_id": "admin-cli",
                    "username": env["KC_BOOTSTRAP_ADMIN_USERNAME"], "password": env["KC_BOOTSTRAP_ADMIN_PASSWORD"]}
            status, tokens = request("POST", "http://localhost:8180/realms/master/protocol/openid-connect/token", form=credentials)
            del credentials, env, inspected
            if status != 200 or not isinstance(tokens, dict) or not isinstance(tokens.get("access_token"), str):
                raise SafeError("Local admin authentication failed; no credentials were displayed.")
            self.token = tokens["access_token"]
            tokens.clear()
        except (subprocess.SubprocessError, ValueError, KeyError, IndexError):
            raise SafeError("Local bootstrap admin credentials could not be read safely from Compose.") from None

    def api(self, path, *, method="GET", payload=None):
        if not path.startswith("/") or ".." in path or "#" in path:
            raise SafeError("Invalid local admin resource path.")
        status, body = request(method, "http://localhost:8180/admin/realms/traceback" + path,
                               token=self.token, payload=payload)
        if status not in (200, 201, 204):
            raise SafeError("Local Keycloak admin operation failed (HTTP " + str(status) + ").")
        return body

    def client(self, client_id):
        clients = self.api("/clients?" + urllib.parse.urlencode({"clientId": client_id}))
        exact = [client for client in clients if client.get("clientId") == client_id]
        if len(exact) > 1:
            raise SafeError("Duplicate local client identifiers found.")
        return exact[0] if exact else None

    def service_secret(self):
        client = self.client("traceback-cli")
        if client is None or not client.get("serviceAccountsEnabled") or client.get("publicClient"):
            raise SafeError("The existing service-account client is unavailable.")
        secret = self.api("/clients/" + client["id"] + "/client-secret").get("value")
        if not isinstance(secret, str) or not secret:
            raise SafeError("The local service-account secret is unavailable.")
        return secret


def client_configuration(admin, client):
    """Return only non-secret configuration relevant to isolation/security."""
    cid = client["id"]
    fields = ["clientId", "name", "protocol", "enabled", "publicClient", "bearerOnly", "standardFlowEnabled",
            "implicitFlowEnabled", "directAccessGrantsEnabled", "serviceAccountsEnabled", "fullScopeAllowed",
            "redirectUris", "webOrigins"]
    safe = {key: client.get(key) for key in fields}
    safe["attributes"] = client.get("attributes", {})
    safe["protocolMappers"] = admin.api("/clients/" + cid + "/protocol-mappers/models")
    for kind in ("default", "optional"):
        scopes = admin.api("/clients/" + cid + "/" + kind + "-client-scopes")
        safe[kind + "ClientScopes"] = sorted(scope["name"] for scope in scopes)
        safe[kind + "ScopeMappers"] = [mapper for scope in scopes
                for mapper in admin.api("/client-scopes/" + scope["id"] + "/protocol-mappers/models")]
    return safe


def has_human_mapper(config):
    return any(mapper.get("config", {}).get("claim.name") == "traceback_principal_type"
               and (mapper.get("protocolMapper") != "oidc-hardcoded-claim-mapper"
                    or mapper.get("config", {}).get("claim.value") == "human")
               for source in ("protocolMappers", "defaultScopeMappers", "optionalScopeMappers")
               for mapper in config.get(source, []))


def matches_expected(actual, expected):
    for key, value in expected.items():
        if key == "attributes":
            if any(actual.get(key, {}).get(k) != v for k, v in value.items()):
                return False
        elif key == "protocolMappers":
            actual_maps = {m.get("name"): m for m in actual.get(key, [])}
            if len(actual_maps) != len(value) or len(actual.get(key, [])) != len(value):
                return False
            for wanted in value:
                found = actual_maps.get(wanted["name"], {})
                if any(found.get(k) != wanted.get(k) for k in ("name", "protocol", "protocolMapper", "consentRequired")):
                    return False
                # Keycloak adds default introspection/userinfo mapper flags. Compare
                # every requested value without rejecting harmless server defaults.
                if any(found.get("config", {}).get(k) != v for k, v in wanted["config"].items()):
                    return False
        elif key.endswith("ClientScopes"):
            if sorted(actual.get(key, [])) != sorted(value):
                return False
        elif actual.get(key) != value:
            return False
    return not has_human_mapper({"defaultScopeMappers": actual.get("defaultScopeMappers", []),
                                 "optionalScopeMappers": actual.get("optionalScopeMappers", [])})
