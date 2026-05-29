#!/usr/bin/env bash
# jcgraph installer (macOS / Linux).
#
# DEFAULT — one-command remote install. Downloads the latest prebuilt,
# self-contained release (bundled JRE — no system Java needed) and puts
# `jcgraph` on your PATH:
#
#   curl -fsSL https://raw.githubusercontent.com/7-e1even/jcgraph/main/install.sh | bash
#
# CHECKOUT — run from a source tree (or pass --build) to build target/jcgraph.jar
# from source instead (needs a JDK with javac + Maven). This is the old behavior.
#
#   ./install.sh                    # in a checkout -> build; piped via curl -> download
#   ./install.sh --download         # force download of the latest release
#   ./install.sh --build            # force build from source (needs JDK + Maven)
#   ./install.sh --version v0.1.0   # download a specific release tag
#   ./install.sh --dir ~/.jcgraph   # where to install (download mode; default ~/.jcgraph)
#   ./install.sh --java-home <jdk>  # JDK for --build when none is auto-detected
#   ./install.sh --skip-pull        # --build: don't git pull first
#   ./install.sh --skip-path        # don't touch your shell rc
#
# Release-asset contract (download mode), for a release tagged <tag>:
#   asset name:  jcgraph-<ver>-<os>-<arch>.tar.gz   (ver = <tag> without a leading "v")
#   os   ∈ {macos, linux}      (uname -s, lowercased; darwin -> macos)
#   arch ∈ {arm64, x86_64}     (uname -m; aarch64 -> arm64, amd64 -> x86_64)
#   contents: a top-level dir holding a `jcgraph` launcher (+ bundled jre/, jcgraph.jar).
# This is exactly what `package.sh` emits — build the per-OS bundles on each
# target platform and attach them to the GitHub release. (Windows: use the
# PowerShell installer / windows-full bundle instead — this script is *nix only.)
set -eu
if (set -o pipefail) 2>/dev/null; then set -o pipefail; fi

REPO="${JCGRAPH_REPO:-7-e1even/jcgraph}"
INSTALL_DIR="${JCGRAPH_HOME:-$HOME/.jcgraph}"
MODE=auto
TAG=""
SKIP_PATH=0
SKIP_PULL=0
JAVA_HOME_ARG=""

while [ $# -gt 0 ]; do
  case "$1" in
    --download)  MODE=download; shift ;;
    --build)     MODE=build; shift ;;
    --version)   TAG="${2:-}"; shift 2 ;;
    --dir)       INSTALL_DIR="${2:-}"; shift 2 ;;
    --java-home) JAVA_HOME_ARG="${2:-}"; shift 2 ;;
    --skip-pull) SKIP_PULL=1; shift ;;
    --skip-path) SKIP_PATH=1; shift ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done

# The dir this script lives in — only meaningful when run from a checkout. When
# piped through curl there is no real file, so SELF stays empty and we download.
SELF=""
if [ -n "${BASH_SOURCE:-}" ] && [ -f "${BASH_SOURCE:-}" ] 2>/dev/null; then
  SELF="$(cd "$(dirname "${BASH_SOURCE}")" && pwd)"
elif [ -n "${BASH_SOURCE:-}" ]; then
  : # BASH_SOURCE set but not a real file (piped) -> leave SELF empty
fi
# BASH_SOURCE is an array in bash; the scalar expansion above grabs [0]. Guard
# again for the common case where we were executed as a normal file.
if [ -z "$SELF" ] && [ -n "${0:-}" ] && [ -f "$0" ]; then
  case "$0" in
    sh|bash|dash|-sh|-bash) : ;;            # piped to a shell, $0 is the shell name
    *) SELF="$(cd "$(dirname "$0")" && pwd)" ;;
  esac
fi

if [ "$MODE" = auto ]; then
  if [ -n "$SELF" ] && [ -f "$SELF/pom.xml" ]; then MODE=build; else MODE=download; fi
fi

# Append `dir` to PATH via the right shell rc, idempotently.
add_to_path() {
  dir="$1"
  if [ "$SKIP_PATH" = 1 ]; then
    echo "[jcgraph] --skip-path: add to PATH yourself:  export PATH=\"$dir:\$PATH\""
    return 0
  fi
  case "${SHELL:-}" in
    *zsh)  rc="$HOME/.zshrc" ;;
    *bash) if [ "$(uname -s)" = Darwin ]; then rc="$HOME/.bash_profile"; else rc="$HOME/.bashrc"; fi ;;
    *)     rc="$HOME/.profile" ;;
  esac
  line="export PATH=\"$dir:\$PATH\" # added by jcgraph"
  if [ -f "$rc" ] && grep -qF "# added by jcgraph" "$rc"; then
    echo "[jcgraph] $rc already has a jcgraph PATH entry"
  else
    printf '\n%s\n' "$line" >> "$rc"
    echo "[jcgraph] added $dir to PATH in $rc (open a NEW terminal to pick it up)"
  fi
}

build_from_source() {
  [ -n "$SELF" ] && [ -f "$SELF/pom.xml" ] || {
    echo "[jcgraph] --build needs a source checkout (no pom.xml found next to this script)." >&2
    echo "          Clone the repo and run ./install.sh from inside it, or drop --build to download." >&2
    exit 1
  }
  ROOT="$SELF"
  echo "[jcgraph] build/install from $ROOT"

  if [ "$SKIP_PULL" = 0 ] && [ -d "$ROOT/.git" ] && command -v git >/dev/null 2>&1; then
    echo "[jcgraph] git pull --ff-only"; git -C "$ROOT" pull --ff-only
  fi

  has_javac() { [ -n "${1:-}" ] && [ -x "$1/bin/javac" ]; }
  JHOME="${JAVA_HOME_ARG:-${JAVA_HOME:-}}"
  if ! has_javac "$JHOME"; then
    JC="$(command -v javac || true)"
    [ -n "$JC" ] && JHOME="$(cd "$(dirname "$JC")/.." && pwd)"
  fi
  if ! has_javac "$JHOME" && [ -x /usr/libexec/java_home ]; then
    CAND="$(/usr/libexec/java_home 2>/dev/null || true)"
    has_javac "$CAND" && JHOME="$CAND"
  fi
  if ! has_javac "$JHOME"; then
    for d in /Library/Java/JavaVirtualMachines/*/Contents/Home /usr/lib/jvm/*; do
      if has_javac "$d"; then JHOME="$d"; break; fi
    done
  fi
  has_javac "$JHOME" || { echo "No JDK (with javac) found. Use --java-home <jdk>." >&2; exit 1; }
  echo "[jcgraph] JDK: $JHOME"

  command -v mvn >/dev/null 2>&1 || { echo "maven (mvn) not found on PATH" >&2; exit 1; }
  echo "[jcgraph] mvn clean package -DskipTests ..."
  JAVA_HOME="$JHOME" mvn -q clean package -DskipTests
  JAR="$ROOT/target/jcgraph.jar"
  [ -f "$JAR" ] || { echo "build produced no jar: $JAR" >&2; exit 1; }
  chmod +x "$ROOT/jcgraph" 2>/dev/null || true
  echo "[jcgraph] built $JAR"
  add_to_path "$ROOT"
  [ -x "$ROOT/jre/bin/java" ] || echo "[jcgraph] note: no bundled jre/ here; the launcher uses system java"
  echo ""
  echo "[jcgraph] done. Try:  jcgraph index path/to/app.jar"
  echo "[jcgraph] update later by re-running:  ./install.sh --build"
}

download_release() {
  command -v curl >/dev/null 2>&1 || { echo "curl is required for the download install" >&2; exit 1; }
  command -v tar  >/dev/null 2>&1 || { echo "tar is required for the download install"  >&2; exit 1; }

  uos="$(uname -s | tr '[:upper:]' '[:lower:]')"
  [ "$uos" = darwin ] && uos=macos
  case "$uos" in
    macos|linux) : ;;
    *) echo "[jcgraph] unsupported OS '$uos' — this installer is macOS/Linux only (use the Windows installer on Windows)." >&2; exit 1 ;;
  esac
  uarch="$(uname -m)"
  case "$uarch" in
    arm64|aarch64) uarch=arm64 ;;
    x86_64|amd64)  uarch=x86_64 ;;
    *) echo "[jcgraph] unsupported arch '$uarch'." >&2; exit 1 ;;
  esac

  if [ -z "$TAG" ]; then
    echo "[jcgraph] resolving latest release of $REPO ..."
    # Capture without letting curl's non-zero (e.g. 404 = no releases) abort the
    # script under `set -e`/pipefail, so the actionable message below is reached.
    api="$(curl -fsSL "https://api.github.com/repos/$REPO/releases/latest" 2>/dev/null || true)"
    TAG="$(printf '%s' "$api" | grep '"tag_name"' | head -1 \
            | sed -E 's/.*"tag_name"[[:space:]]*:[[:space:]]*"([^"]+)".*/\1/' || true)"
    if [ -z "$TAG" ]; then
      name="${REPO##*/}"
      echo "[jcgraph] $REPO has no published release yet (GitHub returned no 'latest')." >&2
      echo "          Fix it one of two ways:" >&2
      echo "          1) Publish a release (tag like v0.1.0) with the platform bundle attached." >&2
      echo "          2) Build from source on this machine (needs a JDK + Maven):" >&2
      echo "               git clone https://github.com/$REPO && cd $name && ./install.sh --build" >&2
      exit 1
    fi
  fi
  ver="${TAG#v}"
  asset="jcgraph-${ver}-${uos}-${uarch}.tar.gz"
  url="https://github.com/$REPO/releases/download/$TAG/$asset"

  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT
  echo "[jcgraph] downloading $asset ($TAG) ..."
  curl -fSL "$url" -o "$tmp/jcgraph.tar.gz" || {
    echo "[jcgraph] download failed: $url" >&2
    echo "          Check the release '$TAG' has an asset named '$asset'." >&2
    echo "          (Build it on this platform with ./package.sh, then attach it to the release.)" >&2
    exit 1
  }

  tar -xzf "$tmp/jcgraph.tar.gz" -C "$tmp"
  # The tarball holds a single top dir (jcgraph-<ver>-<os>-<arch>/). Find the
  # one that actually contains the launcher.
  src=""
  for d in "$tmp"/*/; do
    if [ -f "${d}jcgraph" ]; then src="${d%/}"; break; fi
  done
  [ -n "$src" ] || { echo "[jcgraph] extracted archive has no 'jcgraph' launcher." >&2; exit 1; }

  echo "[jcgraph] installing to $INSTALL_DIR"
  rm -rf "$INSTALL_DIR"
  mkdir -p "$INSTALL_DIR"
  cp -R "$src"/. "$INSTALL_DIR"/
  chmod +x "$INSTALL_DIR/jcgraph" 2>/dev/null || true
  [ -f "$INSTALL_DIR/jcgraph" ] || { echo "[jcgraph] install dir has no 'jcgraph' launcher after copy." >&2; exit 1; }

  add_to_path "$INSTALL_DIR"
  echo ""
  echo "[jcgraph] done — $TAG installed. Try:  jcgraph index path/to/app.jar"
  echo "[jcgraph] update later by re-running this installer."
}

case "$MODE" in
  build)    build_from_source ;;
  download) download_release ;;
  *) echo "internal: bad mode '$MODE'" >&2; exit 2 ;;
esac
