#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
PROJECT="$ROOT_DIR/app-ios-swift/app-ios-swift.xcodeproj"
SCHEME="app-ios-swift"
BUNDLE_ID="orgIdentifier.app-ios-swift"
DERIVED_DATA_PATH="${DERIVED_DATA_PATH:-/tmp/ForeignWordsReaderDerivedData}"

DEVICE_ID="${SIMULATOR_ID:-$(xcrun simctl list devices booted | awk -F'[()]' '/Booted/ { print $2; exit }')}"

if [[ -z "$DEVICE_ID" ]]; then
  echo "No booted iOS simulator found."
  echo "Boot one in Simulator.app, or run with SIMULATOR_ID=<device-uuid>."
  exit 1
fi

APP_PATH="$DERIVED_DATA_PATH/Build/Products/Debug-iphonesimulator/$SCHEME.app"

echo "Using simulator: $DEVICE_ID"
open -a Simulator

xcodebuild \
  -project "$PROJECT" \
  -scheme "$SCHEME" \
  -configuration Debug \
  -destination "id=$DEVICE_ID" \
  -derivedDataPath "$DERIVED_DATA_PATH" \
  CODE_SIGNING_ALLOWED=NO \
  build

xcrun simctl install "$DEVICE_ID" "$APP_PATH"
xcrun simctl launch --terminate-running-process "$DEVICE_ID" "$BUNDLE_ID"
