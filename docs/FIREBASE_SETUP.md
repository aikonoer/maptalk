# MapTalk Firebase / Cloudflare setup

Project ID: **`maptalk-app`**  
Console: https://console.firebase.google.com/project/maptalk-app/overview

Local SDK configs are already downloaded (gitignored):

- `android/app/google-services.json`
- `ios/MapTalk/GoogleService-Info.plist`

## Done

- [x] Firebase project `maptalk-app` (separate from HoopsLive)
- [x] Android + iOS apps registered (`app.maptalk`)
- [x] Firestore `(default)` in `australia-southeast1` + rules/indexes deployed
- [x] Anonymous Auth enabled (console)
- [x] Cloudflare R2 activated; bucket **`maptalk-media`** (Oceania)
- [x] Public reads: `https://pub-7c910bfa4a884bb6bd039db548455d5e.r2.dev`
- [x] Upload Worker: `https://maptalk-media.hhypkfpshg.workers.dev` (`workers/media`)

## Photo upload path (live)

1. Client compresses JPEG on device  
2. Client POSTs bytes to Worker with Firebase ID token  
3. Worker writes `threads/{threadId}/{messageId}.jpg` into R2  
4. Firestore message stores the public `https://pub-….r2.dev/…` URL  

Emulator / mock-data still uses Firebase Storage emulator. Local demo keeps files on device.

## Optional later

- Firebase Storage / Blaze — only if you want Storage as a fallback; **not required** for R2 photos  
- Custom domain instead of `*.r2.dev` / `*.workers.dev`  
- Deploy Worker updates: `cd workers/media && npm run deploy`
