"""Generate one private synthetic Keycloak realm; never touch existing realms/volumes."""
import argparse
import json
import os
from pathlib import Path
import secrets


def prepare(directory):
    directory = directory.resolve()
    directory.mkdir(mode=0o700, parents=True, exist_ok=False)
    password = secrets.token_urlsafe(32)
    client = {
        "clientId": "traceback-m17-web", "publicClient": True,
        "standardFlowEnabled": True, "directAccessGrantsEnabled": False,
        "serviceAccountsEnabled": False, "implicitFlowEnabled": False,
        "redirectUris": ["http://127.0.0.1:18765/callback"], "webOrigins": [],
        "attributes": {"pkce.code.challenge.method": "S256"},
        "protocolMappers": [
            {"name": "human", "protocol": "openid-connect", "protocolMapper": "oidc-hardcoded-claim-mapper",
             "config": {"claim.name": "traceback_principal_type", "claim.value": "human", "jsonType.label": "String", "access.token.claim": "true", "id.token.claim": "false"}},
            {"name": "rest-audience", "protocol": "openid-connect", "protocolMapper": "oidc-audience-mapper",
             "config": {"included.custom.audience": "traceback-api", "access.token.claim": "true", "id.token.claim": "false"}},
        ],
    }
    realm = {"realm": "traceback-m17", "enabled": True, "sslRequired": "none", "registrationAllowed": False,
             "clients": [client], "users": [{"username": "m17-synthetic", "enabled": True,
                 "emailVerified": True, "firstName": "Synthetic", "lastName": "Smoke", "email": "m17@example.test",
                 "credentials": [{"type": "password", "value": password, "temporary": False}]}]}
    for name, value in (("realm.json", realm), ("login.json", {"username": "m17-synthetic", "password": password})):
        path = directory / name
        with open(path, "x", opener=lambda p, flags: os.open(p, flags, 0o600)) as output:
            json.dump(value, output)
        if name == "realm.json":
            # Import is intentionally readable by the container's unprivileged UID;
            # its host parent stays 0700. Never reuse these synthetic credentials.
            path.chmod(0o444)
    print("Private synthetic fixture created; credentials are not printed.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", required=True, type=Path)
    prepare(parser.parse_args().directory)
