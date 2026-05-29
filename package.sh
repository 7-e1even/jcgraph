#!/usr/bin/env bash
# Package jcgraph into a self-contained *nix bundle (macOS / Linux).
# MUST run on the target OS+arch: it bundles that platform's Java runtime via jlink.
#
#   ./package.sh             # self-contained bundle (jlink minimal runtime) -> .tar.gz
#   ./package.sh --system    # also emit a no-JRE tarball (needs system Java; any OS)
#   ./package.sh --build     # run `mvn clean package` first
#
# Requires a JDK 11+ on PATH or via JAVA_HOME (for jlink/jdeps). The jcgraph jar
# targets Java 8 but runs fine on the newer runtime jlink produces.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="$ROOT/target/jcgraph.jar"

BUILD=0; SYSTEM=0
for a in "$@"; do
  case "$a" in
    --build)  BUILD=1 ;;
    --system) SYSTEM=1 ;;
    *) echo "unknown arg: $a" >&2; exit 2 ;;
  esac
done

VER="$(sed -n 's:.*<version>\(.*\)</version>.*:\1:p' "$ROOT/pom.xml" | head -1)"
[ -n "$VER" ] || VER="0.0.0"
OS="$(uname -s | tr '[:upper:]' '[:lower:]')"   # darwin / linux
[ "$OS" = darwin ] && OS=macos
ARCH="$(uname -m)"                               # arm64 / x86_64
echo "[package] version=$VER os=$OS arch=$ARCH"

if [ "$BUILD" = 1 ] || [ ! -f "$JAR" ]; then
  echo "[package] mvn clean package..."
  mvn -q clean package -DskipTests
fi
[ -f "$JAR" ] || { echo "jar not found: $JAR" >&2; exit 1; }

# Locate a JDK (for jlink + jdeps).
JHOME="${JAVA_HOME:-}"
if [ -z "$JHOME" ]; then
  JBIN="$(command -v java || true)"
  [ -n "$JBIN" ] && JHOME="$(cd "$(dirname "$JBIN")/.." && pwd)"
fi
[ -x "$JHOME/bin/jlink" ] || { echo "jlink not found; set JAVA_HOME to a JDK 11+ (got '$JHOME')" >&2; exit 1; }

DIST="$ROOT/dist"; mkdir -p "$DIST"

make_launcher() {  # $1 = target dir
  cat > "$1/jcgraph" <<'EOF'
#!/usr/bin/env bash
set -e
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ -x "$DIR/jre/bin/java" ]; then
  exec "$DIR/jre/bin/java" -jar "$DIR/jcgraph.jar" "$@"
else
  exec java -jar "$DIR/jcgraph.jar" "$@"
fi
EOF
  chmod +x "$1/jcgraph"
}

# --- self-contained variant (jlink runtime) ---
NAME="jcgraph-$VER-$OS-$ARCH"
VD="$DIST/$NAME"
rm -rf "$VD"; mkdir -p "$VD"
cp "$JAR" "$VD/jcgraph.jar"
[ -f "$ROOT/README.md" ] && cp "$ROOT/README.md" "$VD/"
make_launcher "$VD"

# Compute the exact module set from the jar. Override with MODS=... if needed.
MODS="${MODS:-$("$JHOME/bin/jdeps" --print-module-deps --ignore-missing-deps --multi-release base "$JAR")}"
if [ -z "$MODS" ]; then
  echo "jdeps produced no module set for $JAR." >&2
  echo "Inspect with: '$JHOME/bin/jdeps' --print-module-deps '$JAR', then re-run as MODS=<list> ./package.sh" >&2
  exit 1
fi
# --compress=2 was deprecated for removal in JDK 21 (replaced by --compress=zip-N).
# Pick a flag valid for the running jlink so this stays correct on JDK 11..25+.
JL_MAJOR="$("$JHOME/bin/jlink" --version 2>/dev/null | sed -E 's/^([0-9]+).*/\1/')"
if [ -n "$JL_MAJOR" ] && [ "$JL_MAJOR" -ge 21 ] 2>/dev/null; then
  COMPRESS="--compress=zip-6"
else
  COMPRESS="--compress=2"
fi
echo "[package] jlink modules: $MODS ($COMPRESS)"
"$JHOME/bin/jlink" --add-modules "$MODS" \
  --strip-debug --no-header-files --no-man-pages $COMPRESS \
  --output "$VD/jre"

tar -C "$DIST" -czf "$DIST/$NAME.tar.gz" "$NAME"
echo "[package] -> dist/$NAME.tar.gz"

# --- system variant (no JRE; cross-platform) ---
if [ "$SYSTEM" = 1 ]; then
  SNAME="jcgraph-$VER-system"
  SD="$DIST/$SNAME"
  rm -rf "$SD"; mkdir -p "$SD"
  cp "$JAR" "$SD/jcgraph.jar"
  [ -f "$ROOT/README.md" ] && cp "$ROOT/README.md" "$SD/"
  make_launcher "$SD"
  tar -C "$DIST" -czf "$DIST/$SNAME.tar.gz" "$SNAME"
  echo "[package] -> dist/$SNAME.tar.gz"
fi

echo "[package] done."
ls -la "$DIST"/*.tar.gz
