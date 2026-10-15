import unittest
import urllib.parse
from mcp_client import callback_values
from mcp_setup import CALLBACK, CLIENT_ID, ISSUER, RESOURCE, SafeError, clients

class McpDemoTests(unittest.TestCase):
    def callback(self, **overrides):
        return '/callback?' + urllib.parse.urlencode({'state': 'expected', 'iss': ISSUER, 'code': 'synthetic-code', **overrides})
    def test_local_pkce_callback_accepts_exact_relative_or_full_uri(self):
        self.assertEqual(callback_values(self.callback(), 'expected'), 'synthetic-code')
        self.assertEqual(callback_values('http://127.0.0.1:8767' + self.callback(), 'expected'), 'synthetic-code')
    def test_callback_rejects_foreign_authority_state_issuer_or_duplicate_code(self):
        for target in ['http://example.test' + self.callback(), '//example.test' + self.callback(),
                       self.callback(state='wrong'), self.callback(iss='http://example.test'),
                       self.callback() + '&code=duplicate', self.callback(code='')]:
            with self.assertRaises(SafeError): callback_values(target, 'expected')
    def test_dedicated_clients_preserve_pkce_and_resource_boundaries(self):
        resource, client = clients()
        self.assertTrue(resource['bearerOnly'])
        self.assertEqual(resource['attributes']['resource_url'], RESOURCE)
        self.assertEqual(client['clientId'], CLIENT_ID)
        self.assertTrue(client['publicClient'])
        self.assertEqual(client['redirectUris'], [CALLBACK])
        self.assertEqual(client['attributes']['pkce.code.challenge.method'], 'S256')
        for field in ['directAccessGrantsEnabled', 'serviceAccountsEnabled', 'implicitFlowEnabled', 'fullScopeAllowed']:
            self.assertFalse(client[field])
        human = next(m for m in client['protocolMappers'] if m['name'] == 'human')
        self.assertEqual(human['config']['claim.value'], 'human')
        self.assertEqual(human['config']['id.token.claim'], 'false')
        self.assertNotIn('traceback-api', str(client))
