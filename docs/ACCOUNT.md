# MapTalk account model

Contract for identity, profile, and auth. Both apps must stay aligned.
Anonymous bootstrap stays for now; durable accounts + per-post anonymity come later
(see [`PARKING.md`](PARKING.md) § Account).

---

## Auth flows (Firebase)

| Flow | What happens | Status |
| ---- | ------------ | ------ |
| **Bootstrap** | `signInAnonymously()` if no user → stable `uid` | Live |
| **Choose name** | First launch writes `users/{uid}.displayName` | Live |
| **Upgrade / “Sign up”** | Link Apple (iOS) or Google (Android) to the same anonymous `uid` | Live (Apple); Google on Android |
| **Sign in (returning)** | Open app → existing Firebase session restores; or link/sign-in with Apple/Google | Partial — no email/password; credential-already-in-use merge parked |
| **Sign out** | `Auth.signOut()` then re-bootstrap anonymous or require sign-in | Parked |
| **Delete account** | Delete `users/{uid}` (+ subcollections), revoke providers, `user.delete()` | Parked |
| **Per-post anonymous** | One `uid`; writer chooses display name vs “Anonymous” on each create | Parked |

There is **no** separate email/password sign-up. “Sign up” = link a provider to the anonymous account. “Log in” on a new device = Sign in with Apple/Google (needs merge handling when that provider already owns another uid).

---

## `users/{uid}` fields

### Ship / near-term (profile)

| Field | Type | Notes |
| ----- | ---- | ----- |
| `displayName` | string | 1–24 chars. Shown on chats when not posting anonymously. |
| `photoURL` | string? | HTTPS URL (R2 or Storage) for profile photo. Nil → initials avatar. |
| `photoPath` | string? | Optional storage key `users/{uid}/avatar.jpg` for deletes. |
| `createdAt` | timestamp | First profile write. |
| `updatedAt` | timestamp | Bumped on profile edits. |

### Auth metadata (mostly from Firebase Auth, not duplicated)

| Concept | Source | Notes |
| ------- | ------ | ----- |
| `uid` | Auth | Document id. |
| `isAnonymous` | Auth | True until a provider is linked. |
| Providers | Auth `providerData` | `apple.com`, `google.com`. |
| Email | Auth (Apple/Google) | May be private relay; don’t require it in Firestore. |

### Preferences (later)

| Field | Type | Notes |
| ----- | ---- | ----- |
| `defaultPostAnonymous` | bool | Default for the composer toggle. Default `false`. |
| `pushEnabled` | bool | Master push preference (devices still hold FCM tokens). |

### Out of scope for MapTalk (don’t add)

Email/password, phone number as login, birthday, gender, full address, follower counts, public bio essays, OAuth beyond Apple/Google unless needed.

---

## Account settings UI (standard sections)

Mirror common mobile account screens, kept kid-friendly and map-chat specific.

### 1. Profile
- Avatar (tap → change / remove photo)
- Display name
- Provider status (“Anonymous” / “Apple” / “Google” / “Local demo”)

### 2. Sign-in & security
- Continue with Apple (if anonymous) — iOS
- Continue with Google (if anonymous) — Android
- Linked providers list
- Sign out (parked)
- Delete account (parked)

### 3. Posting
- Post anonymously (default) — Soon
- Copy: you’ll keep an account; choose per chat/reply later

### 4. Safety
- Blocked people
- (Later) Muted chats, report history

### 5. Notifications (later)
- Push on/off, per-type if needed

### 6. About / legal (later)
- Privacy, terms, version

---

## Denormalisation

Threads and messages keep `authorId` + `authorName` (and later optional `authorPhotoURL` snapshot on new writes only). Changing display name or photo does **not** rewrite old messages; new posts use the current profile. Anonymous posts use a fixed public label (e.g. `Anonymous`) and omit or hide photo.

---

## Storage for photos

| Mode | Where |
| ---- | ----- |
| Local demo | App files dir; `photoURL` may be a local path or file URL |
| Live | R2 (preferred) `users/{uid}/avatar.jpg` via Worker, or Firebase Storage until avatar presign exists |

Max edge ~512 px, JPEG ~0.8 — small enough for avatars on the map and in chat.
