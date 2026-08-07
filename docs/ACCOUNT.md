# MapTalk account model

Contract for identity, profile, and auth. Both apps must stay aligned.
Anonymous bootstrap stays for launch; per-post anonymity comes later
(see [`PARKING.md`](PARKING.md) § Account).

---

## Auth flows (Firebase)

| Flow | What happens | Status |
| ---- | ------------ | ------ |
| **Bootstrap** | `signInAnonymously()` if no user → stable `uid` | Live |
| **Welcome (first launch)** | Brand screen: Continue with Apple/Google **or** explore as guest → then display name. Anonymous `uid` already exists. Skip if profile already has a name. | Live (iOS + Android) |
| **Choose name** | Writes `users/{uid}.displayName` | Live |
| **Upgrade / “Sign up”** | Link Apple (iOS) or Google (Android) to the same anonymous `uid` — from welcome **or** Account | Live |
| **Sign in (returning)** | Open app → existing Firebase session restores; or link/sign-in with Apple/Google | Partial — no email/password; credential-already-in-use merge parked |
| **Sign out** | Linked: `Auth.signOut()` → fresh anonymous → welcome. Local demo: clear on-device profile. Guest anonymous: no Sign out row (use Delete). | Live (iOS + Android) |
| **Delete account** | Wipe profile (blocks, devices, avatar, `users/{uid}`), `user.delete()`, fresh anonymous → welcome. Provider reauth before wipe when linked. Public posts may remain with denormalised names. | Live (iOS + Android); rules allow own-user delete |
| **Per-post anonymous** | One `uid`; writer chooses display name vs “Anonymous” on each create | Parked |

There is **no** separate email/password sign-up. “Sign up” = link a provider to the anonymous account. “Log in” on a new device = Sign in with Apple/Google (needs merge handling when that provider already owns another uid). Guest (“Explore without an account”) stays on the anonymous Firebase user until they link from Account.

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

### 1. Profile
- Avatar (tap → change / remove photo)
- Display name
- Provider status (“Anonymous” / “Apple” / “Google” / “Local demo”)

### 2. Sign-in & security
- Continue with Apple (if anonymous) — iOS Live
- Continue with Google (if anonymous) — Android
- Linked providers list
- Sign out (linked / local demo reset)
- Delete account (always)

### 3. Safety
- Blocked people (count in row)

### 4. About
- Version
- Privacy Policy (in-app)
- Terms of Use (in-app)

### Parked (not shown)
- Post anonymously (default)
- Notifications master toggle
- Hosted privacy/terms URLs (replace in-app copy when `maptalk.app` is live)
- Credential merge when Apple/Google already owns another uid

---

## Denormalisation

Threads and messages keep `authorId` + `authorName` (and later optional `authorPhotoURL` snapshot on new writes only). Changing display name or photo does **not** rewrite old messages; new posts use the current profile. Anonymous posts use a fixed public label (e.g. `Anonymous`) and omit or hide photo. Account deletion does **not** rewrite historical posts.

---

## Storage for photos

| Mode | Where |
| ---- | ----- |
| Local demo | App files dir; `photoURL` may be a local path or file URL |
| Live | R2 (preferred) `users/{uid}/avatar.jpg` via Worker, or Firebase Storage until avatar presign exists |

Max edge ~512 px, JPEG ~0.8 — small enough for avatars on the map and in chat.

---

## Delete account checklist (client)

1. Apple reauth if linked  
2. Delete `users/{uid}/blocks/*` and `users/{uid}/devices/*`  
3. Delete Storage avatar  
4. Delete `users/{uid}` (rules: own uid only)  
5. `Auth.currentUser.delete()`  
6. Clear `maptalk.didChooseAuthPath`  
7. Sign in anonymously → SessionStore restart → welcome  
