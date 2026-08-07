# Parking lot

Deferred work — pick up when ready. **Store week:** see
[`FEATURE_FREEZE.md`](FEATURE_FREEZE.md). Setup details for Firebase/R2 live in
[`FIREBASE_SETUP.md`](FIREBASE_SETUP.md). Video hardening checklist:
[`VIDEO_PRODUCTION.md`](VIDEO_PRODUCTION.md).

---

## Map markers — custom kind icons

Both apps still use emoji glyphs for `ThreadKind`. Replace with a custom vector
set (event / notice / traffic / general), monochrome-friendly on a dark map.
Touches `LiveThreadBubble` / `ClusterBubbleMarker` / `KindFilterStack` on iOS and
`ThreadBubble` / `ClusterBubble` / `KindFilterStack` on Android.

---

## Place labels — persist on the thread doc (later)

Both apps reverse-geocode a thread's position on demand and cache it in memory.
Writing a `placeLabel` onto the thread document would save the lookup and let the
map show an area name before the geocoder answers.

Related known difference: Android place search goes through `Geocoder`, which is
address-first, where iOS uses `MKLocalSearch`, which also ranks points of
interest. The same query can order results differently on the two platforms.

---

## Tags — curated secondary (later)

Keep **exactly one required main kind** (`ThreadKind`). Optional later: ≤2–3
curated secondary tags (closed list, not free-form) on compose + optional map
refine. Skip unlimited custom tags until density / abuse model is clearer.

---

## Push — APNs key (iOS)

Function `onThreadMessageCreated` is live on Blaze. Android can receive pushes.
iOS still needs an APNs Authentication Key (`.p8`) uploaded in Firebase Console →
Project settings → Cloud Messaging → Apple app `app.maptalk`.

Until then: Live builds request notification permission and register FCM tokens, but
iOS delivery won’t work without the key.

---

## Video — R2 S3 secrets (direct client→R2 PUT)

Code is live (`f4a7c64`): clients call `POST /v1/video/presign` → PUT to R2 →
`POST /v1/video/confirm`. Without S3 API secrets, `/health` shows
`"videoPresign": false` and clients fall back to legacy Worker `PUT /v1/video`
(fine for closed testing).

To enable:

1. Cloudflare → R2 → **Manage R2 API Tokens** → Object Read & Write on `maptalk-media`
2. From `workers/media`:

```bash
npx wrangler secret put R2_ACCESS_KEY_ID
npx wrangler secret put R2_SECRET_ACCESS_KEY
npx wrangler secret put R2_ACCOUNT_ID
# optional: npx wrangler secret put R2_BUCKET_NAME
npx wrangler deploy
```

3. Confirm `GET …/health` → `"videoPresign": true`

Full notes: [`FIREBASE_SETUP.md`](FIREBASE_SETUP.md) § R2 S3 API tokens.

---

## Video — optional moderation

Async moderation / perceptual hash of uploaded clips. Not started; listed as
optional in [`VIDEO_PRODUCTION.md`](VIDEO_PRODUCTION.md) § Abuse & integrity.

---

## Video — R2 cleanup env (Functions)

R2 delete on message delete is live (`MEDIA_DELETE_SECRET` on Worker +
`onThreadMessageDeleted`). Local `firebase/functions/.env` holds the param
values and is gitignored — recreate it if deploying Functions from a fresh clone.

---

## Account — per-post anonymous (later)

**Now (both):** Anonymous bootstrap + welcome (Apple/Google or guest). Account is
production-shaped: profile, provider link, blocked people, Sign out / Delete,
About (version, Privacy, Terms). Catalogue: [`ACCOUNT.md`](ACCOUNT.md).
Deploy Firestore rules so `users/{uid}` allows own-document delete.

**Later:** Per-post anonymous toggle. Credential merge when Apple/Google is
already tied to another Firebase user; avatar on R2; hosted privacy/terms URLs.

---

## Store release (after device matrix)

App Store / Play submit with Live video. Blocked only on running the device matrix
in [`VIDEO_PRODUCTION.md`](VIDEO_PRODUCTION.md) § Ship — not on the parked items above.
Follow [`FEATURE_FREEZE.md`](FEATURE_FREEZE.md) for what not to start.
