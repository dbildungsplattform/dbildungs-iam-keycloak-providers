import base64
import http.server
import json
import os
from pathlib import Path
import subprocess
import threading
import unittest
import urllib.parse
import xml.etree.ElementTree as ET


SCRIPTS = Path(__file__).resolve().parent
SOAP = "http://schemas.xmlsoap.org/soap/envelope/"
PROTOCOL = "urn:oasis:names:tc:SAML:2.0:protocol"
ASSERTION = "urn:oasis:names:tc:SAML:2.0:assertion"
ECP = "urn:oasis:names:tc:SAML:2.0:profiles:SSO:ecp"


class KeycloakStub(http.server.BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def send_body(self, code, body):
        self.send_response(code)
        self.end_headers()
        self.wfile.write(body.encode())

    def do_GET(self):
        if self.path.endswith("client-secret"):
            result = {"value": "secret&+value"}
        elif "?" in self.path:
            result = [{"id": "client-123"}]
        else:
            result = {"protocol": "openid-connect", "directAccessGrantsEnabled": self.server.direct_grants}
        self.send_body(200, json.dumps(result))

    def do_PUT(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        self.server.updates.append(body)
        self.send_body(500 if self.server.fail_restore and not body["directAccessGrantsEnabled"] else 204, "")

    def do_POST(self):
        body = self.rfile.read(int(self.headers["Content-Length"]))
        if self.path.endswith("/protocol/saml"):
            self.server.saml_authorization = self.headers.get("Authorization")
            self.send_body(200, self.saml_response(body))
            return
        fields = urllib.parse.parse_qs(body.decode())
        self.server.forms.append(fields)
        if "/master/" in self.path:
            self.send_body(200, json.dumps({"access_token": "admin-token"}))
        elif self.server.fail_login:
            self.send_body(400, json.dumps({"error": "invalid_grant"}))
        else:
            claims = base64.urlsafe_b64encode(json.dumps({"rolle": "LEHR"}).encode()).decode().rstrip("=")
            self.send_body(200, json.dumps({"access_token": f"header.{claims}.signature"}))

    def saml_response(self, body):
        request = ET.fromstring(body).find(f"{{{SOAP}}}Body/{{{PROTOCOL}}}AuthnRequest")
        self.server.saml_request = request
        envelope = ET.Element(f"{{{SOAP}}}Envelope")
        header = ET.SubElement(envelope, f"{{{SOAP}}}Header")
        acs_url = request.get("AssertionConsumerServiceURL")
        ET.SubElement(header, f"{{{ECP}}}Response", {"AssertionConsumerServiceURL": acs_url})
        soap_body = ET.SubElement(envelope, f"{{{SOAP}}}Body")
        if self.server.saml_mode == "fault":
            ET.SubElement(soap_body, f"{{{SOAP}}}Fault").text = "ECP disabled"
            return ET.tostring(envelope, encoding="unicode")
        response = ET.SubElement(soap_body, f"{{{PROTOCOL}}}Response", {
            "InResponseTo": "wrong-id" if self.server.saml_mode == "mismatch" else request.get("ID"),
            "Destination": acs_url,
        })
        status = ET.SubElement(response, f"{{{PROTOCOL}}}Status")
        ET.SubElement(status, f"{{{PROTOCOL}}}StatusCode", {
            "Value": "urn:oasis:names:tc:SAML:2.0:status:" +
                     ("AuthnFailed" if self.server.saml_mode == "denied" else "Success"),
        })
        if self.server.saml_mode != "no-assertion":
            assertion = ET.SubElement(response, f"{{{ASSERTION}}}Assertion")
            conditions = ET.SubElement(assertion, f"{{{ASSERTION}}}Conditions")
            restriction = ET.SubElement(conditions, f"{{{ASSERTION}}}AudienceRestriction")
            ET.SubElement(restriction, f"{{{ASSERTION}}}Audience").text = request.findtext(f"{{{ASSERTION}}}Issuer")
            statement = ET.SubElement(assertion, f"{{{ASSERTION}}}AttributeStatement")
            attribute = ET.SubElement(statement, f"{{{ASSERTION}}}Attribute", {"Name": "schulkennung"})
            for number in ["1234567", "7654321"]:
                ET.SubElement(attribute, f"{{{ASSERTION}}}AttributeValue").text = number
        return ET.tostring(envelope, encoding="unicode")


class VerifyScriptsTest(unittest.TestCase):
    def setUp(self):
        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), KeycloakStub)
        self.server.updates = []
        self.server.forms = []
        self.server.direct_grants = False
        self.server.fail_login = False
        self.server.fail_restore = False
        self.server.saml_mode = "success"
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.environment = {
            **os.environ,
            "KEYCLOAK_URL": f"http://127.0.0.1:{self.server.server_port}",
            "REALM": "SPSH",
            "CLIENT_ID": "",
            "ADMIN_USER": "admin",
            "ADMIN_PASSWORD": "admin&+password",
            "TEST_USERNAME": "environment-user",
            "TEST_PASSWORD": "test&+password",
            "SAML_ACS_URL": "http://localhost:8765/saml",
        }

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()

    def run_script(self, script, *options):
        return subprocess.run(["bash", str(SCRIPTS / script), *options], env=self.environment,
                              capture_output=True, text=True, timeout=15)

    def test_oidc_prints_claims_and_restores_setting(self):
        result = self.run_script("verify-oidc-token.sh", "--test-username", "cli-user")

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout), {"rolle": "LEHR"})
        self.assertEqual(self.server.updates, [{"directAccessGrantsEnabled": True}, {"directAccessGrantsEnabled": False}])
        self.assertEqual(self.server.forms[-1]["username"], ["cli-user"])
        self.assertEqual(self.server.forms[-1]["password"], ["test&+password"])
        self.assertEqual(self.server.forms[-1]["client_secret"], ["secret&+value"])
        self.assertEqual(self.server.forms[0]["password"], ["admin&+password"])

    def test_oidc_restores_setting_after_failed_login(self):
        self.server.fail_login = True

        result = self.run_script("verify-oidc-token.sh")

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("invalid_grant", result.stderr)
        self.assertEqual(self.server.updates[-1], {"directAccessGrantsEnabled": False})

    def test_oidc_keeps_existing_enabled_setting(self):
        self.server.direct_grants = True

        result = self.run_script("verify-oidc-token.sh")

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.server.updates, [])

    def test_oidc_warns_if_restore_fails(self):
        self.server.fail_restore = True

        result = self.run_script("verify-oidc-token.sh")

        self.assertIn("WARNING: Could not restore", result.stderr)

    def test_saml_prints_separate_attribute_values_without_admin_requests(self):
        result = self.run_script("verify-saml-token.sh", "--test-username", "cli-user")

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout)["attributes"], {"schulkennung": ["1234567", "7654321"]})
        self.assertEqual(self.server.updates, [])
        self.assertEqual(self.server.forms, [])
        self.assertEqual(self.server.saml_request.findtext(f"{{{ASSERTION}}}Issuer"), "saml-test")
        authorization = "Basic " + base64.b64encode(b"cli-user:test&+password").decode()
        self.assertEqual(self.server.saml_authorization, authorization)

    def test_saml_rejects_unsuccessful_or_unrelated_responses(self):
        for mode in ["fault", "denied", "no-assertion", "mismatch"]:
            with self.subTest(mode=mode):
                self.server.saml_mode = mode

                result = self.run_script("verify-saml-token.sh")

                self.assertNotEqual(result.returncode, 0)
                self.assertIn("Error:", result.stderr)
                self.assertEqual(result.stdout, "")

    def test_cli_client_and_acs_override_environment(self):
        self.environment["CLIENT_ID"] = "environment-client"

        result = self.run_script("verify-saml-token.sh", "--client-id", "cli-client",
                                 "--acs-url", "http://localhost:8765/other")

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.server.saml_request.findtext(f"{{{ASSERTION}}}Issuer"), "cli-client")
        self.assertEqual(self.server.saml_request.get("AssertionConsumerServiceURL"), "http://localhost:8765/other")

    def test_help_and_missing_option_values(self):
        for script in ["verify-oidc-token.sh", "verify-saml-token.sh"]:
            with self.subTest(script=script):
                self.assertEqual(self.run_script(script, "--help").returncode, 0)
                result = self.run_script(script, "--test-password")
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("Missing value for --test-password", result.stderr)


if __name__ == "__main__":
    unittest.main()