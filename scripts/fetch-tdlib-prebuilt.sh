#!/usr/bin/env bash
# Download prebuilt TDLib AAR (Java bindings + libtdjni.so for all ABIs).
# Primary host: SecureMessenger GitHub Releases (upstream AkashPriyadarshii/tdlib-android gone).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LIBS_DIR="$ROOT/protocol/telegram/libs"
VERSION="${TDLIB_ANDROID_VERSION:-0.1.0}"
OUT="$LIBS_DIR/tdlib-core-${VERSION}.aar"

# Override with TDLIB_AAR_URL for mirrors / CI caches.
DEFAULT_URL="https://github.com/LTechnologies0/SecureMessenger/releases/download/v1.0.16/tdlib-core-${VERSION}.aar"
URL="${TDLIB_AAR_URL:-$DEFAULT_URL}"

mkdir -p "$LIBS_DIR"

if [[ -f "$OUT" ]]; then
  echo "Already present: $OUT"
  exit 0
fi

echo "Downloading TDLib core AAR v${VERSION}..."
echo "  from $URL"
curl -fL --retry 3 -o "$OUT.tmp" "$URL"
mv "$OUT.tmp" "$OUT"
echo "Saved to $OUT"
echo "Rebuild SecureMessenger: ./gradlew :app:assembleDebug"
