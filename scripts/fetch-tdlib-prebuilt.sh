#!/usr/bin/env bash
# Download prebuilt TDLib AAR (Java bindings + libtdjni.so for all ABIs).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LIBS_DIR="$ROOT/protocol/telegram/libs"
VERSION="${TDLIB_ANDROID_VERSION:-0.1.0}"
URL="https://github.com/AkashPriyadarshii/tdlib-android/releases/download/v${VERSION}/core-release.aar"
OUT="$LIBS_DIR/tdlib-core-${VERSION}.aar"

mkdir -p "$LIBS_DIR"

if [[ -f "$OUT" ]]; then
  echo "Already present: $OUT"
  exit 0
fi

echo "Downloading TDLib core AAR v${VERSION}..."
curl -fL --retry 3 -o "$OUT" "$URL"
echo "Saved to $OUT"
echo "Rebuild SecureMessenger: ./gradlew :app:assembleDebug"
