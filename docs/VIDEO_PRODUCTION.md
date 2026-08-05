# Video production checklist

Track hardening of ≤30s video messages beyond the first ship (`d63a49e`).

Parked follow-ups (S3 secrets, moderation, APNs, accounts): [`PARKING.md`](PARKING.md).

## Envelope (shared)

Target for both platforms before upload:

| Constraint | Value |
|---|---|
| Container | MP4 (`ftyp`) |
| Video | H.264 |
| Audio | AAC |
| Max duration | 30 s |
| Max long edge | 1280 px (720p class) |
| Max body | 12 MB (Worker hard cap) |

---

## 1. Reliable encode

- [x] **Android** re-encode gallery clips (H.264 + AAC, ≤720p) — do not upload arbitrary camera MP4s
- [x] **iOS** pin export to the same envelope (preset / max long edge), not only `MediumQuality`
- [x] Clear client errors: too long, compress failed, too large after encode
- [x] Progress while compressing (spinner / status copy)

## 2. Upload resilience

- [x] Upload progress (and “Sending video…” state) on both platforms
- [x] Clearer Worker error mapping (413 / 415 / 429 → human copy)
- [x] Retry with backoff on transient failures
- [x] Cancel in-flight prepare/upload when leaving the thread
- [x] Prefer streaming / signed PUT over full-body buffers (Worker + device RAM)
  - Video: clients prefer `POST /v1/video/presign` → direct R2 PUT → `POST /v1/video/confirm`
  - Falls back to Worker `PUT /v1/video` when R2 S3 secrets are unset ([parked](PARKING.md))
  - Images/audio still POST buffered (small caps)

## 3. Abuse & integrity

- [x] Server-side duration/size check from the file (not only client-written Firestore fields)
  - Worker parses `mvhd` duration; optional `X-MapTalk-Duration-Ms` cross-check
- [x] Durable rate limits (KV / Durable Object), not per-isolate maps
- [x] Constrain `videoPath` to our public media base + expected key shape (Firestore rules)
- [ ] Optional: async moderation / hash later ([parked](PARKING.md))

## 4. Playback UX

- [x] Poster / first-frame thumbnail on bubbles (no black rectangle before play)
- [x] Robust players (ExoPlayer / polished AVPlayer): buffering, pause others, mute
  - Shared mute toggle; optimistic outgoing bubble + composer thumbnail while uploading
- [x] Disk cache for recent clips
- [x] Optional cellular / large-size warning (≥5 MB or metered/cellular)

## 5. Cost & CDN

- [x] Confirm R2 Cache-Control + public URL strategy under load
- [x] Delete R2 object when message/thread is deleted (or TTL abandoned uploads)
  - Worker `DELETE /v1/admin/object` + Function `onThreadMessageDeleted` (`MEDIA_DELETE_SECRET` set)
- [x] Cap concurrent uploads; metrics on size / 4xx rates
  - Clients gate R2 uploads to 2 in flight
  - Worker daily KV counters on `/health` (`ok` / `413` / `415` / `429` / `4xx` / `5xx`)

## 6. Ship

- [x] Code on `main` with Live video (store release still pending)
- [x] Metrics: upload success %, p95 duration/size, Worker 413/415/429
  - Daily status counters + rolling p50/p95 size (and video duration) on `/health`
- [ ] Device matrix (run on a physical phone before store submit):
  - [ ] Portrait clip encode + play
  - [ ] Landscape clip encode + play
  - [ ] HEVC camera roll → H.264 upload envelope
  - [ ] Short (~3s) and near-limit (~14s) clips
  - [ ] Offline / airplane during upload → clear error + retry
  - [ ] Pause-others when a second bubble plays
  - [ ] Mute / unmute on a playing bubble
  - [ ] Optimistic “Sending…” bubble + composer thumbnail while uploading
  - [ ] Home-screen relaunch stays Live (iOS Live config / Android `-Pmaptalk.mode=live`)
- [ ] App Store / Play release ([parked until matrix done](PARKING.md))

---

## Next

**Device matrix** on a physical phone (checklist above), then store submit.

Everything else is in [`PARKING.md`](PARKING.md).
