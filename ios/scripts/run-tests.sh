#!/usr/bin/env bash
# Run the Paper Clipper test suite (unit + UI) on an iOS Simulator.
#
# Usage:
#   ios/scripts/run-tests.sh            # unit + UI tests
#   ios/scripts/run-tests.sh unit       # unit tests only (fast)
#   ios/scripts/run-tests.sh ui         # UI tests only

set -euo pipefail
cd "$(dirname "$0")/.."   # ios/

SIM="${SIM:-iPhone 17}"   # override with: SIM='iPhone 16' ios/scripts/run-tests.sh
SCHEME="PaperClipper"

xcodegen generate >/dev/null

ARGS=(-project PaperClipper.xcodeproj -scheme "$SCHEME"
      -destination "platform=iOS Simulator,name=$SIM" -derivedDataPath build
      -test-timeouts-enabled YES -default-test-execution-time-allowance 120)

case "${1:-all}" in
  unit) ARGS+=(-only-testing:PaperClipperTests) ;;
  ui)   ARGS+=(-only-testing:PaperClipperUITests) ;;
  all)  ;;
  *) echo "usage: run-tests.sh [unit|ui|all]" >&2; exit 2 ;;
esac

exec xcodebuild "${ARGS[@]}" test
