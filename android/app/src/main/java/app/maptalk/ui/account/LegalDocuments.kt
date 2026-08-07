package app.maptalk.ui.account

/**
 * In-app Privacy + Terms for store submission until hosted pages exist.
 * Mirrors `ios/MapTalk/Features/Account/LegalDocuments.swift`.
 */
enum class LegalDocument(val title: String, val body: String) {
    Privacy(
        title = "Privacy Policy",
        body = """
Last updated: August 2026

MapTalk is location-pinned public chat. This policy explains what we collect and why.

What we collect
• Account: a Firebase user id, optional Google (or Apple) sign-in, display name, and optional profile photo.
• Content you post: chat titles, messages, photos, videos, and voice notes pinned to map locations you choose.
• Device: approximate location while you use the map (to show nearby chats), and an optional push token if you allow notifications.
• Reports and blocks you submit (kept private to your account, except reports which we may review).

How we use it
• To run MapTalk — show chats on the map, deliver replies, and keep your profile.
• To keep people safe — blocks hide authors for you; reports help us review abuse.
• We do not sell your personal information.

Sharing
• Public chats and profiles (name, photo) are visible to other MapTalk users.
• We use Firebase (Google) and media storage to host the service. They process data on our behalf.

Retention
• You can delete your account in Settings. That removes your profile, blocks, device tokens, and Auth identity. Past public messages may remain on the map with the name you used when you posted (they are part of the place’s history).
• You can remove your profile photo anytime.

Your choices
• Explore as a guest (anonymous) or save the account with Google / Apple.
• Change your display name or photo.
• Block people; delete your account.

Children
• MapTalk is not directed at children under 13. Do not use the app if you are under 13.

Contact
• Questions: privacy@maptalk.app
        """.trimIndent(),
    ),
    Terms(
        title = "Terms of Use",
        body = """
Last updated: August 2026

By using MapTalk you agree to these terms.

The service
• MapTalk lets people pin public chats to places on a map. Content you post is visible to others browsing that area.
• We may change or discontinue features. Local demo mode is for trying the app and is not the live service.

Your account
• You are responsible for what you post under your display name.
• Linking Google or Apple saves your account across reinstalls. Guest mode is temporary to that install’s anonymous id.
• You may delete your account in Settings.

Acceptable use
• Do not post illegal content, harassment, spam, or others’ private information.
• Do not attempt to disrupt the service or abuse reports/blocks.
• We may remove content or suspend access that breaks these rules.

Content
• You keep rights to what you create. You grant MapTalk a licence to host and display it so the app works.
• Public posts may remain after you leave if they were already shared on the map.

Disclaimers
• MapTalk is provided as-is. Location and chat info can be wrong or outdated — use your own judgement offline and in public.
• To the extent allowed by law, we are not liable for user content or interactions that happen through the app.

Contact
• legal@maptalk.app
        """.trimIndent(),
    ),
}
