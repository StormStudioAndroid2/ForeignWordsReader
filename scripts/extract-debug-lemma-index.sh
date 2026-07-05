#!/usr/bin/env bash
set -euo pipefail

export LC_ALL=C

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"

ANDROID_PACKAGE="${ANDROID_PACKAGE:-com.example.myapplication.android}"
IOS_BUNDLE_ID="${IOS_BUNDLE_ID:-orgIdentifier.app-ios-swift}"
OUTPUT_DIR="${OUTPUT_DIR:-$ROOT_DIR/debug-lemma-index/from-emulators}"

EXTRACT_ANDROID=1
EXTRACT_IOS=1

usage() {
  cat <<USAGE
Usage: $(basename "$0") [--android-only|--ios-only]

Extract debug lemma index files from Android and iOS emulators.

Environment overrides:
  OUTPUT_DIR=<path>           Default: $ROOT_DIR/debug-lemma-index/from-emulators
  ANDROID_PACKAGE=<id>        Default: $ANDROID_PACKAGE
  ANDROID_SERIAL=<serial>     Optional adb device serial
  IOS_BUNDLE_ID=<id>          Default: $IOS_BUNDLE_ID
  IOS_SIMULATOR_ID=<uuid>     Optional booted simulator UUID
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --android-only)
      EXTRACT_IOS=0
      shift
      ;;
    --ios-only)
      EXTRACT_ANDROID=0
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

extract_android() {
  if ! command -v adb >/dev/null 2>&1; then
    echo "Android: adb was not found." >&2
    return 1
  fi

  run_adb() {
    if [[ -n "${ANDROID_SERIAL:-}" ]]; then
      adb -s "$ANDROID_SERIAL" "$@"
    else
      adb "$@"
    fi
  }

  if ! run_adb get-state >/dev/null 2>&1; then
    echo "Android: no connected emulator/device found." >&2
    return 1
  fi

  if ! run_adb shell run-as "$ANDROID_PACKAGE" test -d files/debug-lemma-index; then
    echo "Android: files/debug-lemma-index not found for $ANDROID_PACKAGE." >&2
    return 1
  fi

  local destination="$OUTPUT_DIR/android"
  mkdir -p "$destination"

  run_adb exec-out run-as "$ANDROID_PACKAGE" \
    tar -C files/debug-lemma-index -cf - . |
    tar -x -C "$destination"

  echo "Android: saved debug lemma files to $destination"
}

extract_ios() {
  if ! command -v xcrun >/dev/null 2>&1; then
    echo "iOS: xcrun was not found." >&2
    return 1
  fi

  local simulator_id="${IOS_SIMULATOR_ID:-}"
  if [[ -z "$simulator_id" ]]; then
    simulator_id="$(xcrun simctl list devices booted | awk -F'[()]' '/Booted/ { print $2; exit }')"
  fi

  if [[ -z "$simulator_id" ]]; then
    echo "iOS: no booted simulator found. Boot one or set IOS_SIMULATOR_ID." >&2
    return 1
  fi

  local data_container
  if ! data_container="$(xcrun simctl get_app_container "$simulator_id" "$IOS_BUNDLE_ID" data 2>/dev/null)"; then
    echo "iOS: app data container not found for $IOS_BUNDLE_ID on $simulator_id." >&2
    return 1
  fi

  local source="$data_container/Library/Application Support/DebugLemmaIndex"
  if [[ ! -d "$source" ]]; then
    echo "iOS: DebugLemmaIndex not found in app container." >&2
    return 1
  fi

  local destination="$OUTPUT_DIR/ios"
  mkdir -p "$destination"
  cp -R "$source"/. "$destination"/

  echo "iOS: saved debug lemma files to $destination"
}

status=0

if [[ "$EXTRACT_ANDROID" == "1" ]]; then
  extract_android || status=1
fi

if [[ "$EXTRACT_IOS" == "1" ]]; then
  extract_ios || status=1
fi

exit "$status"
