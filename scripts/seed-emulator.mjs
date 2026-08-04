#!/usr/bin/env node
//
// Fills the Firestore emulator with sample threads and conversations so both apps have
// something to show without a Firebase project. Run through scripts/mock-data.sh, or directly:
//
//   node scripts/seed-emulator.mjs --lat -33.8688 --lng 151.2093
//
// Writes go through the emulator's REST API with the owner token, which bypasses the security
// rules, so the seeded documents can carry timestamps and counters a client would not be
// allowed to set directly.

const args = parseArgs(process.argv.slice(2));
const host = args.host ?? 'localhost:8080';
const projectId = args.project ?? 'maptalk-qa';
const base = `http://${host}/v1/projects/${projectId}/databases/(default)/documents`;

// Where the dense cluster goes: Sydney CBD unless you pass somewhere else. Point your simulator
// at the same place (see the README) or just pan there.
const center = { lat: Number(args.lat ?? -33.8688), lng: Number(args.lng ?? 151.2093) };

const BASE32 = '0123456789bcdefghjkmnpqrstuvwxyz';

/**
 * The same geohash the two apps compute, because the map finds threads by hashed prefix: a
 * seeded thread with a hash from a different algorithm would sit on the map invisibly.
 * `verifyGeoHash` below pins it to the fixtures both test suites use.
 */
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
      value = value << 1;
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

function verifyGeoHash() {
  const fixtures = [
    [57.64911, 10.40744, 11, 'u4pruydqqvj'],
    [0, 0, 12, '7zzzzzzzzzzz'],
    [-33.8688, 151.2093, 10, 'r3gx2f77bn'],
    [51.5074, -0.1278, 10, 'gcpvj0duq5'],
    [37.7749, -122.4194, 10, '9q8yyk8ytp'],
  ];
  for (const [lat, lng, precision, expected] of fixtures) {
    const actual = geoHash(lat, lng, precision);
    if (actual !== expected) {
      throw new Error(`geohash drift: ${lat},${lng} gave ${actual}, apps expect ${expected}`);
    }
  }
}

/** Metres offset from a point, so the fixtures read in metres rather than fractions of a degree. */
function offset({ lat, lng }, northMetres, eastMetres) {
  return {
    lat: lat + northMetres / 111_320,
    lng: lng + eastMetres / (111_320 * Math.cos((lat * Math.PI) / 180)),
  };
}

const minutesAgo = (minutes) => new Date(Date.now() - minutes * 60_000);

/**
 * The conversations. Each is a thread plus its replies, with the newest activity first so the
 * zoomed-out "most active worldwide" view has a sensible order.
 */
function conversations() {
  const near = (north, east) => offset(center, north, east);

  return [
    {
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
      title: 'Brownout in Mabolo, anyone know how long?',
      kind: 'notice',
      at: near(-620, -80),
      author: 'Bea',
      ageMinutes: 150,
      replies: [['Bea', 'VEC notice says back by 4', 140]],
    },
    {
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
      title: 'Queue for SM Seaside cinema is already around the corner',
      kind: 'general',
      at: near(-410, 660),
      author: 'Fen',
      ageMinutes: 70,
      replies: [['Fen', 'Maybe 40 minutes from where I am', 68]],
    },

    // A few more cities, so zooming out shows the worldwide activity query doing its job.
    {
      title: 'Tram replacement buses all weekend on Swanston',
      kind: 'notice',
      at: { lat: -37.8136, lng: 144.9631 },
      author: 'Wes',
      ageMinutes: 300,
      replies: [['Wes', 'Stop moved a block north', 290]],
    },
    {
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
    {
      title: 'Fog rolled in, bridge is completely hidden',
      kind: 'general',
      at: { lat: 37.7749, lng: -122.4194 },
      author: 'Cleo',
      ageMinutes: 420,
      replies: [['Cleo', 'Can hear the horns from Fort Mason', 410]],
    },
    {
      title: 'Cherry blossoms are past peak in the park',
      kind: 'general',
      at: { lat: 35.6762, lng: 139.6503 },
      author: 'Rin',
      ageMinutes: 500,
      replies: [['Rin', 'Still worth it early morning, no crowds', 495]],
    },
    {
      title: 'Power out along the avenue, traffic lights dark',
      kind: 'traffic',
      at: { lat: 40.7128, lng: -74.006 },
      author: 'Marc',
      ageMinutes: 60,
      replies: [
        ['Marc', 'Treat every junction as a stop', 58],
        ['Dee', 'Crews just showed up', 25],
      ],
    },
  ];
}

async function write(path, fields) {
  const response = await fetch(`${base}/${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: 'Bearer owner' },
    body: JSON.stringify({ fields }),
  });
  if (!response.ok) {
    throw new Error(`${path}: ${response.status} ${await response.text()}`);
  }
  return response.json();
}

async function clearFirestore() {
  const response = await fetch(
    `http://${host}/emulator/v1/projects/${projectId}/databases/(default)/documents`,
    { method: 'DELETE' },
  );
  if (!response.ok) {
    throw new Error(`could not clear the emulator: ${response.status}`);
  }
}

const text = (stringValue) => ({ stringValue });
const number = (doubleValue) => ({ doubleValue });
const count = (value) => ({ integerValue: String(value) });
const time = (date) => ({ timestampValue: date.toISOString() });

async function seed() {
  verifyGeoHash();

  if (!args.keep) {
    await clearFirestore();
  }

  const threads = conversations();
  let messageTotal = 0;

  for (const [index, conversation] of threads.entries()) {
    const authorId = `seed-user-${index}`;
    const lastReply = conversation.replies.at(-1);
    const threadId = `seed-thread-${String(index).padStart(2, '0')}`;

    await write(`threads?documentId=${threadId}`, {
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
    });

    for (const [order, [name, body, ageMinutes]] of conversation.replies.entries()) {
      await write(
        `threads/${threadId}/messages?documentId=msg-${String(order).padStart(2, '0')}`,
        {
          text: text(body),
          authorId: text(`seed-user-${name.toLowerCase().replace(/[^a-z]/g, '')}`),
          authorName: text(name),
          createdAt: time(minutesAgo(ageMinutes)),
        },
      );
      messageTotal += 1;
    }
  }

  const nearby = threads.filter((c) => distance(center, c.at) < 5_000).length;
  console.log(
    `Seeded ${threads.length} threads and ${messageTotal} messages into ${projectId}: ` +
      `${nearby} clustered around ${center.lat.toFixed(4)}, ${center.lng.toFixed(4)} ` +
      `and the rest spread across other cities.`,
  );
}

function distance(a, b) {
  const radius = 6_371_000;
  const toRadians = (degrees) => (degrees * Math.PI) / 180;
  const latDelta = toRadians(b.lat - a.lat);
  const lngDelta = toRadians(b.lng - a.lng);
  const h =
    Math.sin(latDelta / 2) ** 2 +
    Math.cos(toRadians(a.lat)) * Math.cos(toRadians(b.lat)) * Math.sin(lngDelta / 2) ** 2;
  return 2 * radius * Math.asin(Math.sqrt(h));
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
  console.error('Is the Firestore emulator running? scripts/mock-data.sh starts it for you.');
  process.exit(1);
});
