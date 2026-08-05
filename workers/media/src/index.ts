/**
 * Authenticated media uploads into the MapTalk R2 bucket.
 *
 * POST /v1/images?threadId=&messageId=   Content-Type: image/jpeg
 * POST /v1/audio?threadId=&messageId=   Content-Type: audio/mp4 | audio/m4a | audio/aac
 * POST /v1/video?threadId=&messageId=   Content-Type: video/mp4
 *
 * Authorization: Bearer <Firebase ID token>
 *
 * Abuse caps (per isolate, soft):
 *   - 20 uploads / uid / rolling 10 minutes (all kinds)
 *   - 8 audio uploads / uid / rolling 10 minutes
 *   - 4 video uploads / uid / rolling 10 minutes
 *   - Magic-byte sniff + size caps
 */

export interface Env {
  MEDIA: R2Bucket;
  FIREBASE_PROJECT_ID: string;
  PUBLIC_BASE_URL: string;
}

const MAX_IMAGE_BYTES = 2 * 1024 * 1024;
const MAX_AUDIO_BYTES = 1 * 1024 * 1024;
const MAX_VIDEO_BYTES = 12 * 1024 * 1024;
const KEY_RE = /^[A-Za-z0-9_-]{1,128}$/;
const RATE_WINDOW_MS = 10 * 60 * 1000;
const RATE_MAX_TOTAL = 20;
const RATE_MAX_AUDIO = 8;
const RATE_MAX_VIDEO = 4;
/** Bound in-memory rate maps so a flood of uids cannot grow forever. */
const RATE_UID_CAP = 4_000;

type UploadKind = "image" | "audio" | "video";

/** uid → timestamps inside this isolate */
const recentTotal = new Map<string, number[]>();
const recentAudio = new Map<string, number[]>();
const recentVideo = new Map<string, number[]>();

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);

    if (request.method === "OPTIONS") {
      return new Response(null, { status: 204, headers: corsHeaders() });
    }

    if (request.method === "GET" && url.pathname === "/health") {
      return json({
        ok: true,
        limits: {
          maxImageBytes: MAX_IMAGE_BYTES,
          maxAudioBytes: MAX_AUDIO_BYTES,
          maxVideoBytes: MAX_VIDEO_BYTES,
          uploadsPer10Min: RATE_MAX_TOTAL,
          audioPer10Min: RATE_MAX_AUDIO,
          videoPer10Min: RATE_MAX_VIDEO,
        },
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

    if (request.method === "POST" && url.pathname === "/v1/video") {
      return upload(request, env, url, {
        kind: "video",
        kinds: ["video/mp4"],
        maxBytes: MAX_VIDEO_BYTES,
        extension: "mp4",
        contentType: "video/mp4",
      });
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
  const auth = request.headers.get("Authorization") ?? "";
  const token = auth.startsWith("Bearer ") ? auth.slice(7).trim() : "";
  if (!token) return json({ error: "missing_token" }, 401);

  let uid: string;
  try {
    uid = await verifyFirebaseIdToken(token, env.FIREBASE_PROJECT_ID);
  } catch (cause) {
    console.warn("token rejected", cause);
    return json({ error: "invalid_token" }, 401);
  }

  const rate = allowUpload(uid, spec.kind);
  if (!rate.ok) {
    return json(
      { error: "rate_limited", scope: rate.scope },
      429,
      { "Retry-After": "60" },
    );
  }

  const threadId = url.searchParams.get("threadId") ?? "";
  const messageId = url.searchParams.get("messageId") ?? "";
  if (!KEY_RE.test(threadId) || !KEY_RE.test(messageId)) {
    return json({ error: "bad_ids" }, 400);
  }

  const contentType = (request.headers.get("Content-Type") ?? "").split(";")[0].trim().toLowerCase();
  if (!spec.kinds.includes(contentType)) {
    return json({ error: "unsupported_type" }, 415);
  }

  const declared = Number(request.headers.get("Content-Length") ?? "0");
  if (declared > spec.maxBytes) {
    return json({ error: "bad_size" }, 413);
  }

  const bytes = new Uint8Array(await request.arrayBuffer());
  if (bytes.byteLength === 0 || bytes.byteLength > spec.maxBytes) {
    return json({ error: "bad_size" }, 413);
  }

  if (spec.kind === "image" && !looksLikeJpeg(bytes)) {
    return json({ error: "bad_magic" }, 415);
  }
  if ((spec.kind === "audio" || spec.kind === "video") && !looksLikeMp4Container(bytes)) {
    return json({ error: "bad_magic" }, 415);
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

  const publicUrl = `${env.PUBLIC_BASE_URL.replace(/\/$/, "")}/${key}`;
  return json({ url: publicUrl, path: key });
}

function allowUpload(uid: string, kind: UploadKind): { ok: true } | { ok: false; scope: string } {
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

/** JPEG SOI marker. */
function looksLikeJpeg(bytes: Uint8Array): boolean {
  return bytes.length >= 3 && bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff;
}

/** ISO BMFF: size + 'ftyp' within the first box (m4a / mp4). */
function looksLikeMp4Container(bytes: Uint8Array): boolean {
  if (bytes.length < 12) return false;
  const box = String.fromCharCode(bytes[4], bytes[5], bytes[6], bytes[7]);
  return box === "ftyp";
}

function corsHeaders(): HeadersInit {
  return {
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Methods": "POST, OPTIONS, GET",
    "Access-Control-Allow-Headers": "Authorization, Content-Type",
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

/** Verify a Firebase ID token; returns the subject (uid). */
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
