# Parking lot

Deferred work — pick up when ready, not blocking the next ship step
(device matrix → store release). Setup details for Firebase/R2 live in
[`FIREBASE_SETUP.md`](FIREBASE_SETUP.md). Video hardening checklist:
[`VIDEO_PRODUCTION.md`](VIDEO_PRODUCTION.md).

---

## Map markers — custom kind icons

The iOS → Android map stretch is ported, so the two apps are level again: title
cap and 2-line header, the optional opening post, the account page and map
avatar, kind filters, the placing crosshair, the bottom-leading bubble anchor,
gestures that yield to a pinch, the peek preload and swipe dismiss, place labels,
and place search.

Still parked here: replace the emoji glyphs with a custom vector set per
`ThreadKind` (event / notice / traffic / general), monochrome-friendly on a dark
map. Touches `LiveThreadBubble` / `ClusterBubbleMarker` / `KindFilterStack` on
iOS and `ThreadBubble` / `ClusterBubble` / `KindFilterStack` on Android.

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

**Now:** Anonymous Firebase bootstrap is fine. Account page covers profile (name,
photo), Continue with Apple, blocked people. Full field + auth catalogue:
[`ACCOUNT.md`](ACCOUNT.md).

**Later:** Everyone keeps a real account (linked). Starting a chat or replying
offers a toggle: show your name vs post anonymously. One `uid` always; anonymity
is a per-write presentation choice (`authorName` / visibility), not a second
Firebase user. Don’t invent throwaway auth for anonymous posts.

Still parked: sign-out, account delete, credential merge when Apple/Google is
already tied to another Firebase user; avatar on R2 (currently Firebase Storage
for Live).

---

## Store release (after device matrix)

App Store / Play submit with Live video. Blocked only on running the device matrix
in [`VIDEO_PRODUCTION.md`](VIDEO_PRODUCTION.md) § Ship — not on the parked items above.
