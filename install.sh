#!/usr/bin/env bash
# jcgraph one-shot install / update (macOS / Linux).
#
# Builds target/jcgraph.jar and tells you how to put `jcgraph` on PATH. Re-run
# anytime to update: it rebuilds the jar (and `git pull`s first if this is a
# git checkout). Idempotent.
#
#   ./install.sh
#   ./install.sh --java-home /path/to/jdk   # if no JDK is auto-detected
#   ./install.sh --skip-pull                # don't git pull
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

JAVA_HOME_ARG=""; SKIP_PULL=0
while [ $# -gt 0 ]; do
  case "$1" in
    --java-home) JAVA_HOME_ARG="${2:-}"; shift 2 ;;
    --skip-pull) SKIP_PULL=1; shift ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done
echo "[jcgraph] install/update from $ROOT"

# 1) refresh sources if this is a git checkout
if [ "$SKIP_PULL" = 0 ]; then
  if [ -d "$ROOT/.git" ] && command -v git >/dev/null 2>&1; then
    echo "[jcgraph] git pull --ff-only"; git -C "$ROOT" pull --ff-only
  else
    echo "[jcgraph] not a git checkout (or git missing); skipping pull"
  fi
fi

# 2) locate a JDK with javac
has_javac() { [ -n "${1:-}" ] && [ -x "$1/bin/javac" ]; }
JHOME="${JAVA_HOME_ARG:-${JAVA_HOME:-}}"
if ! has_javac "$JHOME"; then
  JC="$(command -v javac || true)"
  [ -n "$JC" ] && JHOME="$(cd "$(dirname "$JC")/.." && pwd)"
fi
has_javac "$JHOME" || { echo "No JDK (with javac) found. Use --java-home <jdk>." >&2; exit 1; }
echo "[jcgraph] JDK: $JHOME"

# 3) maven must be on PATH
command -v mvn >/dev/null 2>&1 || { echo "maven (mvn) not found on PATH" >&2; exit 1; }

# 4) build the shaded jar
echo "[jcgraph] mvn clean package -DskipTests ..."
JAVA_HOME="$JHOME" mvn -q clean package -DskipTests
JAR="$ROOT/target/jcgraph.jar"
[ -f "$JAR" ] || { echo "build produced no jar: $JAR" >&2; exit 1; }
chmod +x "$ROOT/jcgraph" 2>/dev/null || true
echo "[jcgraph] built $JAR"

echo ""
echo "[jcgraph] done. Add to PATH once (then open a new shell):"
echo "    echo 'export PATH=\"$ROOT:\$PATH\"' >> ~/.bashrc   # or ~/.zshrc"
echo "Then:  jcgraph index path/to/app.jar"
echo "[jcgraph] update later by re-running:  ./install.sh"
