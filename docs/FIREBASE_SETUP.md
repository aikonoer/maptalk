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

- Firebase Storage / Blaze — only if you want Storage as a fallback; **not required** for R2 photos  
- Deploy Worker updates: `cd workers/media && npm run deploy`
- `git push` when you want GitHub to catch up

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
