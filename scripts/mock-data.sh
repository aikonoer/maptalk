#!/usr/bin/env bash
#
# Starts the Firebase emulators, fills them with sample conversations, and stays running so you
# can browse them from either app. No Firebase project needed.
#
#   scripts/mock-data.sh                       # cluster around Sydney CBD
#   scripts/mock-data.sh -33.8688 151.2093     # cluster around somewhere else
#
# Then, in another terminal:
#   iOS      xcodegen generate && open ios/MapTalk.xcodeproj, run the "MapTalk Mock Data" scheme
#   Android  cd android && ./gradlew installDebug -Pmaptalk.emulator=true
#
set -euo pipefail

LAT=${1:--33.8688}
LNG=${2:-151.2093}
PROJECT_ID=maptalk-qa
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)

cd "$ROOT/firebase"
[ -d node_modules ] || npm install

npx firebase emulators:start --only auth,firestore,storage --project "$PROJECT_ID" &
emulators=$!
trap 'kill $emulators 2>/dev/null || true' EXIT INT TERM

printf 'Waiting for the emulators'
for _ in $(seq 1 60); do
  if curl -fsS "http://localhost:8080/" >/dev/null 2>&1; then break; fi
  printf '.'
  sleep 1
done
echo

node "$ROOT/scripts/seed-emulator.mjs" --lat "$LAT" --lng "$LNG" --project "$PROJECT_ID"

cat <<EOF

Emulators are running with mock data. Leave this terminal open, then:

  iOS      cd ios && xcodegen generate && open MapTalk.xcodeproj
           run the "MapTalk Mock Data" scheme on a simulator
           (Features > Location > Custom Location: $LAT, $LNG)

  Android  cd android && ./gradlew installDebug -Pmaptalk.emulator=true
           needs MAPS_API_KEY in android/local.properties or the map renders blank

  Data     http://localhost:4000/firestore

Ctrl+C stops the emulators and throws the data away.
EOF

wait $emulators
