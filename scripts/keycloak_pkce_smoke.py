#!/usr/bin/env python3
"""Real local Code + PKCE smoke test; stdlib only; no raw tokens/passwords logged or saved."""
import argparse
import base64
import hashlib
import json
import secrets
import time
import urllib.parse
import urllib.error
import urllib.request
import webbrowser
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, HTTPServer
from local_oidc import ISSUER, CALLBACK, LocalAdmin, SafeError, request, require_loopback


def pkce_pair():
    verifier = secrets.token_urlsafe(64)
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode("ascii")).digest()).rstrip(b"=").decode("ascii")
    return verifier, challenge


def jwt_payload(token):
    # Decoding is NOT signature verification. Positive /api/v1/me requests below
    # exercise Spring's real decoder/signature/issuer/audience validation.
    try:
        parts = token.split(".")
        if len(parts) != 3 or len(token) > 100_000:
            raise ValueError()
        payload = json.loads(base64.urlsafe_b64decode(parts[1] + "=" * (-len(parts[1]) % 4)))
        if not isinstance(payload, dict):
            raise ValueError()
        return payload
    except (ValueError, TypeError, UnicodeError):
        raise SafeError("Received an invalid JWT representation.") from None


def callback_values(target, state):
    """Validate callback state before accepting a code; return no raw error descriptions."""
    parsed = urllib.parse.urlsplit(target)
    if parsed.scheme or parsed.netloc or parsed.fragment or parsed.path != "/callback" or len(target) > 8192:
        raise SafeError("Invalid callback path.")
    values = urllib.parse.parse_qs(parsed.query, keep_blank_values=True)
    if len(values.get("state", [])) != 1 or not secrets.compare_digest(values["state"][0].encode(), state.encode()):
        raise SafeError("Callback state mismatch.")
    if "iss" in values and values["iss"] != [ISSUER]:
        raise SafeError("Callback issuer mismatch.")
    if "error" in values:
        return None, True
    if len(values.get("code", [])) != 1 or not values["code"][0]:
        raise SafeError("Callback must contain exactly one authorization code.")
    return values["code"][0], False


def receive_code(client_id, challenge, state, nonce, *, prompt, timeout=600):
    result = {"code": None, "error": False}

    class CallbackHandler(BaseHTTPRequestHandler):
        def log_message(self, format, *args):
            pass  # Default access logs would expose authorization codes and state.

        def do_GET(self):
            try:
                if self.headers.get("Host") != "127.0.0.1:8765":
                    raise SafeError("Invalid callback host.")
                code, error = callback_values(self.path, state)
                result.update(code=code, error=error)
                status, text = 200, "Callback received. Return to the terminal; you may close this tab."
            except SafeError:
                status, text = 400, "Callback rejected. Continue the original local login."
            body = text.encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("Referrer-Policy", "no-referrer")
            self.send_header("Content-Security-Policy", "default-src 'none'")
            self.end_headers()
            self.wfile.write(body)

    query = {"client_id": client_id, "redirect_uri": CALLBACK, "response_type": "code", "scope": "openid",
            "code_challenge": challenge, "code_challenge_method": "S256", "state": state, "nonce": nonce, "prompt": prompt}
    if prompt == "login":
        query["login_hint"] = "traceback-smoke-user"
    url = ISSUER + "/protocol/openid-connect/auth?" + urllib.parse.urlencode(query)
    with HTTPServer(("127.0.0.1", 8765), CallbackHandler) as server:
        server.timeout = 1
        print("Opening local browser login for " + client_id + ".", flush=True)
        if not webbrowser.open(url, new=1):
            raise SafeError("The browser could not be opened; no authorization URL was printed.")
        deadline = time.monotonic() + timeout
        while not result["code"] and not result["error"] and time.monotonic() < deadline:
            server.handle_request()
    if result["error"] or not result["code"]:
        raise SafeError("Browser login was cancelled, requires interaction, or timed out.")
    return result["code"]


def browser_token(client_id, *, prompt):
    verifier, challenge = pkce_pair()
    state, nonce = secrets.token_urlsafe(32), secrets.token_urlsafe(32)
    code = receive_code(client_id, challenge, state, nonce, prompt=prompt)
    status, tokens = request("POST", ISSUER + "/protocol/openid-connect/token", form={
        "grant_type": "authorization_code", "client_id": client_id, "redirect_uri": CALLBACK,
        "code": code, "code_verifier": verifier})
    del code, verifier
    if status != 200 or not isinstance(tokens, dict) or tokens.get("token_type", "").lower() != "bearer":
        raise SafeError("Authorization-code exchange failed (HTTP " + str(status) + ").")
    access = tokens.get("access_token")
    id_token = tokens.get("id_token")
    if not isinstance(access, str) or not isinstance(id_token, str):
        raise SafeError("Token exchange omitted the required tokens.")
    identity_claims = jwt_payload(id_token)
    if identity_claims.get("nonce") != nonce or identity_claims.get("iss") != ISSUER:
        raise SafeError("ID-token nonce/issuer did not match the browser flow.")
    tokens.clear()  # Do not retain refresh tokens or other token response data.
    del id_token, identity_claims
    return access


def check_claims(token, client_id, audience, human):
    claims = jwt_payload(token)
    audiences = claims.get("aud", [])
    if isinstance(audiences, str):
        audiences = [audiences]
    checks = {"issuer_matches": claims.get("iss") == ISSUER,
              "audience_matches": audience in audiences,
              "client_matches": claims.get("azp") == client_id,
              "classification_matches": claims.get("traceback_principal_type") == "human" if human
                   else "traceback_principal_type" not in claims,
              "subject_present": isinstance(claims.get("sub"), str) and bool(claims["sub"]),
              "not_expired": isinstance(claims.get("exp"), (int, float)) and claims["exp"] > time.time()}
    if audience != "traceback-api":
        checks["api_audience_absent"] = "traceback-api" not in audiences
    if not all(checks.values()):
        raise SafeError("Token claim checks failed; no claims containing user information were displayed.")
    return checks


def tamper_signature(token):
    parts = token.split(".")
    if len(parts) != 3 or not parts[2]:
        raise SafeError("Cannot create the invalid-signature probe.")
    parts[2] = ("A" if parts[2][0] != "A" else "B") + parts[2][1:]
    return ".".join(parts)


def pkce_policy_probes():
    """Check error redirects without following them or exposing their raw URLs."""
    class NoFollow(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, *args, **kwargs):
            return None

    results = {}
    for name, extra in (("missing_challenge", {}),
                        ("plain_method", {"code_challenge_method": "plain", "code_challenge": "a" * 43})):
        state = secrets.token_urlsafe(32)
        params = {"client_id": "traceback-web-test", "redirect_uri": CALLBACK, "response_type": "code",
                  "scope": "openid", "state": state}
        params.update(extra)
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoFollow())
        try:
            response = opener.open(ISSUER + "/protocol/openid-connect/auth?" + urllib.parse.urlencode(params), timeout=10)
            response.close()
            raise SafeError("A PKCE policy probe unexpectedly succeeded.")
        except urllib.error.HTTPError as error:
            status = error.code
            location = urllib.parse.urlsplit(error.headers.get("Location", ""))
            values = urllib.parse.parse_qs(location.query)
            error.close()
            if (status != 302 or urllib.parse.urlunsplit(location._replace(query="", fragment="")) != CALLBACK
                    or values.get("state") != [state] or values.get("error") != ["invalid_request"] or "code" in values):
                raise SafeError("A PKCE policy probe did not return the expected safe rejection.") from None
            results[name] = {"status": status, "oauth_error": "invalid_request", "no_authorization_code": True}
        except (urllib.error.URLError, TimeoutError, OSError):
            raise SafeError("A local PKCE policy probe failed or timed out.") from None
    return results


def run(api_base, *, wrong_audience=True):
    require_loopback(api_base)
    parsed = urllib.parse.urlsplit(api_base)
    if parsed.path not in ("", "/") or parsed.query:
        raise SafeError("The API base must be a loopback origin with no path or query.")
    api_base = api_base.rstrip("/")
    report = {"timestamp_utc": datetime.now(timezone.utc).isoformat(), "passed": False, "http_checks": [], "claim_checks": {}, "test_data": {}}
    status, health = request("GET", api_base + "/actuator/health")
    if status != 200 or not isinstance(health, dict) or health.get("status") != "UP":
        raise SafeError("The local backend is not healthy; start it before browser login.")

    def api(name, method, path, expected, token=None, payload=None):
        status, body = request(method, api_base + path, token=token, payload=payload)
        report["http_checks"].append({"name": name, "status": status, "expected": expected})
        print(name + ": HTTP " + str(status), flush=True)
        if status != expected:
            raise SafeError("An API smoke check returned an unexpected HTTP status.")
        return body

    report["pkce_enforcement"] = pkce_policy_probes()
    human = browser_token("traceback-web-test", prompt="login")
    report["claim_checks"]["human"] = check_claims(human, "traceback-web-test", "traceback-api", True)
    api("human_signature_issuer_audience_validation", "GET", "/api/v1/me", 200, human)
    org, incident = None, None
    try:
        org = api("human_create_organization", "POST", "/api/v1/organizations", 201, human,
                  {"name": "OIDC smoke " + secrets.token_hex(6)})
        if not isinstance(org, dict) or org.get("role") != "ADMIN" or not org.get("id"):
            raise SafeError("Organization creation returned an unexpected contract.")
        report["test_data"]["organization_id"] = org["id"]
        listed = api("human_list_organizations", "GET", "/api/v1/organizations", 200, human)
        if not isinstance(listed, list) or not any(item.get("id") == org["id"] for item in listed):
            raise SafeError("The created organization was not listed for its creator.")
        collection = "/api/v1/organizations/" + org["id"] + "/incidents"
        incident = api("human_create_incident", "POST", collection, 201, human,
                       {"title": "Local OIDC smoke incident", "description": "Synthetic disposable authentication test data."})
        if not isinstance(incident, dict) or incident.get("organizationId") != org["id"] or not incident.get("id"):
            raise SafeError("Incident creation returned an unexpected ownership contract.")
        report["test_data"]["incident_id"] = incident["id"]
        item_path = collection + "/" + incident["id"]
        retrieved = api("human_retrieve_incident", "GET", item_path, 200, human)
        if not isinstance(retrieved, dict) or retrieved.get("organizationId") != org["id"] or retrieved.get("id") != incident["id"]:
            raise SafeError("Incident retrieval returned an unexpected ownership contract.")
        # Retrieve the existing secret in memory from the local admin API, never from a
        # command-line argument, file, environment export, or displayed secret field.
        secret = LocalAdmin().service_secret()
        status, tokens = request("POST", ISSUER + "/protocol/openid-connect/token", form={
                "grant_type": "client_credentials", "client_id": "traceback-cli", "client_secret": secret})
        del secret
        if status != 200 or not isinstance(tokens, dict) or not isinstance(tokens.get("access_token"), str):
            raise SafeError("Local service-account token acquisition failed (HTTP " + str(status) + ").")
        service = tokens["access_token"]
        tokens.clear()
        report["claim_checks"]["service_account"] = check_claims(service, "traceback-cli", "traceback-api", False)
        api("service_signature_issuer_audience_validation", "GET", "/api/v1/me", 200, service)
        api("service_list_organizations_denied", "GET", "/api/v1/organizations", 403, service)
        api("service_create_organization_denied", "POST", "/api/v1/organizations", 403, service, {"name": "Must not be created"})
        api("service_list_incidents_denied", "GET", collection, 403, service)
        api("service_retrieve_incident_denied", "GET", item_path, 403, service)
        api("service_create_incident_denied", "POST", collection, 403, service, {"title": "Denied", "description": "Denied"})
        api("service_update_incident_denied", "PATCH", item_path, 403, service, {"title": "Denied", "version": incident["version"]})
        api("service_delete_incident_denied", "DELETE", item_path + "?version=" + str(incident["version"]), 403, service)
        del service
        api("missing_authentication", "GET", "/api/v1/organizations", 401)
        api("malformed_authentication", "GET", "/api/v1/organizations", 401, "not-a-valid-token")
        api("invalid_signature", "GET", "/api/v1/organizations", 401, tamper_signature(human))
        if wrong_audience:
            wrong = browser_token("traceback-web-test-wrong-audience", prompt="none")
            report["claim_checks"]["wrong_audience"] = check_claims(wrong, "traceback-web-test-wrong-audience", "traceback-not-api", True)
            api("wrong_audience_identity_rejected", "GET", "/api/v1/me", 401, wrong)
            api("wrong_audience_organizations_rejected", "GET", "/api/v1/organizations", 401, wrong)
            api("wrong_audience_incidents_rejected", "GET", collection, 401, wrong)
            del wrong
        else:
            report["wrong_audience_test"] = "explicitly skipped"
        report["passed"] = True
    finally:
        if isinstance(org, dict):
            report["test_data"]["organization_retained_until_disposable_database_removal"] = True
        if isinstance(incident, dict) and incident.get("id"):
            path = "/api/v1/organizations/" + org["id"] + "/incidents/" + incident["id"] + "?version=" + str(incident["version"])
            status, _ = request("DELETE", api_base + path, token=human)
            report["http_checks"].append({"name": "human_cleanup_incident", "status": status, "expected": 204})
            report["test_data"]["incident_cleanup_succeeded"] = status == 204
            if status != 204:
                report["passed"] = False
        del human
        print("Sanitized result:", flush=True)
        print(json.dumps(report, indent=2), flush=True)
    if not report["passed"]:
        raise SafeError("The smoke run or its incident cleanup did not pass.")
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--api-base", default="http://127.0.0.1:8766")
    parser.add_argument("--skip-wrong-audience", action="store_true")
    args = parser.parse_args()
    try:
        run(args.api_base, wrong_audience=not args.skip_wrong_audience)
    except SafeError as error:
        print("Smoke test stopped:", error, flush=True)
        raise SystemExit(1) from None
    except KeyboardInterrupt:
        print("Smoke test cancelled; no tokens saved.", flush=True)
        raise SystemExit(130) from None
    except Exception:
        print("Smoke test stopped due to an unexpected local error; no sensitive details displayed.", flush=True)
        raise SystemExit(1) from None
