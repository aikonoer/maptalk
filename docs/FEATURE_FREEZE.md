# Feature freeze — store week

Target: App Store + Play submit **next week**. Freeze new product features;
ship cleanup, parity, and store requirements only.

## In for launch (both platforms)

| Area | Status |
| ---- | ------ |
| Anonymous bootstrap + welcome (Apple/Google or guest) → display name | iOS Live · Android synced |
| Map browse, place search, peeks, Live pulse, clusters | Live |
| Text + media (photo/video/voice/sticker), opening photo on new chat | Live |
| Account: profile, link provider, blocks, **Sign out**, **Delete account**, About (version + Privacy/Terms) | Live |
| Reports / blocks | Live |
| Deep links `maptalk://thread/{id}` | Live |

## Explicitly frozen (do not start before submit)

- Per-post anonymous toggle  
- Credential merge (Apple/Google already owns another uid)  
- Sign-out → return to *same* linked account without merge work  
- Hosted `maptalk.app` Privacy/Terms URLs (in-app copy ships)  
- Custom kind icon set (emoji glyphs stay)  
- Persist `placeLabel` on thread docs  
- Secondary tags  
- Tips / bounties / store accounts  
- Police/distress flows  
- Map style alternatives (satellite, etc.)  
- Avatar on R2 (Storage OK for launch)  

## Ops before submit (not features)

1. Deploy Firestore rules (own-user delete already in repo)  
2. Device matrix in [`VIDEO_PRODUCTION.md`](VIDEO_PRODUCTION.md) § Ship  
3. APNs Auth Key for iOS push ([`PARKING.md`](PARKING.md))  
4. Play / App Store listings, screenshots, age rating, privacy nutrition labels  
5. Confirm Live builds (`MAPTALK_MODE=live`) on both platforms  

## Bugfix / polish only

Fix crashes, store rejection items, copy typos, and obvious UX bugs.
No new surfaces unless App Review requires them.
