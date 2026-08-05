# MapTalk

Location-pinned public chat threads. Anyone can drop a conversation onto the map — a
concert that just started, a restaurant closing early, traffic on the bridge — and anyone
browsing that part of the map can jump in and reply in real time.

Two native phone apps share one Firebase backend:

| Path        | What it is                                                  |
| ----------- | ----------------------------------------------------------- |
| `android/`  | Jetpack Compose app, Google Maps, Firestore                  |
| `ios/`      | SwiftUI app, MapKit, Firestore                               |
| `firebase/` | Firestore security rules and index configuration             |
| `docs/`     | [Data model and query strategy](docs/data-model.md)          |
| `scripts/`  | The cross-device check, run against the Firebase emulators   |

There is no server code. Both apps talk to Firestore directly and the security rules keep
the data honest, so the backend runs on Firebase's free Spark plan.

## What v1 does

- Anonymous sign-in; you pick a display name on first launch.
- Browse the whole world. Nearby threads come from a geohash query around the viewport;
  zoomed out, you see the most recently active threads globally.
- Bubbles cluster by geohash prefix as you zoom out.
- Create a thread at the map centre with a title and a kind (event, notice, traffic, general).
- Open a thread and post replies; everyone watching sees them immediately.

Deliberately not in v1: real accounts, profiles, thread expiry,
Cloud Functions–backed moderation queues, push notifications, search.

Reporting and blocking are in-app (viewer-private blocks; append-only reports).

## One-time setup

> **Current project:** `maptalk-app` — see [docs/FIREBASE_SETUP.md](docs/FIREBASE_SETUP.md)
> for what is already provisioned and the few console clicks still needed (Anonymous Auth,
> Blaze/Storage, optional Cloudflare R2).

### 1. Firebase project

SDK configs for `maptalk-app` should already be in place locally (gitignored). If you are
on a new machine, download them from the Firebase console into:

- Android: `android/app/google-services.json`
- iOS: `ios/MapTalk/GoogleService-Info.plist`

Then enable **Anonymous** under Authentication, and (for live cloud photos) upgrade to
**Blaze** and click **Get started** on Storage. Deploy rules:

```bash
cd firebase
firebase deploy --only firestore:rules,firestore:indexes,storage
```

Firestore rules are already deployed to `maptalk-app`.

### 2. Google Maps key (Android only)

iOS uses MapKit, which needs no key. Android needs a Maps SDK key:

1. In the [Google Cloud console](https://console.cloud.google.com), pick project **maptalk-app**
   and enable **Maps SDK for Android**.
2. Create an API key and restrict it to Android apps with package `app.maptalk`.
3. Put it in `android/local.properties` (gitignored):

```properties
MAPS_API_KEY=AIza...
```

The Android build reads that value and injects it into the manifest. Without it the app
still builds and runs, but the map renders blank.

## Running the Android app

The repo has no Gradle installed globally; use the wrapper. Gradle needs a JDK 17+, and
Android Studio ships one:

```bash
cd android
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew installDebug     # or assembleDebug
```

Or just open the `android/` directory in Android Studio and press Run.

Unit tests:

```bash
cd android && ./gradlew test
```

## Running the iOS app

The Xcode project is generated from `ios/project.yml` by
[XcodeGen](https://github.com/yonaskolb/XcodeGen), so the whole project is text in git:

```bash
brew install xcodegen        # once
cd ios
xcodegen generate
open MapTalk.xcodeproj
```

Swift Package Manager resolves the Firebase SDK on first open, which takes a few minutes.
Then pick a simulator or device and run.

Unit tests: `Cmd+U` in Xcode, or

```bash
cd ios && xcodebuild test -scheme MapTalk -destination 'platform=iOS Simulator,name=iPhone 17'
```

## Seeing it with mock data

You do not need a Firebase project to have a look around. One command starts the Firebase
emulators and fills them with sample conversations — a concert, a restaurant closing early,
traffic on the bridge, plus a few threads in other cities so the zoomed-out view has something
in it:

```bash
scripts/mock-data.sh                    # cluster around Sydney CBD
scripts/mock-data.sh 51.5074 -0.1278    # cluster somewhere else
```

Leave that running, then start either app in mock-data mode:

```bash
# iOS: run the "MapTalk Mock Data" scheme
cd ios && xcodegen generate && open MapTalk.xcodeproj

# Android
cd android && ./gradlew installDebug -Pmaptalk.emulator=true
```

Each app asks for a display name first — that is the real onboarding flow, and the emulators
forget it when they stop. Point the simulator at the same place you seeded (**Features >
Location > Custom Location** on iOS, `adb emu geo fix <lng> <lat>` on Android) and the map opens
on the cluster. Seeded threads are real documents, so you can open them, reply, and watch the
reply appear on the other platform. `http://localhost:4000/firestore` shows the data; Ctrl+C
throws it away.

Android additionally needs `MAPS_API_KEY` in `android/local.properties` — without it the Google
Maps view fails authorization and renders nothing, markers included. iOS uses MapKit and needs
no key.

Under the hood mock-data mode is a secondary Firebase app with fake credentials pointed at the
emulators: `AppEnvironment.emulator()` on iOS, `FirebaseEmulator.connect()` on Android. Neither
touches the config the app ships with, so your real project is never involved.

## Testing the security rules

The rules have a test suite that runs against the Firestore emulator:

```bash
cd firebase
npm install
npm test
```

## The cross-device check

The thing that actually matters — a thread created on one platform showing up and being
repliable on the other — is automated. Both apps point at the Firebase emulators under fake
credentials, so this needs no Firebase project and no Maps key:

```bash
# with an Android emulator booted and an iOS simulator available
scripts/cross-device-qa.sh 'iPhone 17 Pro'
```

It boots the emulators once and runs three ordered steps: Android pins a bubble and posts the
first message, iOS finds that bubble through its own geohash query, reads the conversation,
replies and pins one of its own, then Android confirms the reply, the bumped activity counter,
and the iOS thread — including that the geohash the Swift port wrote matches the one the real
GeoFire library computes for the same point.

Each step insists the backend has acknowledged its writes before finishing, because Firestore
serves a write back to its own listeners long before the server has seen it.

The iOS half is `MapTalkTests/CrossDeviceSyncTests`, which skips itself when the emulator is
not listening, so a plain `xcodebuild test` stays green. The Android half is
`app.maptalk.qa.CrossDeviceWriteTest` and `CrossDeviceVerifyTest`, which only run under
`connectedAndroidTest`. The fixtures the two sides hand off through are duplicated in both
files on purpose; change one and you must change the other.

`AppEnvironment.emulator()` on iOS is the same wiring the suite uses, if you want to run the
app itself against the emulators. On Android, cleartext traffic to the emulator host is allowed
by a network security config in the `debug` source set only.

## Project conventions

- The two apps mirror each other on purpose: same collection names, same field names, same
  viewport thresholds, same cluster prefix table. `docs/data-model.md` is the contract, and
  `geo/Viewport.kt` / `Core/Viewport.swift` are the two implementations of the same rules.
- Firestore documents are mapped by hand rather than by reflection, so a field rename is a
  compile error rather than a silent null.
