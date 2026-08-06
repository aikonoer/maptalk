# Parking lot

Deferred work — pick up when ready, not blocking the next ship step
(device matrix → store release). Setup details for Firebase/R2 live in
[`FIREBASE_SETUP.md`](FIREBASE_SETUP.md). Video hardening checklist:
[`VIDEO_PRODUCTION.md`](VIDEO_PRODUCTION.md).

---

## iOS → Android sync

Working **iOS-first**; do not port until this stretch is done. Apply these to
Android in one pass later.

- [ ] **Chat title** — capped at compose (~100); header is plain 2-line
      `lineLimit` + tail truncate. No expand/overlay. Live stays on the subtitle
      row. iOS: `ThreadScreen.swift` header + `NewThreadSheet` title cap.
      Android: match (drop any expand/fold/overlay title experiments).

- [ ] **Reddit-style new thread** — title capped (~100) for map/header; optional
      longer body (~1000) posts as the first message. iOS: `NewThreadSheet.swift`
      + `MapModel.createThread(openingText:)`. Port Android `NewThreadSheet` /
      `MapViewModel.createThread` the same way.

- [ ] **Account page** — map opens `AccountScreen` (photo, display name, Apple
      link, posting Soon, blocked, sign-out/delete Soon). Spec:
      `docs/ACCOUNT.md`. Port Android. Deploy updated `firestore.rules` +
      `storage.rules` for `photoURL` / avatar path.

- [ ] **Map account avatar** — status-row button shows profile photo (or
      initials) via profile stream, quiet pad styling. iOS: `MapAccountAvatar` +
      `auth.profile` listener on `MapScreen`. Port Android map chrome.

- [ ] **Cluster markers — stacked kinds** — stacked glyphs (hottest first) +
      “+N” (live on both already for layout). Custom vector icon set still
      parked below.

- [ ] **Cluster markers — custom kind icons** — replace emoji glyphs with a
      custom vector set per `ThreadKind` (event / notice / traffic / general):
      monochrome-friendly on dark map. iOS `ClusterBubbleMarker` + Android
      `ClusterBubble`.

- [ ] **Map kind filters** — stacked emoji filter under the status pill; tap
      spreads to toggle kinds (client-side only). iOS: `MapModel.kindFilter` +
      `KindFilterStack`. Port Android `MapViewModel` / `MapScreen`.

- [ ] **Place-to-pin crosshair** — center crosshair only while placing /
      composing (Start → pin → “Pin chat here” → sheet; × cancels). iOS:
      `isPlacingPin` in `MapScreen.swift`. Port Android map crosshair.

- [ ] **Map bubble anchor** — speech-bubble shape with sharper bottom-leading
      corner; annotation anchor is that corner (`.bottomLeading`). No separate
      pin/dot/caret. iOS: `Theme.bubble(tailRadius:)` + `Annotation` anchor.
      Port Android marker anchor + shape.

- [ ] **Bubble gestures vs map pinch** — tap / long-press must not steal
      multi-touch; yield when a second finger lands. iOS:
      `BubbleGestureCatcher`. Port Android marker touch handling.

- [ ] **Peek preview** — load latest message *before* sliding the card in so
      the Latest block rides with the peek (original slide transition). iOS:
      `presentPreview` in `MapScreen`. Port Android peek if it pops content
      late.

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
already tied to another Firebase user; Android Account parity; avatar on R2
(currently Firebase Storage for Live).

---

## Store release (after device matrix)

App Store / Play submit with Live video. Blocked only on running the device matrix
in [`VIDEO_PRODUCTION.md`](VIDEO_PRODUCTION.md) § Ship — not on the parked items above.
