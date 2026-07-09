#!/usr/bin/env bash
# Build TDLib JNI libs from source and copy into protocol/telegram/jniLibs.
# Requires: git, curl, gperf, php-cli, perl, make, JDK, Android SDK + NDK.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JNI_OUT="$ROOT/protocol/telegram/src/main/jniLibs"
BUILD_DIR="${TMPDIR:-/tmp}/securemessenger-tdlib-build"

SDK_ROOT="${ANDROID_SDK_ROOT:-}"
NDK_VERSION="${ANDROID_NDK_VERSION:-26.1.10909125}"

if [[ -z "$SDK_ROOT" && -f "$ROOT/local.properties" ]]; then
  SDK_ROOT="$(grep -E '^sdk\.dir=' "$ROOT/local.properties" | cut -d= -f2- | tr -d '\r')"
  NDK_DIR="$(grep -E '^ndk\.dir=' "$ROOT/local.properties" | cut -d= -f2- | tr -d '\r' || true)"
  if [[ -n "${NDK_DIR:-}" ]]; then
    ANDROID_NDK_HOME="$NDK_DIR"
    NDK_VERSION="$(basename "$NDK_DIR")"
  fi
fi

SDK_ROOT="${SDK_ROOT:-/home/user/Android/Sdk}"

if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
  ANDROID_NDK_HOME="$SDK_ROOT/ndk/$NDK_VERSION"
fi

if [[ ! -d "$ANDROID_NDK_HOME" ]]; then
  echo "NDK not found at $ANDROID_NDK_HOME"
  echo "Install with: sdkmanager \"ndk;${NDK_VERSION}\""
  echo "Then set ndk.dir in local.properties"
  exit 1
fi

export ANDROID_NDK_HOME ANDROID_SDK_ROOT="$SDK_ROOT"

for tool in gperf php perl make java jar; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "Missing build tool: $tool"
    echo "Fedora: sudo dnf install gperf php-cli"
    exit 1
  fi
done

mkdir -p "$BUILD_DIR"
cd "$BUILD_DIR"

if [[ ! -d td ]]; then
  git clone --depth 1 https://github.com/tdlib/td.git
fi

cd td/example/android

echo "Building OpenSSL for Android (SDK=$SDK_ROOT NDK=$NDK_VERSION)..."
if [[ ! -d third-party/openssl ]]; then
  ./build-openssl.sh "$SDK_ROOT" "$NDK_VERSION"
fi

echo "Building TDLib JNI..."
./build-tdlib.sh "$SDK_ROOT" "$NDK_VERSION" third-party/openssl

mkdir -p "$JNI_OUT"
for abi in arm64-v8a armeabi-v7a x86_64 x86; do
  src="$BUILD_DIR/td/example/android/tdlib/libs/$abi/libtdjni.so"
  if [[ -f "$src" ]]; then
    mkdir -p "$JNI_OUT/$abi"
    cp "$src" "$JNI_OUT/$abi/libtdjni.so"
    echo "Installed $abi"
  fi
done

JAVA_SRC="$BUILD_DIR/td/example/android/tdlib/java"
if [[ -d "$JAVA_SRC" ]]; then
  echo "Generated Java bindings: $JAVA_SRC"
  echo "If ca.denisab85 tdlib JAR mismatches, sync bindings from that directory."
fi

echo "Done. Rebuild SecureMessenger."
