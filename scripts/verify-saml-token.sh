#!/usr/bin/env bash

set -euo pipefail

VERIFY_PROTOCOL=saml
DEFAULT_CLIENT_ID=saml-test
: "${CLIENT_ID:=saml-test}"
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/verify-common.sh"
parse_options "$@"
require_commands curl python3

SAML_ENDPOINT="$KEYCLOAK_URL/realms/$REALM/protocol/saml"
log "Requesting SAML assertion for user '$TEST_USERNAME' via '$CLIENT_ID' in realm '$REALM' at '$KEYCLOAK_URL'"
REQUEST=$(python3 "$SCRIPT_DIR/verify-saml.py" request "$SAML_ENDPOINT" "$CLIENT_ID" "$SAML_ACS_URL")
RESPONSE=$(curl_response POST "$SAML_ENDPOINT" 200 \
    --basic --user "$TEST_USERNAME:$TEST_PASSWORD" \
    -H 'Content-Type: text/xml; charset=utf-8' --data-binary "$REQUEST")
printf '%s' "$RESPONSE" | python3 "$SCRIPT_DIR/verify-saml.py" response "$REQUEST"
log "Done. SAML assertion issued; no client settings changed. Signatures are not verified by this diagnostic script."