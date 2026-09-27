#!/usr/bin/env bash
# The fast inner loop: named test classes, the cross-module guards, one acceptance phase, formatting.
# Tier 3 (the full `verify`, every module and every acceptance phase, both operating systems) is CI's job.
#
#   scripts/fast.sh test <module> <TestClass[,TestClass...]>   compile (Error Prone, -Werror) + those classes
#   scripts/fast.sh guards                                      the tests that catch cross-module breaks
#   scripts/fast.sh phase <N> [TestClass]                       one acceptance phase against the shaded jar
#   scripts/fast.sh fmt                                         google-java-format (spotless:apply)
#
# Modules: core, jrs, ops, app. Sibling modules are built from source through -am, never installed.
# Flags fixed here on purpose: no Jacoco (a partial run cannot meet the coverage floor), no Spotless
# check (the pre-commit hook and CI's format gate own it), offline first (retried online once when
# Maven says it needs the network), and a filtered report: the full log goes to $FAST_LOG.
set -euo pipefail
cd "$(dirname "$0")/.."

FAST_LOG="${FAST_LOG:-${TMPDIR:-/tmp}/jrsctl-fast.log}"
COMMON=(-Djacoco.skip=true -Dspotless.check.skip=true -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoTests=false)

# The tests that break when a change is right locally and wrong across modules: the architecture rule
# (Phase0SkeletonTest), every Step's idempotency and compensation, every command's help example, and
# the JSON documents against their schemas. Keep this list in step with CLAUDE.md.
GUARDS='IdempotencyCoverageTest,HelpExamplesTest,JsonOutputSchemaTest,Phase0SkeletonTest'

usage() {
  sed -n '2,13p' "$0" | sed 's/^# \{0,1\}//'
  exit 1
}

# run <maven args...>: offline first; if Maven wants the network, once more online.
run() {
  local start end rc=0
  start=$(date +%s)
  if [ -z "${FAST_ONLINE:-}" ]; then
    bash scripts/mvn.sh -o "$@" >"$FAST_LOG" 2>&1 || rc=$?
    if [ "$rc" -ne 0 ] && grep -qiE "offline mode|Cannot access .* in offline|Could not resolve dependencies" "$FAST_LOG"; then
      echo "offline resolution failed; retrying online" >&2
      rc=0
      bash scripts/mvn.sh "$@" >"$FAST_LOG" 2>&1 || rc=$?
    fi
  else
    bash scripts/mvn.sh "$@" >"$FAST_LOG" 2>&1 || rc=$?
  fi
  end=$(date +%s)
  # a pattern that matches no class passes silently under failIfNoSpecifiedTests=false: refuse that
  local ran
  ran=$(grep -E "Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+$" "$FAST_LOG" | tail -1 | sed -E 's/.*Tests run: ([0-9]+),.*/\1/' || true)
  if [ "$rc" -eq 0 ] && [ "${ran:-0}" -eq 0 ]; then
    echo "no test ran: the class name matched nothing in the modules built" >&2
    rc=1
  fi
  # what the caller needs: the verdict, the failing tests, and the first compiler errors
  grep -E "^\[(ERROR|WARNING)\] +(.*\.java|Tests run:.*(FAIL|ERROR)|.*Test\.[A-Za-z_]+:[0-9]+)|Tests run:.*Fail.*[1-9]|BUILD (SUCCESS|FAILURE)|COMPILATION ERROR|error:" "$FAST_LOG" \
    | grep -vE "^\[INFO\]" | head -40 || true
  grep -E "Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+$" "$FAST_LOG" | tail -1 || true
  echo "$([ "$rc" -eq 0 ] && echo PASS || echo FAIL) in $((end - start))s (full log: $FAST_LOG)"
  return "$rc"
}

[ $# -ge 1 ] || usage
cmd="$1"
shift
case "$cmd" in
  test)
    [ $# -eq 2 ] || usage
    case "$1" in core | jrs | ops | app) ;; *) echo "module must be core, jrs, ops or app" >&2; exit 1 ;; esac
    run -pl "$1" -am test -Dtest="$2" "${COMMON[@]}"
    ;;
  guards)
    # verify, not test: the acceptance guard runs against the shaded jar, which is built at package
    run -pl acceptance -am verify -Dtest="$GUARDS" "${COMMON[@]}"
    ;;
  phase)
    [ $# -ge 1 ] || usage
    case "$1" in '' | *[!0-9]*) echo "phase must be a number" >&2; exit 1 ;; esac
    if [ $# -ge 2 ]; then
      run -pl acceptance -am verify -Dphase="$1" -Dtest="$2" "${COMMON[@]}"
    else
      # every other module's tests are excluded by naming this phase's classes only
      run -pl acceptance -am verify -Dphase="$1" -Dtest="Phase${1}*Test" "${COMMON[@]}"
    fi
    ;;
  fmt)
    bash scripts/mvn.sh -q spotless:apply
    echo "formatted"
    ;;
  *) usage ;;
esac
