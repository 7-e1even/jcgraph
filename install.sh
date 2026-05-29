#!/usr/bin/env bash
# jcgraph one-shot install / update (macOS / Linux).
#
# Builds target/jcgraph.jar and puts `jcgraph` on your PATH (via the right shell
# rc) so the command works from any directory. Re-run anytime to update: it
# rebuilds the jar (and `git pull`s first if this is a git checkout). Idempotent.
#
#   ./install.sh
#   ./install.sh --java-home /path/to/jdk   # if no JDK is auto-detected
#   ./install.sh --skip-pull                # don't git pull
#   ./install.sh --skip-path                # build only, leave shell rc alone
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

JAVA_HOME_ARG=""; SKIP_PULL=0; SKIP_PATH=0
while [ $# -gt 0 ]; do
  case "$1" in
    --java-home) JAVA_HOME_ARG="${2:-}"; shift 2 ;;
    --skip-pull) SKIP_PULL=1; shift ;;
    --skip-path) SKIP_PATH=1; shift ;;
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

# 2) locate a JDK with javac (a JRE is not enough to build)
has_javac() { [ -n "${1:-}" ] && [ -x "$1/bin/javac" ]; }
JHOME="${JAVA_HOME_ARG:-${JAVA_HOME:-}}"
# 2a) javac already on PATH
if ! has_javac "$JHOME"; then
  JC="$(command -v javac || true)"
  [ -n "$JC" ] && JHOME="$(cd "$(dirname "$JC")/.." && pwd)"
fi
# 2b) macOS: ask the system which JDK is default
if ! has_javac "$JHOME" && [ -x /usr/libexec/java_home ]; then
  CAND="$(/usr/libexec/java_home 2>/dev/null || true)"
  has_javac "$CAND" && JHOME="$CAND"
fi
# 2c) scan the standard JVM locations (macOS + Linux)
if ! has_javac "$JHOME"; then
  for d in /Library/Java/JavaVirtualMachines/*/Contents/Home /usr/lib/jvm/*; do
    if has_javac "$d"; then JHOME="$d"; break; fi
  done
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

# 5) put this folder on PATH via the right shell rc, idempotently
if [ "$SKIP_PATH" = 0 ]; then
  case "${SHELL:-}" in
    *zsh)  RC="$HOME/.zshrc" ;;
    *bash) [ "$(uname -s)" = Darwin ] && RC="$HOME/.bash_profile" || RC="$HOME/.bashrc" ;;
    *)     RC="$HOME/.profile" ;;
  esac
  LINE="export PATH=\"$ROOT:\$PATH\" # added by jcgraph"
  if [ -f "$RC" ] && grep -qF "# added by jcgraph" "$RC"; then
    echo "[jcgraph] $RC already has a jcgraph PATH entry"
  else
    printf '\n%s\n' "$LINE" >> "$RC"
    echo "[jcgraph] added $ROOT to PATH in $RC (open a NEW terminal to pick it up)"
  fi
else
  echo "[jcgraph] --skip-path: add to PATH yourself:  export PATH=\"$ROOT:\$PATH\""
fi

# 6) note about the runtime the launcher will use
[ -x "$ROOT/jre/bin/java" ] || echo "[jcgraph] note: no bundled jre/ here; the launcher uses system java"

echo ""
echo "[jcgraph] done. Try:  jcgraph index path/to/app.jar"
echo "[jcgraph] update later by re-running:  ./install.sh"
