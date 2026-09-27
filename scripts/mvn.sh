#!/usr/bin/env bash
# Runs Maven with the JDK 21 this project requires. Usage: scripts/mvn.sh verify
set -euo pipefail
cd "$(dirname "$0")/.."
# Wire the repository's git hooks once (staged-files Spotless check on commit, .githooks/):
# quiet, idempotent, never overrides a path the developer set themselves. Only in a clone: an
# unpacked source archive has no .git, and git would otherwise write to an enclosing repository.
if [ -e .git ] && command -v git >/dev/null 2>&1 && [ -z "$(git config --get core.hooksPath 2>/dev/null || true)" ]; then
  git config core.hooksPath .githooks 2>/dev/null || true
fi
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*)
    exec cmd.exe //c "scripts\\mvn.cmd $*"
    ;;
esac
: "${JRSCTL_JDK:=${JAVA_HOME:-}}"
if [ -z "$JRSCTL_JDK" ] || [ ! -x "$JRSCTL_JDK/bin/java" ]; then
  echo "Set JRSCTL_JDK (or JAVA_HOME) to a JDK 21 home." >&2
  exit 1
fi
export JAVA_HOME="$JRSCTL_JDK"
export PATH="$JAVA_HOME/bin:$PATH"
# Through sh, not as a program: a source ZIP (GitHub's "Download ZIP") drops the executable bit.
if [ -f mvnw ]; then
  exec sh mvnw -B "$@"
else
  exec mvn -B "$@"
fi
