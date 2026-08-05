# Parking lot

Deferred work — pick up when ready, not blocking the next ship step
(device matrix → store release). Setup details for Firebase/R2 live in
[`FIREBASE_SETUP.md`](FIREBASE_SETUP.md). Video hardening checklist:
[`VIDEO_PRODUCTION.md`](VIDEO_PRODUCTION.md).

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

## Account extras (not in identity slice)

Sign-out, account delete, merging when an Apple/Google credential is already linked to
another Firebase user.

---

## Store release (after device matrix)

App Store / Play submit with Live video. Blocked only on running the device matrix
in [`VIDEO_PRODUCTION.md`](VIDEO_PRODUCTION.md) § Ship — not on the parked items above.
