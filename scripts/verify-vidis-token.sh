#!/usr/bin/env bash
#
# Verifies the spsh-custom-oidc-api-mapper providers by pulling a real access
# token for the "vidis-test" client and printing its claims.
#
# See the "Verifying a token via curl" section in ../Readme.md for the
# manual, step-by-step version of what this script automates.
#
# Usage:
#   ./scripts/verify-vidis-token.sh [options]
#
# Precedence for every setting: CLI flag > environment variable > default.
#
# Options (each also configurable via the environment variable in parentheses):
#   --keycloak-url URL       (KEYCLOAK_URL)    default: http://localhost:8080
#   --realm REALM            (REALM)           default: SPSH
#   --client-id CLIENT_ID    (CLIENT_ID)       default: vidis-test
#   --admin-user USER        (ADMIN_USER)      default: admin
#   --admin-password PASS    (ADMIN_PASSWORD)  default: admin
#   --test-username USER     (TEST_USERNAME)   default: smueller (seeded with the VIDIS test Angebot, ssuperadmin has none)
#   --test-password PASS     (TEST_PASSWORD)   default: SPSHtest1!
#   -h, --help               show this help and exit
#
# Example for a dev/feature deployment:
#   ./scripts/verify-vidis-token.sh \
#     --keycloak-url "https://<namespace>-keycloak.<dev-domain>" \
#     --admin-password "<from 1Password>"

set -euo pipefail

# ---- 0. apply defaults for anything not already set in the environment ----
: "${KEYCLOAK_URL:=http://localhost:8080}"
: "${REALM:=SPSH}"
: "${CLIENT_ID:=vidis-test}"
: "${ADMIN_USER:=admin}"
: "${ADMIN_PASSWORD:=admin}"
: "${TEST_USERNAME:=hschuster}"
: "${TEST_PASSWORD:=SPSHtest1!}"

usage() {
    sed -n '2,/^set -euo pipefail/p' "$0" | sed '$d; s/^# \{0,1\}//'
}

# ---- 0b. CLI flags override env vars/defaults ----
while [[ $# -gt 0 ]]; do
    case "$1" in
        --keycloak-url) KEYCLOAK_URL="$2"; shift 2 ;;
        --realm) REALM="$2"; shift 2 ;;
        --client-id) CLIENT_ID="$2"; shift 2 ;;
        --admin-user) ADMIN_USER="$2"; shift 2 ;;
        --admin-password) ADMIN_PASSWORD="$2"; shift 2 ;;
        --test-username) TEST_USERNAME="$2"; shift 2 ;;
        --test-password) TEST_PASSWORD="$2"; shift 2 ;;
        -h|--help) usage; exit 0 ;;
        *) echo "Unknown option: $1 (use --help for usage)" >&2; exit 1 ;;
    esac
done

log() { printf '\n\033[1;34m==> %s\033[0m\n' "$1"; }
die() { printf '\033[1;31mError: %s\033[0m\n' "$1" >&2; exit 1; }

# ---- 1. prerequisites ----
for bin in curl jq base64; do
    command -v "$bin" >/dev/null 2>&1 || die "'$bin' is required but not installed."
done

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

# runs a curl call, aborts with a helpful message if the HTTP status is unexpected
curl_json() {
    local method="$1" url="$2" expected_status="$3"
    shift 3
    local response status body
    response=$(curl -sS -o /tmp/vidis-verify-body.$$ -w '%{http_code}' -X "$method" "$url" "$@") || die "curl request to $url failed to execute."
    status="$response"
    body=$(cat /tmp/vidis-verify-body.$$)
    rm -f /tmp/vidis-verify-body.$$
    if [[ "$status" != "$expected_status" ]]; then
        die "$method $url returned HTTP $status (expected $expected_status). Response: $body"
    fi
    printf '%s' "$body"
}

# ---- 2. get an admin token (master realm, admin-cli allows the password grant) ----
log "Fetching admin token"
ADMIN_TOKEN_RESPONSE=$(curl_json POST "$KEYCLOAK_URL/realms/master/protocol/openid-connect/token" 200 \
    -d grant_type=password -d client_id=admin-cli \
    -d username="$ADMIN_USER" -d password="$ADMIN_PASSWORD")
ADMIN_TOKEN=$(echo "$ADMIN_TOKEN_RESPONSE" | jq -r '.access_token // empty')
[[ -n "$ADMIN_TOKEN" ]] || die "Could not obtain an admin token. Check ADMIN_USER/ADMIN_PASSWORD/KEYCLOAK_URL. Response: $ADMIN_TOKEN_RESPONSE"

# ---- 3. look up the client and its current directAccessGrantsEnabled state ----
log "Looking up client '$CLIENT_ID' in realm '$REALM'"
CLIENTS_RESPONSE=$(curl_json GET "$KEYCLOAK_URL/admin/realms/$REALM/clients?clientId=$CLIENT_ID" 200 \
    -H "Authorization: Bearer $ADMIN_TOKEN")
CLIENT_UUID=$(echo "$CLIENTS_RESPONSE" | jq -r '.[0].id // empty')
[[ -n "$CLIENT_UUID" ]] || die "Client '$CLIENT_ID' not found in realm '$REALM'. Check CLIENT_ID/REALM."

CLIENT_JSON=$(curl_json GET "$KEYCLOAK_URL/admin/realms/$REALM/clients/$CLIENT_UUID" 200 \
    -H "Authorization: Bearer $ADMIN_TOKEN")
ORIGINAL_DIRECT_GRANTS=$(echo "$CLIENT_JSON" | jq -r '.directAccessGrantsEnabled')

# restore the client's original setting, even if a later step fails
cleanup() {
    if [[ "$ORIGINAL_DIRECT_GRANTS" == "false" ]]; then
        log "Restoring directAccessGrantsEnabled=false on '$CLIENT_ID'"
        curl -sS -o /dev/null -X PUT "$KEYCLOAK_URL/admin/realms/$REALM/clients/$CLIENT_UUID" \
            -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
            -d "$(echo "$CLIENT_JSON" | jq '.directAccessGrantsEnabled=false')" || true
    fi
}
trap cleanup EXIT

# ---- 4. temporarily enable the password grant (skip if already enabled) ----
if [[ "$ORIGINAL_DIRECT_GRANTS" == "true" ]]; then
    log "'$CLIENT_ID' already has directAccessGrantsEnabled=true, leaving it as-is"
else
    log "Temporarily enabling directAccessGrantsEnabled on '$CLIENT_ID'"
    curl_json PUT "$KEYCLOAK_URL/admin/realms/$REALM/clients/$CLIENT_UUID" 204 \
        -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
        -d "$(echo "$CLIENT_JSON" | jq '.directAccessGrantsEnabled=true')" >/dev/null
fi

# ---- 5. fetch the client secret ----
log "Fetching client secret"
SECRET_RESPONSE=$(curl_json GET "$KEYCLOAK_URL/admin/realms/$REALM/clients/$CLIENT_UUID/client-secret" 200 \
    -H "Authorization: Bearer $ADMIN_TOKEN")
VIDIS_SECRET=$(echo "$SECRET_RESPONSE" | jq -r '.value // empty')
[[ -n "$VIDIS_SECRET" ]] || die "Client '$CLIENT_ID' has no secret configured (is it a public client?)."

# ---- 6. get a token for the test user via the vidis-test client ----
log "Requesting token for user '$TEST_USERNAME' via '$CLIENT_ID'"
TOKEN_RESPONSE=$(curl_json POST "$KEYCLOAK_URL/realms/$REALM/protocol/openid-connect/token" 200 \
    -d grant_type=password -d client_id="$CLIENT_ID" -d client_secret="$VIDIS_SECRET" \
    -d username="$TEST_USERNAME" -d password="$TEST_PASSWORD")
TOKEN=$(echo "$TOKEN_RESPONSE" | jq -r '.access_token // empty')
[[ -n "$TOKEN" ]] || die "Could not obtain a user token. Check TEST_USERNAME/TEST_PASSWORD. Response: $TOKEN_RESPONSE"

# ---- 7. decode and print the claims ----
PAYLOAD=$(b64url_decode "$(echo "$TOKEN" | cut -d. -f2)")
echo "$PAYLOAD" | jq . || die "Access token payload is not valid JSON: $PAYLOAD"

log "Done. directAccessGrantsEnabled will be reset to its original value ($ORIGINAL_DIRECT_GRANTS) on exit."
