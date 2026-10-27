#!/usr/bin/env bash

: "${KEYCLOAK_URL:=http://localhost:8080}"
: "${REALM:=SPSH}"
: "${ADMIN_USER:=admin}"
: "${ADMIN_PASSWORD:=admin}"
: "${TEST_USERNAME:=hschuster}"
: "${TEST_PASSWORD:=SPSHtest1!}"
: "${SAML_ACS_URL:=http://localhost:8765/saml}"

log() { printf '\n==> %s\n' "$1" >&2; }
die() { printf 'Error: %s\n' "$1" >&2; exit 1; }

usage() {
    printf 'Usage: %s [options]\n\n' "$(basename "$0")"
    printf '%s\n' \
        'Precedence: CLI flag > environment variable > default.' \
        '' \
        '  --keycloak-url URL    (KEYCLOAK_URL)    default: http://localhost:8080' \
        '  --realm REALM         (REALM)           default: SPSH' \
        "  --client-id ID        (CLIENT_ID)       default: $DEFAULT_CLIENT_ID" \
        '  --test-username USER  (TEST_USERNAME)   default: hschuster (Lehrkraft)' \
        '  --test-password PASS  (TEST_PASSWORD)   default: SPSHtest1!'
    if [[ "$VERIFY_PROTOCOL" == "oidc" ]]; then
        printf '%s\n' \
            '  --admin-user USER     (ADMIN_USER)      default: admin' \
            '  --admin-password PASS (ADMIN_PASSWORD)  default: admin'
    else
        printf '%s\n' \
            '  --acs-url URL         (SAML_ACS_URL)    default: http://localhost:8765/saml'
    fi
    printf '%s\n' '  -h, --help            show this help and exit'
}

parse_options() {
    while [[ $# -gt 0 ]]; do
        case "$1" in
            -h|--help) usage; exit 0 ;;
            --keycloak-url|--realm|--client-id|--test-username|--test-password|--admin-user|--admin-password|--acs-url)
                [[ $# -ge 2 && -n "$2" ]] || die "Missing value for $1"
                case "$1" in
                    --keycloak-url) KEYCLOAK_URL="$2" ;;
                    --realm) REALM="$2" ;;
                    --client-id) CLIENT_ID="$2" ;;
                    --test-username) TEST_USERNAME="$2" ;;
                    --test-password) TEST_PASSWORD="$2" ;;
                    --admin-user|--admin-password)
                        [[ "$VERIFY_PROTOCOL" == "oidc" ]] || die "$1 is only used by verify-oidc-token.sh"
                        if [[ "$1" == "--admin-user" ]]; then ADMIN_USER="$2"; else ADMIN_PASSWORD="$2"; fi
                        ;;
                    --acs-url)
                        [[ "$VERIFY_PROTOCOL" == "saml" ]] || die "$1 is only used by verify-saml-token.sh"
                        SAML_ACS_URL="$2"
                        ;;
                esac
                shift 2
                ;;
            *) die "Unknown option: $1 (use --help for usage)" ;;
        esac
    done
    KEYCLOAK_URL="${KEYCLOAK_URL%/}"
}

require_commands() {
    local binary
    for binary in "$@"; do
        command -v "$binary" >/dev/null 2>&1 || die "'$binary' is required but not installed."
    done
}

curl_response() {
    local method="$1" url="$2" expected_status="$3"
    shift 3
    local response http_status body
    response=$(curl -sS --connect-timeout 10 --max-time 60 -w $'\n%{http_code}' \
        -X "$method" "$url" "$@") || {
        printf 'Error: curl request to %s failed to execute.\n' "$url" >&2
        return 1
    }
    http_status="${response##*$'\n'}"
    body="${response%$'\n'*}"
    if [[ "$http_status" != "$expected_status" ]]; then
        printf 'Error: %s %s returned HTTP %s (expected %s). Response: %s\n' \
            "$method" "$url" "$http_status" "$expected_status" "$body" >&2
        return 1
    fi
    printf '%s' "$body"
}