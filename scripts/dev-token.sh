#!/usr/bin/env bash
# Mints a 1-hour RS256 access token for the dev profile.
# Usage: scripts/dev-token.sh "<space-separated scopes>" [payer_code]
#   scripts/dev-token.sh "claims:write claims:read"             # EHR
#   scripts/dev-token.sh "remittance:write" ACME-HEALTH         # payer
#   scripts/dev-token.sh "payments:write"                       # billing office
set -euo pipefail
cd "$(dirname "$0")/.."
scopes=${1:?usage: dev-token.sh "<scopes>" [payer_code]}
payer=${2:-}
key=.dev-keys/private.pem
[[ -f $key ]] || { echo "Run scripts/dev-keys.sh first" >&2; exit 1; }

b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }
json_escape() { printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'; }

now=$(date +%s)
header='{"alg":"RS256","typ":"JWT"}'
payload="{\"iss\":\"https://dev.openclaim.local\",\"aud\":\"openclaim-core\",\"sub\":\"dev\",\"iat\":$now,\"exp\":$((now + 3600)),\"scope\":\"$(json_escape "$scopes")\""
[[ -n $payer ]] && payload+=",\"payer_code\":\"$(json_escape "$payer")\""
payload+="}"

unsigned="$(printf '%s' "$header" | b64url).$(printf '%s' "$payload" | b64url)"
signature=$(printf '%s' "$unsigned" | openssl dgst -sha256 -sign "$key" -binary | b64url)
printf '%s.%s\n' "$unsigned" "$signature"
