#!/usr/bin/env bash
# Mutation check: apply a perl substitution to a source file, run the named tests, report whether the mutant
# was killed, and always restore the original (touching it so Maven recompiles; see CONTRIBUTING.md).
# Usage: scripts/mutate.sh <file> <perl-substitution> <test-selector> [extra maven args...]
set -euo pipefail
file=$1 expr=$2 tests=$3
shift 3
backup=$(mktemp)
cp "$file" "$backup"
trap 'cp "$backup" "$file"; touch "$file"; rm -f "$backup"' EXIT
perl -0pi -e "$expr" "$file"
if cmp -s "$file" "$backup"; then
  echo "NOT APPLIED: substitution matched nothing in $file" >&2
  exit 2
fi
if ./mvnw -B -q test -Dspotless.check.skip=true -Dtest="$tests" -Dsurefire.failIfNoSpecifiedTests=false "$@" >/dev/null 2>&1; then
  echo "SURVIVED: tests still pass with the mutation"
  exit 1
fi
echo "KILLED"
