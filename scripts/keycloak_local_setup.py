#!/usr/bin/env python3
"""Inspect or add the reviewed local clients/user; never overwrite existing configuration."""
import argparse
import json
import urllib.parse
from local_oidc import ROOT, LocalAdmin, SafeError, client_configuration, has_human_mapper, matches_expected


def validate_client_descriptor(client):
    client_id = client.get("clientId")
    if client_id not in ("traceback-web-test", "traceback-web-test-wrong-audience"):
        raise SafeError("Unexpected client in the local configuration.")
    required = {"protocol": "openid-connect", "enabled": True, "publicClient": True, "bearerOnly": False,
            "standardFlowEnabled": True, "implicitFlowEnabled": False, "directAccessGrantsEnabled": False,
            "serviceAccountsEnabled": False, "fullScopeAllowed": False,
            "redirectUris": ["http://127.0.0.1:8765/callback"], "webOrigins": [],
            "defaultClientScopes": ["basic"], "optionalClientScopes": []}
    if any(client.get(k) != v for k, v in required.items()) or any(k in client for k in ("secret", "credentials", "access_token", "refresh_token")):
        raise SafeError("The client descriptor violates the local-only public PKCE policy.")
    attributes = {"pkce.code.challenge.method": "S256", "standard.token.exchange.enabled": "false",
            "oauth2.device.authorization.grant.enabled": "false", "oidc.ciba.grant.enabled": "false",
            "oauth2.jwt.authorization.grant.enabled": "false"}
    if any(client.get("attributes", {}).get(k) != v for k, v in attributes.items()):
        raise SafeError("The client descriptor enables an unapproved grant or PKCE method.")
    mappers = client.get("protocolMappers", [])
    if len(mappers) != 2:
        raise SafeError("Exactly two dedicated client mappers are required.")
    kinds = {mapper.get("protocolMapper"): mapper.get("config", {}) for mapper in mappers}
    human = kinds.get("oidc-hardcoded-claim-mapper", {})
    audience = kinds.get("oidc-audience-mapper", {})
    expected_audience = "traceback-api" if client_id == "traceback-web-test" else "traceback-not-api"
    if (human.get("claim.name") != "traceback_principal_type" or human.get("claim.value") != "human"
            or human.get("jsonType.label") != "String" or human.get("access.token.claim") != "true"
            or human.get("id.token.claim") != "false" or human.get("userinfo.token.claim") != "false"
            or audience.get("included.custom.audience") != expected_audience or audience.get("access.token.claim") != "true"):
        raise SafeError("The dedicated claim/audience mappers violate the reviewed policy.")


def run(apply=False):
    config = json.loads((ROOT / "dev/keycloak/local-smoke-config.json").read_text())
    if config.get("realm") != "traceback":
        raise SafeError("Only the existing traceback realm is supported.")
    for client in config["clients"]:
        validate_client_descriptor(client)
    admin = LocalAdmin()
    cli = admin.client("traceback-cli")
    if cli is None:
        raise SafeError("The existing traceback-cli client is missing; refusing to create or replace it.")
    cli_before = client_configuration(admin, cli)
    if has_human_mapper(cli_before):
        raise SafeError("traceback-cli inherits a human mapper; stop for a separate reviewed correction.")
    report = {"service_client_has_human_mapper": False, "clients": {}}
    for expected in config["clients"]:
        if expected["clientId"] not in ("traceback-web-test", "traceback-web-test-wrong-audience"):
            raise SafeError("Unexpected client in the local configuration.")
        existing = admin.client(expected["clientId"])
        created = False
        if existing is None and apply:
            admin.api("/clients", method="POST", payload=expected)
            existing = admin.client(expected["clientId"])
            created = True
        if existing is None:
            report["clients"][expected["clientId"]] = "absent (no changes)"
        else:
            if not matches_expected(client_configuration(admin, existing), expected):
                raise SafeError("A smoke client has conflicting settings; it was not overwritten. Review it separately.")
            report["clients"][expected["clientId"]] = "created and verified" if created else "verified unchanged"
    expected_user = config["testUser"]
    if expected_user != {"username": "traceback-smoke-user", "enabled": True}:
        raise SafeError("Only the passwordless synthetic test-user descriptor is supported.")
    users = admin.api("/users?" + urllib.parse.urlencode({"username": expected_user["username"], "exact": "true"}))
    if not users and apply:
        admin.api("/users", method="POST", payload=expected_user)
        users = admin.api("/users?" + urllib.parse.urlencode({"username": expected_user["username"], "exact": "true"}))
        report["test_user"] = "created without credentials; user must set password interactively"
    else:
        report["test_user"] = "present unchanged" if users else "absent (no changes)"
    if users:
        report["password_setup_page"] = "http://localhost:8180/admin/master/console/#/traceback/users/" + users[0]["id"] + "/credentials"
    if client_configuration(admin, admin.client("traceback-cli")) != cli_before:
        raise SafeError("Unexpected service-client configuration change detected.")
    report["service_client_configuration_preserved"] = True
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true", help="Only after explicit approval: add absent clients/user; never modify existing objects.")
    args = parser.parse_args()
    try:
        run(args.apply)
    except SafeError as error:
        print("Setup stopped:", error)
        raise SystemExit(1) from None
    except Exception:
        print("Setup stopped due to an unexpected local error; no sensitive details displayed.")
        raise SystemExit(1) from None
