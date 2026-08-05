# Parking lot

Deferred work — pick up when ready, not blocking current ship.

## Push — APNs key (iOS)

Function `onThreadMessageCreated` is live on Blaze. Android can receive pushes.
iOS still needs an APNs Authentication Key (.p8) uploaded in Firebase Console →
Project settings → Cloud Messaging → Apple app `app.maptalk`.

Until then: Live builds request notification permission and register FCM tokens, but
iOS delivery won’t work without the key.

## Video messages

Shipped MVP: pick ≤30s MP4, R2 `POST /v1/video`, in-thread play.

Production hardening checklist: [`VIDEO_PRODUCTION.md`](VIDEO_PRODUCTION.md).

## Account extras (not in identity slice)

Sign-out, account delete, merging when an Apple/Google credential is already linked to
another Firebase user.
