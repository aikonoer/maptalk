# MapTalk Firebase / Cloudflare setup

Project ID: **`maptalk-app`**  
Console: https://console.firebase.google.com/project/maptalk-app/overview

Local SDK configs are already downloaded (gitignored):

- `android/app/google-services.json`
- `ios/MapTalk/GoogleService-Info.plist`

## Done from the CLI

- [x] Created Firebase project `maptalk-app` (separate from HoopsLive)
- [x] Registered Android (`app.maptalk`) and iOS (`app.maptalk`) apps
- [x] Created Firestore `(default)` in `australia-southeast1`
- [x] Deployed Firestore rules + indexes
- [x] Wrote `firebase/.firebaserc` → default `maptalk-app`

## Needs a click in your browsers (API blocked)

### 1. Anonymous Auth (~30s)

https://console.firebase.google.com/project/maptalk-app/authentication/providers

1. Get started if prompted  
2. Enable **Anonymous** → Save  

### 2. Firebase Storage / Blaze (~2 min) — for live cloud photos

Storage needs billing on the project (Spark cannot create the bucket):

https://console.firebase.google.com/project/maptalk-app/usage/details

1. Upgrade **maptalk-app** to **Blaze** (same billing account as HoopsLive is fine)  
2. Then open https://console.firebase.google.com/project/maptalk-app/storage and **Get started**  
3. Tell me when that’s done — I’ll deploy `storage.rules`

Until then: **local demo photos still work** on device; emulator Storage works without Blaze.

### 3. Cloudflare R2 (optional, free egress)

Wrangler is logged into **hoops live account**, but R2 is not enabled yet:

https://dash.cloudflare.com/9f3ffd7ed0ebbff87bcf2f5466e501c6/r2

1. Enable R2 (accept terms)  
2. Tell me — I’ll create bucket `maptalk-media` and wire uploads  

HoopsLive itself still uses Firebase Storage for chat photos; R2 was only planned there.

## Do not do

- Do not reuse project `hoopslive-9da9e` for MapTalk  
- Do not commit `google-services.json` / `GoogleService-Info.plist`
