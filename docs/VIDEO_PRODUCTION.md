# Video production checklist

Track hardening of ≤30s video messages beyond the first ship (`d63a49e`).

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
- [ ] Prefer streaming / signed PUT over full-body buffers (Worker + device RAM)
  - Partial: Worker reads with early abort (capped chunking); clients still buffer encode output

## 3. Abuse & integrity

- [x] Server-side duration/size check from the file (not only client-written Firestore fields)
  - Worker parses `mvhd` duration; optional `X-MapTalk-Duration-Ms` cross-check
- [x] Durable rate limits (KV / Durable Object), not per-isolate maps
- [x] Constrain `videoPath` to our public media base + expected key shape (Firestore rules)
- [ ] Optional: async moderation / hash later

## 4. Playback UX

- [x] Poster / first-frame thumbnail on bubbles (no black rectangle before play)
- [x] Robust players (ExoPlayer / polished AVPlayer): buffering, pause others, mute
- [x] Disk cache for recent clips
- [x] Optional cellular / large-size warning (≥5 MB or metered/cellular)

## 5. Cost & CDN

- [x] Confirm R2 Cache-Control + public URL strategy under load
- [x] Delete R2 object when message/thread is deleted (or TTL abandoned uploads)
  - Worker `DELETE /v1/admin/object` + Function `onThreadMessageDeleted` (`MEDIA_DELETE_SECRET` set)
- [ ] Cap concurrent uploads; metrics on size / 4xx rates

## 6. Ship

- [x] Push `main` + store release with Live video
  - Partial: `main` pushed; store release still pending
- [ ] Device matrix: portrait/landscape, HEVC→H.264, short/long, offline fail
- [ ] Metrics: upload success %, p95 duration/size, Worker 413/415/429

---

## Current slice (done)

Media URL allowlist; ExoPlayer + AVPlayer pause-others; disk caches; R2 delete hook (needs secret).

## Still open

- True signed PUT / zero-copy R2 stream
- Concurrent upload caps, metrics, App Store / Play release, device matrix

