# MapTalk data model and query strategy

Both apps talk to the same Firestore database and must stay behaviourally identical.
This document is the contract; if you change something here, change it in both apps.

## Collections

### `threads/{threadId}`

A public conversation pinned to a single point on the map.

| Field           | Type      | Notes                                                        |
| --------------- | --------- | ------------------------------------------------------------ |
| `title`         | string    | 1-80 chars. Shown on the bubble and in the thread header.     |
| `kind`          | string    | One of `event`, `notice`, `traffic`, `general`. Display only. |
| `lat`           | number    | -90..90. Used for the client-side distance filter.           |
| `lng`           | number    | -180..180.                                                   |
| `geohash`       | string    | Precision 10, computed from `lat`/`lng`. Query key.          |
| `authorId`      | string    | Must equal the writer's `auth.uid`.                          |
| `authorName`    | string    | 1-24 chars, denormalised from `users/{uid}.displayName`.      |
| `createdAt`     | timestamp | Server timestamp, must equal `request.time`.                 |
| `lastMessageAt` | timestamp | Server timestamp, bumped on every new message.               |
| `messageCount`  | number    | Starts at 0, only ever incremented by exactly 1.             |

### `threads/{threadId}/messages/{messageId}`

| Field               | Type      | Notes                                                          |
| ------------------- | --------- | -------------------------------------------------------------- |
| `text`              | string    | Caption or body. Empty for voice; sticker glyph for stickers.  |
| `messageKind`       | string    | `text`, `image`, `voice`, or `sticker`. Defaults to `text`.    |
| `imagePath`         | string?   | Relative local path or remote URL for image messages.          |
| `imageWidth`        | number?   | Pixel width of the compressed image.                           |
| `imageHeight`       | number?   | Pixel height of the compressed image.                          |
| `audioPath`         | string?   | Relative local path or remote URL for voice notes.             |
| `audioDurationMs`   | number?   | Duration in ms (1–60000).                                      |
| `replyToId`         | string?   | Id of the message being replied to.                            |
| `replyToText`       | string?   | Snapshot of reply body (≤200 chars).                           |
| `replyToAuthorName` | string?   | Snapshot of reply author name (≤64 chars).                     |
| `reactions`         | map?      | emoji → list of uids. Only field updatable after create.       |
| `authorId`          | string    | Must equal the writer's `auth.uid`.                            |
| `authorName`        | string    | 1-24 chars.                                                    |
| `createdAt`         | timestamp | Server timestamp, equals `request.time`.                       |

Images are compressed on the device (max edge 1280 px, JPEG ~0.72) before storage.
Voice notes are AAC/M4A, max ~60s / 1 MB. Stickers are curated emoji glyphs (no pack download).

| Mode | Where bytes live | What path fields hold |
| ---- | ---------------- | --------------------- |
| Local demo | App files directory | Relative filename |
| Emulator | Firebase Storage emulator | Emulator download URL |
| Live | Cloudflare R2 via `workers/media` | `https://pub-….r2.dev/threads/…/….jpg` or `.m4a` |

R2 is the production store (zero egress). The Worker verifies the Firebase ID token before
accepting JPEG or audio. Object layout: `threads/{threadId}/{messageId}.{jpg\|m4a}`.

Firebase Storage rules in `firebase/storage.rules` remain for the emulator path.

### `users/{uid}`

| Field         | Type      | Notes                                    |
| ------------- | --------- | ---------------------------------------- |
| `displayName` | string    | 1-24 chars, chosen at first launch.      |
| `createdAt`   | timestamp | Server timestamp.                        |

### `users/{uid}/blocks/{blockedUid}`

Viewer-private. Clients hide that author's threads and messages.

| Field         | Type      | Notes                                      |
| ------------- | --------- | ------------------------------------------ |
| `blockedUid`  | string    | Must equal the document id; not yourself.  |
| `displayName` | string    | Snapshot of their name when blocked.       |
| `createdAt`   | timestamp | Server timestamp.                          |

### `users/{uid}/reports/{reportId}`

Append-only reports owned by the reporter. Admin can query as a collection group later.

| Field            | Type      | Notes                                                         |
| ---------------- | --------- | ------------------------------------------------------------- |
| `targetType`     | string    | `message`, `thread`, or `user`.                               |
| `targetId`       | string    | Message id, thread id, or uid.                                |
| `threadId`       | string    | Required (non-empty) for `message`; empty otherwise.          |
| `targetAuthorId` | string    | Author being reported; must not be the reporter.              |
| `reason`         | string    | `spam`, `harassment`, `inappropriate`, or `other`.            |
| `createdAt`      | timestamp | Server timestamp.                                             |

### `users/{uid}/devices/{deviceId}`

FCM registration tokens for push. Doc id is a stable per-install device id.

| Field       | Type      | Notes                                      |
| ----------- | --------- | ------------------------------------------ |
| `token`     | string    | FCM registration token (8–4096 chars).     |
| `platform`  | string    | `ios` or `android`.                        |
| `updatedAt` | timestamp | Server timestamp on every refresh.         |

### `threads/{threadId}/subscribers/{uid}`

Who should get notified about new messages in this thread. Clients upsert when the user
opens the thread or posts. Cloud Functions fan out FCM to these uids (excluding the
message author).

| Field          | Type      | Notes                          |
| -------------- | --------- | ------------------------------ |
| `subscribedAt` | timestamp | Server timestamp.              |

## Writes

Creating a thread is a single `set` on a new document with `messageCount = 0` and both
timestamps set to the server timestamp.

Posting a message is a single atomic batch of two writes:

1. `create` the message document.
2. `update` the parent thread with `lastMessageAt = serverTimestamp()` and
   `messageCount = increment(1)`.

The security rules only let an update touch `lastMessageAt` and `messageCount`, and only
when the count goes up by exactly one.

Message documents may also be updated solely to change the `reactions` map (emoji → uids).
All other message fields are immutable after create.

Push uses Cloud Functions (`firebase/functions`) on Blaze: a message create trigger fans out
FCM to `threads/{id}/subscribers` (excluding the author), using tokens in
`users/{uid}/devices`.

## Reading the map

The visible radius of the camera decides which of two queries runs, so panning out to the
whole world never turns into an unbounded read.

| Visible radius | Mode           | Query                                                                 |
| -------------- | -------------- | --------------------------------------------------------------------- |
| <= 50 km       | `Nearby`       | Geohash bounds around the viewport centre, then a client distance filter |
| > 50 km        | `GlobalRecent` | `orderBy('lastMessageAt', desc).limit(200)`                           |

`Nearby` follows the standard Firestore geohash recipe: compute the bound pairs for the
centre and radius (up to 9 pairs), run one `orderBy('geohash').startAt(..).endAt(..)`
query per pair with a 40 document limit, merge the results, then drop the geohash false
positives by real distance. Every query is a snapshot listener, so replies and new threads
appear without a refresh. Camera movement is debounced by 300 ms and the previous
listeners are detached before new ones attach.

Only single field indexes are needed (`geohash` ascending and `lastMessageAt`
descending), and Firestore creates those automatically.

## Clustering the bubbles

Both apps group markers by geohash prefix instead of using a clustering library, so the
map looks the same on either platform. The prefix length comes from the visible radius:

| Visible radius | Geohash prefix | Approximate cell size |
| -------------- | -------------- | --------------------- |
| > 500 km       | 2              | ~1250 km              |
| > 100 km       | 3              | ~156 km               |
| > 25 km        | 4              | ~39 km                |
| > 5 km         | 5              | ~5 km                 |
| > 1 km         | 6              | ~1.2 km               |
| <= 1 km        | none           | individual threads    |

A cluster is drawn at the average position of its members and shows the member count.
Tapping a cluster zooms in; tapping a single thread opens the chat.

Reference implementations, which must stay in sync:

- Android: `android/app/src/main/java/app/maptalk/geo/Viewport.kt`
- iOS: `ios/MapTalk/Core/Viewport.swift`
