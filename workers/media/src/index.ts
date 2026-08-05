/**
 * Authenticated media uploads into the MapTalk R2 bucket.
 *
 * POST /v1/images?threadId=&messageId=   Content-Type: image/jpeg
 * POST /v1/audio?threadId=&messageId=   Content-Type: audio/mp4 | audio/m4a | audio/aac
 * POST|PUT /v1/video?threadId=&messageId=   Content-Type: video/mp4
 *   Body streamed to R2 (no full-buffer in Worker); validated after put.
 *   Optional: X-MapTalk-Duration-Ms (cross-checked against mvhd when present)
 *   Prefer PUT; POST kept for older clients.
 * GET  /health                              Limits + daily upload metrics
 * GET  /__ping                              Deploy smoke check
 *
 * Authorization: Bearer <Firebase ID token>
 *
 * Abuse caps (KV-backed when RATE is bound, else soft in-memory):
 *   - 20 uploads / uid / rolling 10 minutes (all kinds)
 *   - 8 audio uploads / uid / rolling 10 minutes
 *   - 4 video uploads / uid / rolling 10 minutes
 *   - Magic-byte sniff + size caps + video duration from mvhd
 */

export interface Env {
  MEDIA: R2Bucket;
  RATE?: KVNamespace;
  FIREBASE_PROJECT_ID: string;
  PUBLIC_BASE_URL: string;
  MEDIA_DELETE_SECRET?: string;
}

const MAX_IMAGE_BYTES = 2 * 1024 * 1024;
const MAX_AUDIO_BYTES = 1 * 1024 * 1024;
const MAX_VIDEO_BYTES = 12 * 1024 * 1024;
const MAX_VIDEO_DURATION_MS = 30_000;
const KEY_RE = /^[A-Za-z0-9_-]{1,128}$/;
const RATE_WINDOW_MS = 10 * 60 * 1000;
const RATE_MAX_TOTAL = 20;
const RATE_MAX_AUDIO = 8;
const RATE_MAX_VIDEO = 4;
const RATE_UID_CAP = 4_000;

type UploadKind = "image" | "audio" | "video";

/** Fallback when KV is unavailable (local / misconfigured). */
const recentTotal = new Map<string, number[]>();
const recentAudio = new Map<string, number[]>();
const recentVideo = new Map<string, number[]>();

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);

    if (request.method === "OPTIONS") {
      return new Response(null, { status: 204, headers: corsHeaders() });
    }

    if (request.method === "GET" && url.pathname === "/__ping") {
      return new Response("pong-v2", { status: 200, headers: { "content-type": "text/plain" } });
    }

    if (request.method === "GET" && url.pathname === "/health") {
      const metrics = env.RATE ? await readMetrics(env.RATE) : null;
      return json({
        ok: true,
        version: 2,
        limits: {
          maxImageBytes: MAX_IMAGE_BYTES,
          maxAudioBytes: MAX_AUDIO_BYTES,
          maxVideoBytes: MAX_VIDEO_BYTES,
          maxVideoDurationMs: MAX_VIDEO_DURATION_MS,
          uploadsPer10Min: RATE_MAX_TOTAL,
          audioPer10Min: RATE_MAX_AUDIO,
          videoPer10Min: RATE_MAX_VIDEO,
          durableRateLimits: Boolean(env.RATE),
        },
        metrics,
      });
    }

    if (request.method === "POST" && url.pathname === "/v1/images") {
      return upload(request, env, url, {
        kind: "image",
        kinds: ["image/jpeg"],
        maxBytes: MAX_IMAGE_BYTES,
        extension: "jpg",
        contentType: "image/jpeg",
      });
    }

    if (request.method === "POST" && url.pathname === "/v1/audio") {
      return upload(request, env, url, {
        kind: "audio",
        kinds: ["audio/mp4", "audio/m4a", "audio/aac", "audio/x-m4a"],
        maxBytes: MAX_AUDIO_BYTES,
        extension: "m4a",
        contentType: "audio/mp4",
      });
    }

    if (
      (request.method === "POST" || request.method === "PUT") &&
      url.pathname === "/v1/video"
    ) {
      return uploadVideoStreaming(request, env, url);
    }

    if (request.method === "DELETE" && url.pathname === "/v1/admin/object") {
      const secret = request.headers.get("X-MapTalk-Admin") ?? "";
      if (!env.MEDIA_DELETE_SECRET || secret !== env.MEDIA_DELETE_SECRET) {
        return json({ error: "unauthorized" }, 401);
      }
      const key = url.searchParams.get("key") ?? "";
      if (!/^threads\/[A-Za-z0-9_-]{1,128}\/[A-Za-z0-9_-]{1,128}\.(jpg|m4a|mp4)$/.test(key)) {
        return json({ error: "bad_key" }, 400);
      }
      await env.MEDIA.delete(key);
      return json({ ok: true, key });
    }

    return json({ error: "not_found" }, 404);
  },
} satisfies ExportedHandler<Env>;

type UploadSpec = {
  kind: UploadKind;
  kinds: string[];
  maxBytes: number;
  extension: string;
  contentType: string;
};

async function upload(
  request: Request,
  env: Env,
  url: URL,
  spec: UploadSpec,
): Promise<Response> {
  const reply = (body: unknown, status = 200, extraHeaders: HeadersInit = {}) => {
    void bumpMetric(env, spec.kind, status);
    return json(body, status, extraHeaders);
  };

  const auth = request.headers.get("Authorization") ?? "";
  const token = auth.startsWith("Bearer ") ? auth.slice(7).trim() : "";
  if (!token) return reply({ error: "missing_token" }, 401);

  let uid: string;
  try {
    uid = await verifyFirebaseIdToken(token, env.FIREBASE_PROJECT_ID);
  } catch (cause) {
    console.warn("token rejected", cause);
    return reply({ error: "invalid_token" }, 401);
  }

  const rate = await allowUpload(env, uid, spec.kind);
  if (!rate.ok) {
    return reply(
      { error: "rate_limited", scope: rate.scope },
      429,
      { "Retry-After": "60" },
    );
  }

  const threadId = url.searchParams.get("threadId") ?? "";
  const messageId = url.searchParams.get("messageId") ?? "";
  if (!KEY_RE.test(threadId) || !KEY_RE.test(messageId)) {
    return reply({ error: "bad_ids" }, 400);
  }

  const contentType = (request.headers.get("Content-Type") ?? "").split(";")[0].trim().toLowerCase();
  if (!spec.kinds.includes(contentType)) {
    return reply({ error: "unsupported_type" }, 415);
  }

  const declared = Number(request.headers.get("Content-Length") ?? "0");
  if (declared > spec.maxBytes) {
    return reply({ error: "bad_size" }, 413);
  }

  if (!request.body) {
    return reply({ error: "bad_size" }, 413);
  }

  // Stream with early abort so oversized bodies never fully buffer.
  const bytes = await readBodyCapped(request.body, spec.maxBytes);
  if (bytes === null) {
    return reply({ error: "bad_size" }, 413);
  }
  if (bytes.byteLength === 0) {
    return reply({ error: "bad_size" }, 413);
  }

  if (spec.kind === "image" && !looksLikeJpeg(bytes)) {
    return reply({ error: "bad_magic" }, 415);
  }
  if ((spec.kind === "audio" || spec.kind === "video") && !looksLikeMp4Container(bytes)) {
    return reply({ error: "bad_magic" }, 415);
  }

  if (spec.kind === "video") {
    const durationMs = mp4DurationMs(bytes);
    if (durationMs == null || durationMs <= 0) {
      return reply({ error: "bad_duration" }, 415);
    }
    if (durationMs > MAX_VIDEO_DURATION_MS) {
      return reply({ error: "bad_duration", durationMs }, 413);
    }
    const claimed = Number(request.headers.get("X-MapTalk-Duration-Ms") ?? "");
    if (Number.isFinite(claimed) && claimed > 0) {
      if (Math.abs(claimed - durationMs) > 1_500) {
        return reply({ error: "duration_mismatch", durationMs, claimed }, 400);
      }
    }
  }

  const key = `threads/${threadId}/${messageId}.${spec.extension}`;
  await env.MEDIA.put(key, bytes, {
    httpMetadata: {
      contentType: spec.contentType,
      cacheControl: "public, max-age=31536000, immutable",
    },
    customMetadata: {
      uid,
      kind: spec.kind,
    },
  });

  const base = env.PUBLIC_BASE_URL.replace(/\/$/, "");
  const publicUrl = `${base}/${key}`;
  void recordSample(env, spec.kind, "size", bytes.byteLength);
  return reply({ url: publicUrl, path: key });
}

/**
 * Stream the request body straight into R2, then sniff the stored object.
 * Keeps Worker memory off the full 12 MB path (image/audio still use buffered upload).
 */
async function uploadVideoStreaming(
  request: Request,
  env: Env,
  url: URL,
): Promise<Response> {
  const reply = (body: unknown, status = 200, extraHeaders: HeadersInit = {}) => {
    void bumpMetric(env, "video", status);
    return json(body, status, extraHeaders);
  };

  const auth = request.headers.get("Authorization") ?? "";
  const token = auth.startsWith("Bearer ") ? auth.slice(7).trim() : "";
  if (!token) return reply({ error: "missing_token" }, 401);

  let uid: string;
  try {
    uid = await verifyFirebaseIdToken(token, env.FIREBASE_PROJECT_ID);
  } catch (cause) {
    console.warn("token rejected", cause);
    return reply({ error: "invalid_token" }, 401);
  }

  const rate = await allowUpload(env, uid, "video");
  if (!rate.ok) {
    return reply(
      { error: "rate_limited", scope: rate.scope },
      429,
      { "Retry-After": "60" },
    );
  }

  const threadId = url.searchParams.get("threadId") ?? "";
  const messageId = url.searchParams.get("messageId") ?? "";
  if (!KEY_RE.test(threadId) || !KEY_RE.test(messageId)) {
    return reply({ error: "bad_ids" }, 400);
  }

  const contentType = (request.headers.get("Content-Type") ?? "").split(";")[0].trim().toLowerCase();
  if (contentType !== "video/mp4") {
    return reply({ error: "unsupported_type" }, 415);
  }

  const declared = Number(request.headers.get("Content-Length") ?? "0");
  if (!Number.isFinite(declared) || declared <= 0) {
    return reply({ error: "bad_size" }, 413);
  }
  if (declared > MAX_VIDEO_BYTES) {
    return reply({ error: "bad_size" }, 413);
  }

  if (!request.body) {
    return reply({ error: "bad_size" }, 413);
  }

  const key = `threads/${threadId}/${messageId}.mp4`;
  try {
    await env.MEDIA.put(key, request.body, {
      httpMetadata: {
        contentType: "video/mp4",
        cacheControl: "public, max-age=31536000, immutable",
      },
      customMetadata: {
        uid,
        kind: "video",
      },
    });
  } catch (cause) {
    console.warn("r2 put failed", cause);
    return reply({ error: "upload_failed" }, 502);
  }

  const obj = await env.MEDIA.get(key);
  if (!obj) {
    return reply({ error: "upload_failed" }, 502);
  }

  if (obj.size <= 0 || obj.size > MAX_VIDEO_BYTES) {
    await env.MEDIA.delete(key);
    return reply({ error: "bad_size" }, 413);
  }

  // Sniff at most 2 MB for ftyp + mvhd; duration boxes sit early in typical exports.
  const sampleCap = Math.min(obj.size, 2 * 1024 * 1024);
  const sample = obj.body
    ? await readBodyCapped(obj.body, sampleCap)
    : new Uint8Array(await obj.arrayBuffer());
  if (sample === null || sample.byteLength === 0) {
    await env.MEDIA.delete(key);
    return reply({ error: "bad_size" }, 413);
  }

  if (!looksLikeMp4Container(sample)) {
    await env.MEDIA.delete(key);
    return reply({ error: "bad_magic" }, 415);
  }

  const durationMs = mp4DurationMs(sample);
  if (durationMs == null || durationMs <= 0) {
    await env.MEDIA.delete(key);
    return reply({ error: "bad_duration" }, 415);
  }
  if (durationMs > MAX_VIDEO_DURATION_MS) {
    await env.MEDIA.delete(key);
    return reply({ error: "bad_duration", durationMs }, 413);
  }
  const claimed = Number(request.headers.get("X-MapTalk-Duration-Ms") ?? "");
  if (Number.isFinite(claimed) && claimed > 0) {
    if (Math.abs(claimed - durationMs) > 1_500) {
      await env.MEDIA.delete(key);
      return reply({ error: "duration_mismatch", durationMs, claimed }, 400);
    }
  }

  const base = env.PUBLIC_BASE_URL.replace(/\/$/, "");
  const publicUrl = `${base}/${key}`;
  void recordSample(env, "video", "size", obj.size);
  void recordSample(env, "video", "durationMs", durationMs);
  return reply({ url: publicUrl, path: key });
}

/** Read the body in chunks; return null if it exceeds maxBytes. */
async function readBodyCapped(
  body: ReadableStream<Uint8Array>,
  maxBytes: number,
): Promise<Uint8Array | null> {
  const reader = body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      total += value.byteLength;
      if (total > maxBytes) {
        await reader.cancel();
        return null;
      }
      chunks.push(value);
    }
  } catch {
    await reader.cancel().catch(() => undefined);
    return null;
  }
  return concat(chunks, total);
}

function concat(chunks: Uint8Array[], total: number): Uint8Array {
  const out = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    out.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return out;
}

async function allowUpload(
  env: Env,
  uid: string,
  kind: UploadKind,
): Promise<{ ok: true } | { ok: false; scope: string }> {
  if (env.RATE) {
    return allowUploadKv(env.RATE, uid, kind);
  }
  return allowUploadMemory(uid, kind);
}

async function allowUploadKv(
  kv: KVNamespace,
  uid: string,
  kind: UploadKind,
): Promise<{ ok: true } | { ok: false; scope: string }> {
  const now = Date.now();
  const totalKey = `rate:total:${uid}`;
  const kindKey = kind === "audio" || kind === "video" ? `rate:${kind}:${uid}` : null;
  const kindMax = kind === "audio" ? RATE_MAX_AUDIO : kind === "video" ? RATE_MAX_VIDEO : null;

  const total = pruneStamps(parseStamps(await kv.get(totalKey)), now);
  if (total.length >= RATE_MAX_TOTAL) {
    await kv.put(totalKey, JSON.stringify(total), { expirationTtl: 700 });
    return { ok: false, scope: "total" };
  }

  if (kindKey && kindMax != null) {
    const scoped = pruneStamps(parseStamps(await kv.get(kindKey)), now);
    if (scoped.length >= kindMax) {
      await kv.put(kindKey, JSON.stringify(scoped), { expirationTtl: 700 });
      return { ok: false, scope: kind };
    }
    scoped.push(now);
    await kv.put(kindKey, JSON.stringify(scoped), { expirationTtl: 700 });
  }

  total.push(now);
  await kv.put(totalKey, JSON.stringify(total), { expirationTtl: 700 });
  return { ok: true };
}

function parseStamps(raw: string | null): number[] {
  if (!raw) return [];
  try {
    const parsed = JSON.parse(raw) as unknown;
    if (!Array.isArray(parsed)) return [];
    return parsed.filter((n): n is number => typeof n === "number");
  } catch {
    return [];
  }
}

function pruneStamps(stamps: number[], now: number): number[] {
  return stamps.filter((t) => now - t < RATE_WINDOW_MS);
}

function allowUploadMemory(
  uid: string,
  kind: UploadKind,
): { ok: true } | { ok: false; scope: string } {
  const now = Date.now();
  pruneMap(recentTotal, now);
  pruneMap(recentAudio, now);
  pruneMap(recentVideo, now);

  const total = (recentTotal.get(uid) ?? []).filter((t) => now - t < RATE_WINDOW_MS);
  if (total.length >= RATE_MAX_TOTAL) {
    recentTotal.set(uid, total);
    return { ok: false, scope: "total" };
  }

  if (kind === "audio") {
    const audio = (recentAudio.get(uid) ?? []).filter((t) => now - t < RATE_WINDOW_MS);
    if (audio.length >= RATE_MAX_AUDIO) {
      recentAudio.set(uid, audio);
      return { ok: false, scope: "audio" };
    }
    audio.push(now);
    recentAudio.set(uid, audio);
  }

  if (kind === "video") {
    const video = (recentVideo.get(uid) ?? []).filter((t) => now - t < RATE_WINDOW_MS);
    if (video.length >= RATE_MAX_VIDEO) {
      recentVideo.set(uid, video);
      return { ok: false, scope: "video" };
    }
    video.push(now);
    recentVideo.set(uid, video);
  }

  total.push(now);
  recentTotal.set(uid, total);
  return { ok: true };
}

function pruneMap(map: Map<string, number[]>, now: number) {
  for (const [uid, stamps] of map) {
    const kept = stamps.filter((t) => now - t < RATE_WINDOW_MS);
    if (kept.length === 0) map.delete(uid);
    else map.set(uid, kept);
  }
  if (map.size <= RATE_UID_CAP) return;
  const ranked = [...map.entries()].sort(
    (a, b) => (a[1][a[1].length - 1] ?? 0) - (b[1][b[1].length - 1] ?? 0),
  );
  for (let i = 0; i < ranked.length - RATE_UID_CAP; i++) {
    map.delete(ranked[i][0]);
  }
}

function metricBucket(status: number): string {
  if (status >= 200 && status < 300) return "ok";
  if (status === 413 || status === 415 || status === 429) return String(status);
  if (status >= 400 && status < 500) return "4xx";
  if (status >= 500) return "5xx";
  return "other";
}

async function bumpMetric(env: Env, kind: UploadKind, status: number): Promise<void> {
  if (!env.RATE) return;
  const day = new Date().toISOString().slice(0, 10);
  const key = `metrics:${day}:${kind}:${metricBucket(status)}`;
  try {
    const next = Number((await env.RATE.get(key)) ?? "0") + 1;
    await env.RATE.put(key, String(next), { expirationTtl: 60 * 60 * 24 * 14 });
  } catch (cause) {
    console.warn("metric bump failed", cause);
  }
}

const SAMPLE_CAP = 200;

async function recordSample(
  env: Env,
  kind: UploadKind,
  field: "size" | "durationMs",
  value: number,
): Promise<void> {
  if (!env.RATE || !Number.isFinite(value) || value < 0) return;
  const day = new Date().toISOString().slice(0, 10);
  const key = `metrics:${day}:${kind}:${field}:samples`;
  try {
    const samples = parseStamps(await env.RATE.get(key));
    samples.push(Math.round(value));
    while (samples.length > SAMPLE_CAP) samples.shift();
    await env.RATE.put(key, JSON.stringify(samples), { expirationTtl: 60 * 60 * 24 * 14 });
  } catch (cause) {
    console.warn("sample record failed", cause);
  }
}

function percentile(samples: number[], p: number): number | null {
  if (samples.length === 0) return null;
  const sorted = [...samples].sort((a, b) => a - b);
  const rank = Math.ceil((p / 100) * sorted.length) - 1;
  return sorted[Math.max(0, Math.min(sorted.length - 1, rank))] ?? null;
}

async function readMetrics(kv: KVNamespace): Promise<Record<string, unknown>> {
  const day = new Date().toISOString().slice(0, 10);
  const kinds: UploadKind[] = ["image", "audio", "video"];
  const buckets = ["ok", "413", "415", "429", "4xx", "5xx"];
  const counts: Record<string, number> = {};
  for (const kind of kinds) {
    for (const bucket of buckets) {
      const raw = await kv.get(`metrics:${day}:${kind}:${bucket}`);
      if (raw) counts[`${kind}_${bucket}`] = Number(raw);
    }
  }

  const percentiles: Record<string, number> = {};
  for (const kind of kinds) {
    for (const field of ["size", "durationMs"] as const) {
      if (kind !== "video" && field === "durationMs") continue;
      const samples = parseStamps(await kv.get(`metrics:${day}:${kind}:${field}:samples`));
      const p50 = percentile(samples, 50);
      const p95 = percentile(samples, 95);
      if (p50 != null) percentiles[`${kind}_${field}_p50`] = p50;
      if (p95 != null) percentiles[`${kind}_${field}_p95`] = p95;
      if (samples.length > 0) percentiles[`${kind}_${field}_n`] = samples.length;
    }
  }

  return { day, counts, percentiles };
}

function looksLikeJpeg(bytes: Uint8Array): boolean {
  return bytes.length >= 3 && bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff;
}

function looksLikeMp4Container(bytes: Uint8Array): boolean {
  if (bytes.length < 12) return false;
  const box = String.fromCharCode(bytes[4], bytes[5], bytes[6], bytes[7]);
  return box === "ftyp";
}

/** Parse movie duration from the first mvhd box (ISO BMFF). */
function mp4DurationMs(bytes: Uint8Array): number | null {
  const limit = Math.min(bytes.length - 8, 2 * 1024 * 1024);
  for (let i = 0; i < limit; i++) {
    if (
      bytes[i] === 0x6d &&
      bytes[i + 1] === 0x76 &&
      bytes[i + 2] === 0x68 &&
      bytes[i + 3] === 0x64
    ) {
      const version = bytes[i + 4];
      if (version === 0 && i + 24 <= bytes.length) {
        const timescale = readU32(bytes, i + 16);
        const duration = readU32(bytes, i + 20);
        if (timescale <= 0) return null;
        return Math.round((duration / timescale) * 1000);
      }
      if (version === 1 && i + 36 <= bytes.length) {
        const timescale = readU32(bytes, i + 24);
        const duration = readU64(bytes, i + 28);
        if (timescale <= 0) return null;
        return Math.round((Number(duration) / timescale) * 1000);
      }
      return null;
    }
  }
  return null;
}

function readU32(bytes: Uint8Array, offset: number): number {
  return (
    ((bytes[offset] << 24) |
      (bytes[offset + 1] << 16) |
      (bytes[offset + 2] << 8) |
      bytes[offset + 3]) >>>
    0
  );
}

function readU64(bytes: Uint8Array, offset: number): bigint {
  const hi = BigInt(readU32(bytes, offset));
  const lo = BigInt(readU32(bytes, offset + 4));
  return (hi << 32n) | lo;
}

function corsHeaders(): HeadersInit {
  return {
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Methods": "POST, PUT, OPTIONS, GET, DELETE",
    "Access-Control-Allow-Headers": "Authorization, Content-Type, X-MapTalk-Duration-Ms, X-MapTalk-Admin",
    "Access-Control-Max-Age": "86400",
  };
}

function json(body: unknown, status = 200, extraHeaders: HeadersInit = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "Content-Type": "application/json",
      ...corsHeaders(),
      ...extraHeaders,
    },
  });
}

async function verifyFirebaseIdToken(token: string, projectId: string): Promise<string> {
  const parts = token.split(".");
  if (parts.length !== 3) throw new Error("malformed");

  const header = JSON.parse(atobUrl(parts[0])) as { kid?: string; alg?: string };
  if (header.alg !== "RS256" || !header.kid) throw new Error("bad_header");

  const jwks = await fetchGoogleJwks();
  const jwk = jwks.keys.find((k) => k.kid === header.kid);
  if (!jwk) throw new Error("unknown_kid");

  const key = await crypto.subtle.importKey(
    "jwk",
    jwk,
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["verify"],
  );

  const data = new TextEncoder().encode(`${parts[0]}.${parts[1]}`);
  const signature = b64urlToBytes(parts[2]);
  const ok = await crypto.subtle.verify("RSASSA-PKCS1-v1_5", key, signature, data);
  if (!ok) throw new Error("bad_signature");

  const payload = JSON.parse(atobUrl(parts[1])) as {
    aud?: string;
    iss?: string;
    exp?: number;
    sub?: string;
  };
  const now = Math.floor(Date.now() / 1000);
  if (payload.aud !== projectId) throw new Error("bad_aud");
  if (payload.iss !== `https://securetoken.google.com/${projectId}`) throw new Error("bad_iss");
  if (!payload.sub || !payload.exp || payload.exp < now) throw new Error("expired");
  return payload.sub;
}

type Jwks = { keys: JsonWebKey & { kid?: string }[] };

let cachedJwks: { at: number; value: Jwks } | null = null;

async function fetchGoogleJwks(): Promise<Jwks> {
  const now = Date.now();
  if (cachedJwks && now - cachedJwks.at < 60 * 60 * 1000) return cachedJwks.value;
  const res = await fetch(
    "https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com",
  );
  if (!res.ok) throw new Error(`jwks_${res.status}`);
  const value = (await res.json()) as Jwks;
  cachedJwks = { at: now, value };
  return value;
}

function atobUrl(input: string): string {
  const padded = input.replace(/-/g, "+").replace(/_/g, "/") + "===".slice((input.length + 3) % 4);
  return atob(padded);
}

function b64urlToBytes(input: string): ArrayBuffer {
  const bin = atobUrl(input);
  const bytes = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
  return bytes.buffer;
}
