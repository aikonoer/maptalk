# MapTalk Cloud Functions

Push fan-out when a new message is posted to a thread.

## Prerequisites

1. Firebase project **maptalk-app** on the **Blaze** plan (Functions + FCM Admin).
2. In Firebase Console → Project settings → Cloud Messaging:
   - Upload an **APNs Authentication Key** (.p8) for iOS.
3. Node 20+.

## Deploy

```bash
cd firebase/functions
npm install
npm run build
cd ..
firebase deploy --only functions,firestore:rules --project maptalk-app
```

The function `onThreadMessageCreated` reads `threads/{id}/subscribers`, loads each
subscriber's `users/{uid}/devices` tokens, and sends an FCM multicast (excluding the
message author).
