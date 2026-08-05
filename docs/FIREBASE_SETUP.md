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

## Seed sample chats (live)

```bash
node scripts/seed-live.mjs
```

Writes the Cebu neighbourhood (plus a couple of other cities) into `maptalk-app` so Live
opens with bubbles to tap. Safe to re-run; document IDs are stable.

## Optional later

- Firebase Storage / Blaze — Blaze is on for push Functions; Storage still optional vs R2  
- Deploy Worker updates: `cd workers/media && npm run deploy`
- `git push` when you want GitHub to catch up

### Real identity (Apple / Google link)

Anonymous Auth stays the bootstrap. Users can **link** Apple (iOS) or Google (Android) from
Settings so the same `uid` becomes permanent.

1. Firebase Console → Authentication → Sign-in method → enable **Apple** and **Google**.
2. Apple Developer → Identifiers → `app.maptalk` → enable **Sign in with Apple**.
3. Android: add your debug (and release) **SHA-1 / SHA-256** on the Firebase Android app,
   then redownload `android/app/google-services.json` so `oauth_client` is populated.
   ```bash
   keytool -list -v -alias androiddebugkey \
     -keystore ~/.android/debug.keystore -storepass android -keypass android
   ```
4. iOS already ships the Sign in with Apple entitlement in `MapTalk.entitlements`.

### Custom domain for media (when you have a domain)

Today Live uses:

- Upload API: `https://maptalk-media.hhypkfpshg.workers.dev`
- Public files: `https://pub-7c910bfa4a884bb6bd039db548455d5e.r2.dev`

To brand them (e.g. `media.maptalk.app` + `cdn.maptalk.app`):

1. In Cloudflare → Workers → `maptalk-media` → Triggers → **Add Custom Domain** (or a route on your zone).
2. In R2 → `maptalk-media` → Settings → **Custom Domains** → attach a hostname; Cloudflare issues the cert.
3. Set Worker `PUBLIC_BASE_URL` to the R2 custom domain (`wrangler secret` / vars), redeploy.
4. Point both apps’ `MAPTALK_MEDIA_UPLOAD_URL` at the Worker custom domain (`…/v1/images`).

Until then, the `*.workers.dev` / `*.r2.dev` URLs are fine for development and closed testing.
