#!/usr/bin/env node
//
// Seeds the live MapTalk Firestore project with sample threads (default: Cebu City).
// Uses your gcloud user token (project owner) so security rules are bypassed.
//
//   node scripts/seed-live.mjs
//   node scripts/seed-live.mjs --lat 10.3157 --lng 123.8854 --project maptalk-app
//
// Re-run with --keep to add without clearing existing threads (document IDs are stable).

import { execSync } from 'node:child_process';

const args = parseArgs(process.argv.slice(2));
const projectId = args.project ?? 'maptalk-app';
const center = {
  lat: Number(args.lat ?? 10.3157),
  lng: Number(args.lng ?? 123.8854),
};
const base =
  `https://firestore.googleapis.com/v1/projects/${projectId}/databases/(default)/documents`;

const BASE32 = '0123456789bcdefghjkmnpqrstuvwxyz';

function geoHash(lat, lng, precision = 10) {
  const latRange = [-90, 90];
  const lngRange = [-180, 180];
  let hash = '';
  let value = 0;
  let bits = 0;
  let even = true;

  while (hash.length < precision) {
    const range = even ? lngRange : latRange;
    const coordinate = even ? lng : lat;
    const mid = (range[0] + range[1]) / 2;
    if (coordinate > mid) {
      value = (value << 1) + 1;
      range[0] = mid;
    } else {
      value <<= 1;
      range[1] = mid;
    }
    even = !even;
    if (bits < 4) {
      bits += 1;
    } else {
      bits = 0;
      hash += BASE32[value];
      value = 0;
    }
  }
  return hash;
}

function offset({ lat, lng }, northMetres, eastMetres) {
  return {
    lat: lat + northMetres / 111_320,
    lng: lng + eastMetres / (111_320 * Math.cos((lat * Math.PI) / 180)),
  };
}

const minutesAgo = (minutes) => new Date(Date.now() - minutes * 60_000);

function conversations() {
  const near = (north, east) => offset(center, north, east);
  return [
    {
      id: 'cebu-waterfront',
      title: 'Anyone else at Waterfront for the concert?',
      kind: 'event',
      at: near(120, 80),
      author: 'Priya',
      ageMinutes: 95,
      replies: [
        ['Priya', 'Doors were quick, barely queued', 90],
        ['Marcus', 'Standing left is packed, plenty of room on the right', 74],
        ['Tomas', 'Main act on at 9 apparently', 41],
        ['Priya', 'Sound is unreal from the front', 12],
      ],
    },
    {
      id: 'cebu-itpark',
      title: 'Closing early tonight at IT Park — family thing',
      kind: 'notice',
      at: near(-260, 140),
      author: 'Loretta (Bar Sesa)',
      ageMinutes: 180,
      replies: [
        ['Loretta (Bar Sesa)', 'Kitchen is off but coffee is on until then', 175],
        ['Dan', 'Thanks for the heads up, will come by tomorrow', 120],
      ],
    },
    {
      id: 'cebu-bridge',
      title: 'Mactan–Mandaue bridge crawling, what happened?',
      kind: 'traffic',
      at: near(700, -220),
      author: 'Kenji',
      ageMinutes: 52,
      replies: [
        ['Kenji', 'Stopped for ten minutes now', 50],
        ['Ava', 'Two lanes closed, looks like a breakdown not a crash', 44],
        ['Sam', 'Took the old bridge instead, saved me 20 min', 21],
      ],
    },
    {
      id: 'cebu-carbon',
      title: 'Carbon Market is packed tonight, food stalls everywhere',
      kind: 'event',
      at: near(-90, -310),
      author: 'Rosa',
      ageMinutes: 240,
      replies: [
        ['Rosa', 'Lechon stall near the entrance is worth the queue', 230],
        ['Ellie', 'Live band started by Fuente', 66],
      ],
    },
    {
      id: 'cebu-cat',
      title: 'Lost a grey cat around Lahug, very friendly',
      kind: 'general',
      at: near(310, 420),
      author: 'Hugo',
      ageMinutes: 400,
      replies: [
        ['Hugo', 'Answers to Miso, no collar', 395],
        ['Nadia', 'Saw a grey one near JY Square an hour ago', 88],
      ],
    },
    {
      id: 'cebu-brownout',
      title: 'Brownout in Mabolo, anyone know how long?',
      kind: 'notice',
      at: near(-620, -80),
      author: 'Bea',
      ageMinutes: 150,
      replies: [['Bea', 'VEC notice says back by 4', 140]],
    },
    {
      id: 'cebu-srp',
      title: 'Pickup game at SRP courts in 20 if anyone wants in',
      kind: 'general',
      at: near(430, -540),
      author: 'Theo',
      ageMinutes: 35,
      replies: [
        ['Theo', 'Got 6, need 2 more', 33],
        ['Ines', 'On my way from Banilad', 8],
      ],
    },
    {
      id: 'cebu-seaside',
      title: 'Queue for SM Seaside cinema is already around the corner',
      kind: 'general',
      at: near(-410, 660),
      author: 'Fen',
      ageMinutes: 70,
      replies: [['Fen', 'Maybe 40 minutes from where I am', 68]],
    },
    {
      id: 'mel-tram',
      title: 'Tram replacement buses all weekend on Swanston',
      kind: 'notice',
      at: { lat: -37.8136, lng: 144.9631 },
      author: 'Wes',
      ageMinutes: 300,
      replies: [['Wes', 'Stop moved a block north', 290]],
    },
    {
      id: 'lon-food',
      title: 'Street food festival on the South Bank today',
      kind: 'event',
      at: { lat: 51.5074, lng: -0.1278 },
      author: 'Amira',
      ageMinutes: 210,
      replies: [
        ['Amira', 'Runs until 8, free entry', 205],
        ['Joe', 'Beef bowl stall is the one to beat', 130],
      ],
    },
  ];
}

function accessToken() {
  return execSync('gcloud auth print-access-token', { encoding: 'utf8' }).trim();
}

const text = (stringValue) => ({ stringValue });
const number = (doubleValue) => ({ doubleValue });
const count = (value) => ({ integerValue: String(value) });
const time = (date) => ({ timestampValue: date.toISOString() });

async function upsert(pathWithQuery, fields, token) {
  // PATCH creates-or-replaces a document at a known id.
  const [collectionPath, query] = pathWithQuery.split('?');
  const documentId = new URLSearchParams(query).get('documentId');
  const url =
    `${base}/${collectionPath}/${documentId}` +
    `?currentDocument.exists=false`;
  // Try create first; if it exists, overwrite with PATCH.
  let response = await fetch(`${base}/${collectionPath}?documentId=${documentId}`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${token}`,
    },
    body: JSON.stringify({ fields }),
  });
  if (response.status === 409) {
    response = await fetch(`${base}/${collectionPath}/${documentId}`, {
      method: 'PATCH',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${token}`,
      },
      body: JSON.stringify({ fields }),
    });
  }
  if (!response.ok) {
    throw new Error(`${pathWithQuery}: ${response.status} ${await response.text()}`);
  }
}

async function seed() {
  const token = accessToken();
  const threads = conversations();
  let messageTotal = 0;

  for (const [index, conversation] of threads.entries()) {
    const authorId = `seed-user-${index}`;
    const lastReply = conversation.replies.at(-1);
    const threadId = conversation.id;

    await upsert(`threads?documentId=${threadId}`, {
      title: text(conversation.title),
      kind: text(conversation.kind),
      lat: number(conversation.at.lat),
      lng: number(conversation.at.lng),
      geohash: text(geoHash(conversation.at.lat, conversation.at.lng)),
      authorId: text(authorId),
      authorName: text(conversation.author),
      createdAt: time(minutesAgo(conversation.ageMinutes)),
      lastMessageAt: time(minutesAgo(lastReply ? lastReply[2] : conversation.ageMinutes)),
      messageCount: count(conversation.replies.length),
    }, token);

    for (const [order, [name, body, ageMinutes]] of conversation.replies.entries()) {
      await upsert(
        `threads/${threadId}/messages?documentId=msg-${String(order).padStart(2, '0')}`,
        {
          text: text(body),
          messageKind: text('text'),
          authorId: text(`seed-user-${name.toLowerCase().replace(/[^a-z]/g, '')}`),
          authorName: text(name),
          createdAt: time(minutesAgo(ageMinutes)),
        },
        token,
      );
      messageTotal += 1;
    }
  }

  console.log(
    `Seeded ${threads.length} threads and ${messageTotal} messages into ${projectId} ` +
      `around ${center.lat.toFixed(4)}, ${center.lng.toFixed(4)}.`,
  );
}

function parseArgs(argv) {
  const parsed = {};
  for (let i = 0; i < argv.length; i += 1) {
    const key = argv[i].replace(/^--/, '');
    if (key === 'keep') {
      parsed.keep = true;
    } else {
      parsed[key] = argv[i + 1];
      i += 1;
    }
  }
  return parsed;
}

seed().catch((error) => {
  console.error(`Seeding failed: ${error.message}`);
  process.exit(1);
});
