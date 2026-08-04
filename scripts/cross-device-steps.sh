#!/usr/bin/env bash
#
# The three ordered steps of the cross-device check. Run by scripts/cross-device-qa.sh inside
# `firebase emulators:exec`, which is what makes the data written in step one visible in step
# two and three.
#
set -euo pipefail

SIMULATOR=${1:-iPhone 17 Pro}
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)

step() { printf '\n\033[1m==> %s\033[0m\n' "$1"; }

step 'Android pins a bubble on the map'
cd "$ROOT/android"
./gradlew --quiet :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=app.maptalk.qa.CrossDeviceWriteTest

step 'iOS finds it, reads the conversation, replies, and pins one of its own'
cd "$ROOT/ios"
# The suite skips itself when the emulator is unreachable, so insist on seeing it pass.
xcodebuild test \
  -project MapTalk.xcodeproj \
  -scheme MapTalk \
  -destination "platform=iOS Simulator,name=$SIMULATOR" \
  -derivedDataPath build \
  -only-testing:MapTalkTests/CrossDeviceSyncTests \
  | tee /tmp/maptalk-ios-qa.log \
  | grep -E '✔|✘|error:|Test run|TEST' || true
grep -q 'Test iosSeesTheAndroidBubbleRepliesToItAndPinsItsOwn() passed' /tmp/maptalk-ios-qa.log

step 'Android sees the reply and the thread iOS created'
cd "$ROOT/android"
./gradlew --quiet :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=app.maptalk.qa.CrossDeviceVerifyTest

printf '\n\033[1;32m==> Cross-device sync verified end to end.\033[0m\n'
