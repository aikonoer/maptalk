/**
 * Authenticated JPEG uploads into the MapTalk R2 bucket.
 *
 * Clients send:
 *   POST /v1/images?threadId=…&messageId=…
 *   Authorization: Bearer <Firebase ID token>
 *   Content-Type: image/jpeg
 *   body = compressed JPEG bytes (<= 2 MB)
 *
 * Response: { "url": "https://pub-….r2.dev/threads/…/….jpg" }
 */

export interface Env {
  MEDIA: R2Bucket;
  FIREBASE_PROJECT_ID: string;
  PUBLIC_BASE_URL: string;
}

const MAX_BYTES = 2 * 1024 * 1024;
const KEY_RE = /^[A-Za-z0-9_-]{1,128}$/;

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);

    if (request.method === "OPTIONS") {
      return new Response(null, { status: 204, headers: corsHeaders() });
    }

    if (request.method === "GET" && url.pathname === "/health") {
      return json({ ok: true });
    }

    if (request.method === "POST" && url.pathname === "/v1/images") {
      return uploadImage(request, env, url);
    }

    return json({ error: "not_found" }, 404);
  },
} satisfies ExportedHandler<Env>;

async function uploadImage(request: Request, env: Env, url: URL): Promise<Response> {
  const auth = request.headers.get("Authorization") ?? "";
  const token = auth.startsWith("Bearer ") ? auth.slice(7).trim() : "";
  if (!token) return json({ error: "missing_token" }, 401);

  try {
    await verifyFirebaseIdToken(token, env.FIREBASE_PROJECT_ID);
  } catch (cause) {
    console.warn("token rejected", cause);
    return json({ error: "invalid_token" }, 401);
  }

  const threadId = url.searchParams.get("threadId") ?? "";
  const messageId = url.searchParams.get("messageId") ?? "";
  if (!KEY_RE.test(threadId) || !KEY_RE.test(messageId)) {
    return json({ error: "bad_ids" }, 400);
  }

  const contentType = (request.headers.get("Content-Type") ?? "").split(";")[0].trim();
  if (contentType !== "image/jpeg") {
    return json({ error: "unsupported_type" }, 415);
  }

  const bytes = new Uint8Array(await request.arrayBuffer());
  if (bytes.byteLength === 0 || bytes.byteLength > MAX_BYTES) {
    return json({ error: "bad_size" }, 413);
  }

  const key = `threads/${threadId}/${messageId}.jpg`;
  await env.MEDIA.put(key, bytes, {
    httpMetadata: { contentType: "image/jpeg", cacheControl: "public, max-age=31536000, immutable" },
  });

  const publicUrl = `${env.PUBLIC_BASE_URL.replace(/\/$/, "")}/${key}`;
  return json({ url: publicUrl, path: key });
}

function corsHeaders(): HeadersInit {
  return {
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Methods": "POST, OPTIONS, GET",
    "Access-Control-Allow-Headers": "Authorization, Content-Type",
    "Access-Control-Max-Age": "86400",
  };
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "Content-Type": "application/json",
      ...corsHeaders(),
    },
  });
}

/** Verify a Firebase ID token with Google's JWKS (no Admin SDK). */
async function verifyFirebaseIdToken(token: string, projectId: string): Promise<void> {
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
