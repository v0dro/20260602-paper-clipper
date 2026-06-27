#!/usr/bin/env bash
# Build, install and launch Paper Clipper on a USB-connected iPhone.
#
# Prereqs:
#   1. brew install xcodegen   (once)
#   2. Edit Config.xcconfig: set DEVELOPMENT_TEAM to your 10-char Apple Team ID (and SERVER_URL /
#      PROXY_TOKEN to talk to the real backend). Simulator runs don't need DEVELOPMENT_TEAM.
#   3. Plug in the iPhone, unlock it, and tap "Trust" when prompted.
#
# Usage: ios/scripts/run-on-device.sh   (run from anywhere)

set -euo pipefail
cd "$(dirname "$0")/.."   # ios/

SCHEME="PaperClipper"
BUNDLE_ID="com.captureken.paperclipper"
DERIVED="build"

echo "==> Generating Xcode project"
xcodegen generate >/dev/null

echo "==> Looking for a connected device"
# Pick the first paired, connected device UDID.
UDID="$(xcrun devicectl list devices 2>/dev/null | awk '/connected/{print $(NF-1); exit}')"
if [[ -z "${UDID:-}" ]]; then
  echo "No connected device found. Connect + unlock the iPhone, tap Trust, then retry." >&2
  echo "(Check with: xcrun devicectl list devices)" >&2
  exit 1
fi
echo "    device: $UDID"

echo "==> Building for device (automatic signing)"
xcodebuild \
  -project PaperClipper.xcodeproj \
  -scheme "$SCHEME" \
  -configuration Debug \
  -destination "id=$UDID" \
  -derivedDataPath "$DERIVED" \
  -allowProvisioningUpdates \
  build

APP="$DERIVED/Build/Products/Debug-iphoneos/$SCHEME.app"
echo "==> Installing $APP"
xcrun devicectl device install app --device "$UDID" "$APP"

echo "==> Launching $BUNDLE_ID"
xcrun devicectl device process launch --device "$UDID" "$BUNDLE_ID"

echo "Done. If the app shows 'Untrusted Developer', approve it on the phone:"
echo "  Settings ▸ General ▸ VPN & Device Management ▸ (your team) ▸ Trust."
