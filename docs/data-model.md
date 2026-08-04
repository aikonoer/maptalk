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

| Field          | Type      | Notes                                                          |
| -------------- | --------- | -------------------------------------------------------------- |
| `text`         | string    | Caption or body. May be empty when `messageKind` is `image`.   |
| `messageKind`  | string    | `text` (default) or `image`.                                   |
| `imagePath`    | string?   | Relative local path or remote URL once Storage/R2 is wired.    |
| `imageWidth`   | number?   | Pixel width of the compressed image.                           |
| `imageHeight`  | number?   | Pixel height of the compressed image.                          |
| `authorId`     | string    | Must equal the writer's `auth.uid`.                            |
| `authorName`   | string    | 1-24 chars.                                                    |
| `createdAt`    | timestamp | Server timestamp, equals `request.time`.                       |

Images are compressed on the device (max edge 1280 px, JPEG ~0.72) before storage.

| Mode | Where bytes live | What `imagePath` holds |
| ---- | ---------------- | ---------------------- |
| Local demo | App files directory | Relative filename |
| Emulator / live | Firebase Storage (`threads/{threadId}/{messageId}.jpg`) | HTTPS download URL |

Cloudflare R2 can replace Storage later for free egress; the Firestore field stays a URL either way.
Storage rules live in `firebase/storage.rules` (signed-in read/write, JPEG under 2 MB).

### `users/{uid}`

| Field         | Type      | Notes                                    |
| ------------- | --------- | ---------------------------------------- |
| `displayName` | string    | 1-24 chars, chosen at first launch.      |
| `createdAt`   | timestamp | Server timestamp.                        |

## Writes

Creating a thread is a single `set` on a new document with `messageCount = 0` and both
timestamps set to the server timestamp.

Posting a message is a single atomic batch of two writes:

1. `create` the message document.
2. `update` the parent thread with `lastMessageAt = serverTimestamp()` and
   `messageCount = increment(1)`.

The security rules only let an update touch `lastMessageAt` and `messageCount`, and only
when the count goes up by exactly one, so there are no Cloud Functions and the whole
backend runs on the free Spark plan.

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
