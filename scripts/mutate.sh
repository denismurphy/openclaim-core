#!/usr/bin/env bash
# Mutation check: apply a perl substitution to a source file, run the named tests, report whether the mutant
# was killed, and always restore the original (touching it so Maven recompiles; see CONTRIBUTING.md).
# Usage: scripts/mutate.sh <file> <perl-substitution> <test-selector> [extra maven args...]
set -euo pipefail
file=$1 expr=$2 tests=$3
shift 3
run_tests() {
  if [[ $tests == *IT || $tests == *IT\** ]]; then
    # Failsafe integration tests: skip unit tests, run only the selected ITs.
    ./mvnw -B -q verify -Dspotless.check.skip=true -Djacoco.skip=true -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false \
      -Dit.test="$tests" -Dfailsafe.failIfNoSpecifiedTests=false "$@" >/dev/null 2>&1
  else
    ./mvnw -B -q test -Dspotless.check.skip=true -Dtest="$tests" -Dsurefire.failIfNoSpecifiedTests=false "$@" >/dev/null 2>&1
  fi
}
# A mutant "killed" by a test that was already failing proves nothing, so insist on a green baseline first.
if ! run_tests "$@"; then
  echo "BASELINE RED: $tests fail without the mutation; fix them first" >&2
  exit 3
fi
backup=$(mktemp)
cp "$file" "$backup"
trap 'cp "$backup" "$file"; touch "$file"; rm -f "$backup"' EXIT
perl -0pi -e "$expr" "$file"
if cmp -s "$file" "$backup"; then
  echo "NOT APPLIED: substitution matched nothing in $file" >&2
  exit 2
fi
# A mutant that does not compile would "fail" every test; that is not a kill.
if ! ./mvnw -B -q test-compile -Dspotless.check.skip=true "$@" >/dev/null 2>&1; then
  echo "INVALID: the mutant does not compile" >&2
  exit 4
fi
if run_tests "$@"; then
  echo "SURVIVED: tests still pass with the mutation"
  exit 1
fi
echo "KILLED"
