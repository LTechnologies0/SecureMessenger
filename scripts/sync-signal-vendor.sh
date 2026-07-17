#!/usr/bin/env bash
# Sync libsignal-service and its JVM core deps from Signal-Android (AGPL-3.0).
# libsignal-client + libsignal-android come from Signal's Maven repo at build time.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VENDOR="$ROOT/vendor/signal"
REF="${SIGNAL_ANDROID_REF:-main}"
TMP="${TMPDIR:-/tmp}/signal-android-sync-$$"

cleanup() { rm -rf "$TMP"; }
trap cleanup EXIT

mkdir -p "$VENDOR"

if [[ -f "$VENDOR/VERSION" ]] && [[ "$(cat "$VENDOR/VERSION")" == "$REF" ]] \
    && [[ -d "$VENDOR/libsignal-service/src" ]]; then
  echo "Signal vendor already synced at $REF"
  exit 0
fi

echo "Fetching signalapp/Signal-Android @ $REF ..."
git clone --depth 1 --branch "$REF" https://github.com/signalapp/Signal-Android.git "$TMP" 2>/dev/null \
  || git clone --depth 1 https://github.com/signalapp/Signal-Android.git "$TMP"

copy_module() {
  local src_path="$1"
  local dest_name="$2"
  rm -rf "$VENDOR/$dest_name"
  mkdir -p "$VENDOR/$dest_name"
  cp -a "$TMP/$src_path/." "$VENDOR/$dest_name/"
  # Drop upstream Gradle files — we ship our own build.gradle.kts per module.
  rm -f "$VENDOR/$dest_name/build.gradle.kts"
}

copy_module "core/util-jvm" "util-jvm"
copy_module "core/models-jvm" "models-jvm"
copy_module "core/network" "network-jvm"
copy_module "lib/libsignal-service" "libsignal-service"

mkdir -p "$VENDOR/wire-handler"
cp "$TMP/wire-handler/wire-handler-1.0.0.jar" "$VENDOR/wire-handler/"

mkdir -p "$ROOT/protocol/signal/src/main/res/raw"
if [[ -f "$TMP/app/src/main/res/raw/whisper.store" ]]; then
  cp "$TMP/app/src/main/res/raw/whisper.store" "$ROOT/protocol/signal/src/main/res/raw/whisper"
elif [[ -f "$TMP/app/src/main/res/raw/whisper" ]]; then
  cp "$TMP/app/src/main/res/raw/whisper" "$ROOT/protocol/signal/src/main/res/raw/whisper"
fi

# Tor SOCKS5: prefer OkHttp SOCKS proxy over Signal TLS circumvention proxy.
for PATCH in push-socket-socks signal-url-socks websocket-socks; do
  PATCH_FILE="$ROOT/vendor/signal/patches/${PATCH}.patch"
  if [[ -f "$PATCH_FILE" ]]; then
    (cd "$VENDOR/libsignal-service" && patch -p1 -N < "$PATCH_FILE" || true)
  fi
done

echo "$REF" > "$VENDOR/VERSION"
echo "Synced Signal vendor modules to $VENDOR (ref=$REF)"
