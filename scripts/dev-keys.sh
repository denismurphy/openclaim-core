#!/usr/bin/env bash
# Generates the local RSA key pair used to sign development access tokens (see scripts/dev-token.sh).
# Keys live in .dev-keys/, which is git-ignored; never use them outside a developer machine.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p .dev-keys
chmod 700 .dev-keys
if [[ -f .dev-keys/private.pem ]]; then
  echo ".dev-keys/private.pem already exists; delete it to regenerate" >&2
  exit 0
fi
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out .dev-keys/private.pem 2>/dev/null
chmod 600 .dev-keys/private.pem
openssl pkey -in .dev-keys/private.pem -pubout -out .dev-keys/public.pem
echo "Wrote .dev-keys/private.pem and .dev-keys/public.pem"
