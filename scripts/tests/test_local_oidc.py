import base64
import copy
import hashlib
import io
import json
import sys
import time
import unittest
import urllib.error
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import local_oidc
import keycloak_pkce_smoke as smoke
import keycloak_local_setup as setup


class LocalOidcTests(unittest.TestCase):
    def token(self, payload):
        encode = lambda item: base64.urlsafe_b64encode(json.dumps(item).encode()).rstrip(b"=").decode()
        return encode({"alg": "RS256"}) + "." + encode(payload) + ".synthetic-signature"

    def claims(self, client="traceback-web-test", aud="traceback-api", human=True):
        payload = {"iss": local_oidc.ISSUER, "aud": aud, "azp": client, "sub": "synthetic-subject", "exp": time.time() + 300}
        if human:
            payload["traceback_principal_type"] = "human"
        return payload

    def test_pkce_s256_and_entropy_format(self):
        verifier, challenge = smoke.pkce_pair()
        self.assertGreaterEqual(len(verifier), 43)
        self.assertLessEqual(len(verifier), 128)
        self.assertRegex(verifier, r"^[A-Za-z0-9_-]+$")
        self.assertEqual(challenge, base64.urlsafe_b64encode(hashlib.sha256(verifier.encode("ascii")).digest()).rstrip(b"=").decode())
        self.assertNotEqual(verifier, smoke.pkce_pair()[0])

    def test_callback_validates_state_issuer_and_single_code(self):
        self.assertEqual(("synthetic-code", False), smoke.callback_values(
            "/callback?state=expected&code=synthetic-code&iss=" + local_oidc.urllib.parse.quote(local_oidc.ISSUER), "expected"))

    def test_callback_rejects_spoofed_state_duplicate_parameters_and_wrong_path(self):
        for target in ("/callback?state=wrong&code=x", "/callback?code=x", "/callback?state=expected&code=",
                       "/callback?state=expected&state=expected&code=x", "/callback?state=expected&code=x&code=y",
                       "/elsewhere?state=expected&code=x", "http://external.example/callback?state=expected&code=x",
                       "/callback?state=expected&code=x&iss=http://external.example", "/callback?state=expected&code=x#fragment"):
            with self.subTest(target=target), self.assertRaises(local_oidc.SafeError):
                smoke.callback_values(target, "expected")

    def test_callback_error_description_is_not_returned(self):
        self.assertEqual((None, True), smoke.callback_values("/callback?state=expected&error=denied&error_description=private", "expected"))

    def test_local_only_http_urls(self):
        for url in (local_oidc.ISSUER, local_oidc.CALLBACK, "http://[::1]:8766"):
            self.assertEqual(url, local_oidc.require_loopback(url))
        for url in ("http://external.example", "https://external.example", "file:///tmp/test", "http://user:password@localhost:8180", "http://localhost:8180#fragment"):
            with self.subTest(url=url), self.assertRaises(local_oidc.SafeError):
                local_oidc.require_loopback(url)

    def test_redirect_refusal_never_displays_destination(self):
        with self.assertRaises(local_oidc.SafeError) as context:
            local_oidc.NoRedirect().redirect_request(None, None, 302, "Found", {}, "http://external.example/?private=secret")
        self.assertNotIn("secret", str(context.exception))
        self.assertNotIn("external", str(context.exception))

    def test_http_error_body_is_not_returned_or_logged(self):
        error = urllib.error.HTTPError("http://localhost:8180/token", 401, "Unauthorized", {}, io.BytesIO(b'{"private":"secret"}'))
        with patch("urllib.request.build_opener") as opener, patch("sys.stdout", new_callable=io.StringIO) as output:
            opener.return_value.open.side_effect = error
            self.assertEqual((401, None), local_oidc.request("POST", "http://localhost:8180/token", form={"secret": "synthetic"}))
            self.assertEqual("", output.getvalue())
        self.assertTrue(error.fp.closed)

    def test_human_claims_checked_without_subject_disclosure(self):
        result = smoke.check_claims(self.token(self.claims()), "traceback-web-test", "traceback-api", True)
        self.assertTrue(all(result.values()))
        self.assertNotIn("synthetic-subject", json.dumps(result))

    def test_incorrect_human_claims_fail_safely(self):
        for field, value in (("iss", "http://other.example"), ("aud", "wrong"), ("traceback_principal_type", True), ("exp", 1), ("sub", "")):
            claims = self.claims()
            claims[field] = value
            with self.subTest(field=field), self.assertRaises(local_oidc.SafeError) as context:
                smoke.check_claims(self.token(claims), "traceback-web-test", "traceback-api", True)
            self.assertNotIn("synthetic-subject", str(context.exception))

    def test_service_token_requires_no_classification(self):
        claims = self.claims(client="traceback-cli", human=False)
        self.assertTrue(all(smoke.check_claims(self.token(claims), "traceback-cli", "traceback-api", False).values()))
        claims["traceback_principal_type"] = "human"
        with self.assertRaises(local_oidc.SafeError):
            smoke.check_claims(self.token(claims), "traceback-cli", "traceback-api", False)

    def test_wrong_audience_probe_must_exclude_api_audience(self):
        claims = self.claims(client="traceback-web-test-wrong-audience", aud=["traceback-not-api"])
        self.assertTrue(all(smoke.check_claims(self.token(claims), "traceback-web-test-wrong-audience", "traceback-not-api", True).values()))
        claims["aud"].append("traceback-api")
        with self.assertRaises(local_oidc.SafeError):
            smoke.check_claims(self.token(claims), "traceback-web-test-wrong-audience", "traceback-not-api", True)

    def test_malformed_token_error_contains_no_input(self):
        with self.assertRaises(local_oidc.SafeError) as context:
            smoke.jwt_payload("private-malformed-token")
        self.assertNotIn("private-malformed-token", str(context.exception))

    def test_signature_probe_changes_only_signature(self):
        token = self.token(self.claims())
        changed = smoke.tamper_signature(token)
        self.assertEqual(token.rsplit(".", 1)[0], changed.rsplit(".", 1)[0])
        self.assertNotEqual(token, changed)

    def test_matches_requested_config_tolerates_only_unspecified_server_defaults(self):
        expected = json.loads((local_oidc.ROOT / "dev/keycloak/local-smoke-config.json").read_text())["clients"][0]
        actual = copy.deepcopy(expected)
        actual["protocolMappers"][0]["config"]["introspection.token.claim"] = "true"
        self.assertTrue(local_oidc.matches_expected(actual, expected))
        actual["protocolMappers"][0]["config"]["claim.value"] = "service_account"
        self.assertFalse(local_oidc.matches_expected(actual, expected))

    def test_rejects_extra_duplicate_or_shared_human_mappers(self):
        expected = json.loads((local_oidc.ROOT / "dev/keycloak/local-smoke-config.json").read_text())["clients"][0]
        for source in ("protocolMappers", "defaultScopeMappers", "optionalScopeMappers"):
            actual = copy.deepcopy(expected)
            actual.setdefault(source, []).append(copy.deepcopy(expected["protocolMappers"][0]))
            self.assertFalse(local_oidc.matches_expected(actual, expected))

    def test_setup_refuses_unsafe_or_secret_containing_descriptors(self):
        descriptor = json.loads((local_oidc.ROOT / "dev/keycloak/local-smoke-config.json").read_text())["clients"][0]
        setup.validate_client_descriptor(descriptor)
        for key, value in (("redirectUris", ["http://external.example/callback"]), ("serviceAccountsEnabled", True),
                           ("directAccessGrantsEnabled", True), ("defaultClientScopes", ["roles"]), ("secret", "synthetic-secret")):
            changed = copy.deepcopy(descriptor)
            changed[key] = value
            with self.subTest(key=key), self.assertRaises(local_oidc.SafeError):
                setup.validate_client_descriptor(changed)
        changed = copy.deepcopy(descriptor)
        changed["attributes"]["standard.token.exchange.enabled"] = "true"
        with self.assertRaises(local_oidc.SafeError):
            setup.validate_client_descriptor(changed)

    def test_dynamic_classification_mapper_is_treated_as_unsafe(self):
        self.assertTrue(local_oidc.has_human_mapper({"defaultScopeMappers": [{"protocolMapper": "oidc-usermodel-attribute-mapper", "config": {"claim.name": "traceback_principal_type"}}]}))

    def test_token_exchange_response_checks_nonce_and_drops_refresh_token(self):
        tokens = {"token_type": "Bearer", "access_token": "synthetic-access", "refresh_token": "synthetic-refresh",
                  "id_token": self.token({"iss": local_oidc.ISSUER, "nonce": "synthetic-nonce"})}
        with patch.object(smoke.secrets, "token_urlsafe", side_effect=["synthetic-state", "synthetic-nonce"]), \
             patch.object(smoke, "pkce_pair", return_value=("verifier", "challenge")), \
             patch.object(smoke, "receive_code", return_value="synthetic-code"), \
             patch.object(smoke, "request", return_value=(200, tokens)):
            self.assertEqual("synthetic-access", smoke.browser_token("traceback-web-test", prompt="login"))
        self.assertEqual({}, tokens)

    def test_pkce_policy_probes_accept_error_redirects_without_following(self):
        headers = {"Location": local_oidc.CALLBACK + "?state=synthetic-state&error=invalid_request"}
        errors = [urllib.error.HTTPError("http://localhost:8180/auth", 302, "Found", headers, io.BytesIO(b"")) for _ in range(2)]
        with patch.object(smoke.secrets, "token_urlsafe", return_value="synthetic-state"), \
             patch("urllib.request.build_opener") as opener:
            opener.return_value.open.side_effect = errors
            results = smoke.pkce_policy_probes()
            self.assertEqual({"missing_challenge", "plain_method"}, set(results))
            self.assertTrue(all(result["no_authorization_code"] for result in results.values()))

    def test_nonce_mismatch_is_rejected(self):
        tokens = {"token_type": "Bearer", "access_token": "synthetic-access",
                  "id_token": self.token({"iss": local_oidc.ISSUER, "nonce": "wrong"})}
        with patch.object(smoke, "receive_code", return_value="synthetic-code"), patch.object(smoke, "request", return_value=(200, tokens)):
            with self.assertRaises(local_oidc.SafeError):
                smoke.browser_token("traceback-web-test", prompt="login")


if __name__ == "__main__":
    unittest.main()
