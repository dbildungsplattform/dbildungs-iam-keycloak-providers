#!/usr/bin/env bash
#
# Verifies the spsh-custom-oidc-api-mapper providers by pulling a real access
# token for the "vidis-test" client and printing its claims.
#
# See the "Verifying a token via curl" section in ../Readme.md for the
# manual, step-by-step version of what this script automates.
#
# Usage:
#   ./scripts/verify-oidc-token.sh [options]
#
# Precedence for every setting: CLI flag > environment variable > default.
#
# Options (each also configurable via the environment variable in parentheses):
#   --keycloak-url URL       (KEYCLOAK_URL)    default: http://localhost:8080
#   --realm REALM            (REALM)           default: SPSH
#   --client-id CLIENT_ID    (CLIENT_ID)       default: vidis-test
#   --admin-user USER        (ADMIN_USER)      default: admin
#   --admin-password PASS    (ADMIN_PASSWORD)  default: admin
#   --test-username USER     (TEST_USERNAME)   default: hschuster (Lehrkraft)
#   --test-password PASS     (TEST_PASSWORD)   default: SPSHtest1!
#   -h, --help               show this help and exit
#
# Example for a dev/feature deployment:
#   ./scripts/verify-oidc-token.sh \
#     --keycloak-url "https://<namespace>-keycloak.<dev-domain>" \
#     --admin-password "<from 1Password>"

set -euo pipefail

VERIFY_PROTOCOL=oidc
DEFAULT_CLIENT_ID=vidis-test
: "${CLIENT_ID:=vidis-test}"
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/verify-common.sh"
parse_options "$@"
require_commands curl jq base64

log "Using KEYCLOAK_URL=$KEYCLOAK_URL REALM=$REALM CLIENT_ID=$CLIENT_ID ADMIN_USER=$ADMIN_USER TEST_USERNAME=$TEST_USERNAME"

# base64url (JWT) decode, independent of GNU/BSD base64 differences
b64url_decode() {
    local input="$1"
    input="${input//-/+}"
    input="${input//_//}"
    case $(( ${#input} % 4 )) in
        2) input+="==" ;;
        3) input+="=" ;;
    esac
    printf '%s' "$input" | base64 -d 2>/dev/null
}

# ---- 2. get an admin token (master realm, admin-cli allows the password grant) ----
log "Fetching admin token"
ADMIN_TOKEN_RESPONSE=$(curl_response POST "$KEYCLOAK_URL/realms/master/protocol/openid-connect/token" 200 \
    -d grant_type=password -d client_id=admin-cli \
    --data-urlencode username="$ADMIN_USER" --data-urlencode password="$ADMIN_PASSWORD")
ADMIN_TOKEN=$(echo "$ADMIN_TOKEN_RESPONSE" | jq -r '.access_token // empty')
[[ -n "$ADMIN_TOKEN" ]] || die "Could not obtain an admin token. Check ADMIN_USER/ADMIN_PASSWORD/KEYCLOAK_URL. Response: $ADMIN_TOKEN_RESPONSE"

# ---- 3. look up the client and its current directAccessGrantsEnabled state ----
log "Looking up client '$CLIENT_ID' in realm '$REALM'"
CLIENTS_RESPONSE=$(curl_response GET "$KEYCLOAK_URL/admin/realms/$REALM/clients" 200 \
    --get --data-urlencode clientId="$CLIENT_ID" -H "Authorization: Bearer $ADMIN_TOKEN")
CLIENT_UUID=$(echo "$CLIENTS_RESPONSE" | jq -r '.[0].id // empty')
[[ -n "$CLIENT_UUID" ]] || die "Client '$CLIENT_ID' not found in realm '$REALM'. Check CLIENT_ID/REALM."

CLIENT_JSON=$(curl_response GET "$KEYCLOAK_URL/admin/realms/$REALM/clients/$CLIENT_UUID" 200 \
    -H "Authorization: Bearer $ADMIN_TOKEN")
[[ "$(echo "$CLIENT_JSON" | jq -r '.protocol')" == "openid-connect" ]] || die "Client '$CLIENT_ID' is not an OIDC client. Use verify-saml-token.sh for SAML."
ORIGINAL_DIRECT_GRANTS=$(echo "$CLIENT_JSON" | jq -r '.directAccessGrantsEnabled')

# restore the client's original setting, even if a later step fails
cleanup() {
    if [[ "$ORIGINAL_DIRECT_GRANTS" == "false" ]]; then
        log "Restoring directAccessGrantsEnabled=false on '$CLIENT_ID'"
        curl_response PUT "$KEYCLOAK_URL/admin/realms/$REALM/clients/$CLIENT_UUID" 204 \
            -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
            -d '{"directAccessGrantsEnabled":false}' >/dev/null || \
            printf 'WARNING: Could not restore directAccessGrantsEnabled=false on %s. Restore it manually.\n' "$CLIENT_ID" >&2
    fi
}
trap cleanup EXIT

# ---- 4. temporarily enable the password grant (skip if already enabled) ----
if [[ "$ORIGINAL_DIRECT_GRANTS" == "true" ]]; then
    log "'$CLIENT_ID' already has directAccessGrantsEnabled=true, leaving it as-is"
else
    log "Temporarily enabling directAccessGrantsEnabled on '$CLIENT_ID'"
    curl_response PUT "$KEYCLOAK_URL/admin/realms/$REALM/clients/$CLIENT_UUID" 204 \
        -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
        -d '{"directAccessGrantsEnabled":true}' >/dev/null
fi

# ---- 5. fetch the client secret ----
log "Fetching client secret"
SECRET_RESPONSE=$(curl_response GET "$KEYCLOAK_URL/admin/realms/$REALM/clients/$CLIENT_UUID/client-secret" 200 \
    -H "Authorization: Bearer $ADMIN_TOKEN")
CLIENT_SECRET=$(echo "$SECRET_RESPONSE" | jq -r '.value // empty')
[[ -n "$CLIENT_SECRET" ]] || die "Client '$CLIENT_ID' has no secret configured (is it a public client?)."

# ---- 6. get a token for the test user via the vidis-test client ----
log "Requesting token for user '$TEST_USERNAME' via '$CLIENT_ID'"
TOKEN_RESPONSE=$(curl_response POST "$KEYCLOAK_URL/realms/$REALM/protocol/openid-connect/token" 200 \
    -d grant_type=password --data-urlencode client_id="$CLIENT_ID" --data-urlencode client_secret="$CLIENT_SECRET" \
    --data-urlencode username="$TEST_USERNAME" --data-urlencode password="$TEST_PASSWORD")
TOKEN=$(echo "$TOKEN_RESPONSE" | jq -r '.access_token // empty')
[[ -n "$TOKEN" ]] || die "Could not obtain a user token. Check TEST_USERNAME/TEST_PASSWORD. Response: $TOKEN_RESPONSE"

# ---- 7. decode and print the claims ----
PAYLOAD=$(b64url_decode "$(echo "$TOKEN" | cut -d. -f2)")
echo "$PAYLOAD" | jq . || die "Access token payload is not valid JSON: $PAYLOAD"

log "Done. directAccessGrantsEnabled will be reset to its original value ($ORIGINAL_DIRECT_GRANTS) on exit."
