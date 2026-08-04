#!/usr/bin/env bash
#
# The cross-device check from the plan, automated: a thread created on Android is found and
# replied to on iOS, and the reply comes back to Android. Both apps talk to the Firebase
# emulators under fake credentials, so this needs no Firebase project and no Maps key.
#
# Prerequisites: an Android emulator already booted (or a device attached), an iOS simulator
# available, node, and a JDK for Gradle. Usage:
#
#   scripts/cross-device-qa.sh [iOS simulator name]
#
set -euo pipefail

SIMULATOR=${1:-iPhone 17 Pro}
PROJECT_ID=maptalk-qa
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)

: "${JAVA_HOME:=/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
export JAVA_HOME

step() { printf '\n\033[1m==> %s\033[0m\n' "$1"; }

step 'Checking for an Android device'
adb=${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb
if ! "$adb" devices | grep -q 'device$'; then
  echo 'No Android device or emulator is attached. Start one, then run this again.' >&2
  echo "Available emulators: $("${ANDROID_HOME:-$HOME/Library/Android/sdk}"/emulator/emulator -list-avds | tr '\n' ' ')" >&2
  exit 1
fi

step 'Running the whole handshake against the Firebase emulators'
cd "$ROOT/firebase"
[ -d node_modules ] || npm install

# One emulator instance wraps all three steps so the data written by one is there for the next.
npx firebase emulators:exec --only auth,firestore,storage --project "$PROJECT_ID" "$ROOT/scripts/cross-device-steps.sh '$SIMULATOR'"
