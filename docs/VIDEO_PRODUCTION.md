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
- [ ] Constrain `videoPath` to our public media base + expected key shape (Firestore rules)
- [ ] Optional: async moderation / hash later

## 4. Playback UX

- [x] Poster / first-frame thumbnail on bubbles (no black rectangle before play)
- [ ] Robust players (ExoPlayer / polished AVPlayer): buffering, pause others, mute
- [ ] Disk cache for recent clips
- [ ] Optional cellular / large-size warning

## 5. Cost & CDN

- [ ] Confirm R2 Cache-Control + public URL strategy under load
- [ ] Delete R2 object when message/thread is deleted (or TTL abandoned uploads)
- [ ] Cap concurrent uploads; metrics on size / 4xx rates

## 6. Ship

- [ ] Push `main` + store release with Live video
- [ ] Device matrix: portrait/landscape, HEVC→H.264, short/long, offline fail
- [ ] Metrics: upload success %, p95 duration/size, Worker 413/415/429

---

## Current slice (done)

Retry/cancel + upload backoff; Worker mvhd duration + KV rate limits + capped body read.

## Still open

- True signed PUT / zero-copy R2 stream (clients still buffer encoded bytes)
- Firestore `videoPath` host allowlist
- ExoPlayer / stronger playback, disk cache, cellular warning
- R2 delete-on-message-delete, store ship, metrics

