#!/usr/bin/env bash
# Sync libsignal-service and its JVM core deps from Signal-Android (AGPL-3.0).
# Keeps our vendored build.gradle.kts / patches / wire-handler; only refreshes sources.
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

# Copy upstream sources into an existing module dir without wiping our Gradle wrappers.
sync_sources() {
  local src_path="$1"
  local dest_name="$2"
  local dest="$VENDOR/$dest_name"
  mkdir -p "$dest"
  # Remove previous synced trees only (keep build.gradle.kts, .gitignore, lint.xml, etc.).
  rm -rf "$dest/src" "$dest/consumer-rules.pro" "$dest/proguard-rules.pro"
  if [[ -d "$TMP/$src_path/src" ]]; then
    cp -a "$TMP/$src_path/src" "$dest/"
  else
    echo "ERROR: missing $TMP/$src_path/src" >&2
    exit 1
  fi
  # Optional non-Gradle assets from upstream module root.
  for f in consumer-rules.pro proguard-rules.pro; do
    if [[ -f "$TMP/$src_path/$f" ]]; then
      cp -a "$TMP/$src_path/$f" "$dest/"
    fi
  done
  if [[ ! -f "$dest/build.gradle.kts" ]]; then
    echo "ERROR: $dest/build.gradle.kts missing — commit SecureMessenger vendor Gradle files first." >&2
    exit 1
  fi
}

sync_sources "core/util-jvm" "util-jvm"
sync_sources "core/models-jvm" "models-jvm"
sync_sources "core/network" "network-jvm"
sync_sources "lib/libsignal-service" "libsignal-service"

mkdir -p "$VENDOR/wire-handler"
cp "$TMP/wire-handler/wire-handler-1.0.0.jar" "$VENDOR/wire-handler/"

mkdir -p "$ROOT/protocol/signal/src/main/res/raw"
if [[ -f "$TMP/app/src/main/res/raw/whisper.store" ]]; then
  cp "$TMP/app/src/main/res/raw/whisper.store" "$ROOT/protocol/signal/src/main/res/raw/whisper"
elif [[ -f "$TMP/app/src/main/res/raw/whisper" ]]; then
  cp "$TMP/app/src/main/res/raw/whisper" "$ROOT/protocol/signal/src/main/res/raw/whisper"
fi

# Tor SOCKS5 + restored CS APIs used by SecureMessenger.
python3 "$VENDOR/overlays/apply-overlays.py"

echo "$REF" > "$VENDOR/VERSION"
echo "Synced Signal vendor sources to $VENDOR (ref=$REF)"
