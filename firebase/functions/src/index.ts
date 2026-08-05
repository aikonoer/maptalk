import { initializeApp } from "firebase-admin/app";
import { getFirestore } from "firebase-admin/firestore";
import { getMessaging } from "firebase-admin/messaging";
import {
  onDocumentCreated,
  onDocumentDeleted,
} from "firebase-functions/v2/firestore";
import { defineString } from "firebase-functions/params";
import { logger } from "firebase-functions";

initializeApp();

const db = getFirestore();
const messaging = getMessaging();

const mediaDeleteSecret = defineString("MEDIA_DELETE_SECRET", {
  default: "",
  description: "Shared secret for Worker DELETE /v1/admin/object",
});

const mediaWorkerBase = defineString("MEDIA_WORKER_BASE", {
  default: "https://maptalk-media.hhypkfpshg.workers.dev",
});

const R2_PUBLIC_PREFIX =
  "https://pub-7c910bfa4a884bb6bd039db548455d5e.r2.dev/";

type MessageDoc = {
  text?: string;
  authorId?: string;
  authorName?: string;
  messageKind?: string;
  imagePath?: string;
  audioPath?: string;
  videoPath?: string;
};

function previewBody(message: MessageDoc): string {
  const kind = message.messageKind ?? "text";
  const name = message.authorName?.trim() || "Someone";
  switch (kind) {
    case "image":
      return `${name} sent a photo`;
    case "voice":
      return `${name} sent a voice note`;
    case "video":
      return `${name} sent a video`;
    case "sticker":
      return `${name} sent a sticker`;
    default: {
      const text = (message.text ?? "").trim();
      if (!text) return `${name} replied`;
      return text.length > 120 ? `${text.slice(0, 117)}…` : text;
    }
  }
}

/**
 * When a message is posted, notify every subscriber except the author.
 */
export const onThreadMessageCreated = onDocumentCreated(
  "threads/{threadId}/messages/{messageId}",
  async (event) => {
    const snap = event.data;
    if (!snap) return;

    const threadId = event.params.threadId;
    const message = snap.data() as MessageDoc;
    const authorId = message.authorId;
    if (!authorId) return;

    const [threadSnap, subscribersSnap] = await Promise.all([
      db.collection("threads").doc(threadId).get(),
      db.collection("threads").doc(threadId).collection("subscribers").get(),
    ]);

    const title =
      (threadSnap.data()?.title as string | undefined)?.trim() || "MapTalk";
    const body = previewBody(message);

    const recipientUids = subscribersSnap.docs
      .map((doc) => doc.id)
      .filter((uid) => uid !== authorId);

    if (recipientUids.length === 0) {
      logger.info("No subscribers to notify", { threadId });
      return;
    }

    const tokenSnaps = await Promise.all(
      recipientUids.map((uid) =>
        db.collection("users").doc(uid).collection("devices").get(),
      ),
    );

    const tokens = new Set<string>();
    for (const devices of tokenSnaps) {
      for (const device of devices.docs) {
        const token = device.data().token;
        if (typeof token === "string" && token.length >= 8) {
          tokens.add(token);
        }
      }
    }

    if (tokens.size === 0) {
      logger.info("Subscribers have no device tokens", { threadId, recipientUids });
      return;
    }

    const tokenList = [...tokens];
    // FCM multicast max is 500.
    for (let i = 0; i < tokenList.length; i += 500) {
      const chunk = tokenList.slice(i, i + 500);
      const response = await messaging.sendEachForMulticast({
        tokens: chunk,
        notification: { title, body },
        data: {
          threadId,
          type: "thread_message",
        },
        apns: {
          payload: {
            aps: {
              sound: "default",
              badge: 1,
            },
          },
        },
        android: {
          priority: "high",
          notification: {
            channelId: "thread_messages",
            sound: "default",
          },
        },
      });

      const stale: string[] = [];
      response.responses.forEach((result, index) => {
        if (!result.success) {
          const code = result.error?.code ?? "";
          if (
            code.includes("registration-token-not-registered") ||
            code.includes("invalid-registration-token")
          ) {
            stale.push(chunk[index]!);
          }
        }
      });

      if (stale.length > 0) {
        logger.info("Dropping stale FCM tokens", { count: stale.length });
        // Best-effort cleanup across all users' device docs matching those tokens.
        const staleSet = new Set(stale);
        for (const uid of recipientUids) {
          const devices = await db
            .collection("users")
            .doc(uid)
            .collection("devices")
            .get();
          const batch = db.batch();
          let ops = 0;
          for (const device of devices.docs) {
            const token = device.data().token;
            if (typeof token === "string" && staleSet.has(token)) {
              batch.delete(device.ref);
              ops += 1;
            }
          }
          if (ops > 0) await batch.commit();
        }
      }

      logger.info("Push fan-out", {
        threadId,
        success: response.successCount,
        failure: response.failureCount,
      });
    }
  },
);

/**
 * When a message doc is deleted (admin/scripts), drop the matching R2 object.
 * Clients cannot delete messages today — this is for future moderation tools.
 */
export const onThreadMessageDeleted = onDocumentDeleted(
  "threads/{threadId}/messages/{messageId}",
  async (event) => {
    const snap = event.data;
    if (!snap) return;
    const message = snap.data() as MessageDoc;
    const secret = mediaDeleteSecret.value();
    if (!secret) {
      logger.warn("MEDIA_DELETE_SECRET unset; skipping R2 cleanup");
      return;
    }
    const candidates = [message.imagePath, message.audioPath, message.videoPath]
      .filter((p): p is string => typeof p === "string" && p.startsWith(R2_PUBLIC_PREFIX));
    for (const url of candidates) {
      const key = url.slice(R2_PUBLIC_PREFIX.length);
      try {
        const res = await fetch(
          `${mediaWorkerBase.value().replace(/\/$/, "")}/v1/admin/object?key=${encodeURIComponent(key)}`,
          {
            method: "DELETE",
            headers: { "X-MapTalk-Admin": secret },
          },
        );
        if (!res.ok) {
          logger.warn("R2 delete failed", { key, status: res.status });
        } else {
          logger.info("R2 object deleted", { key });
        }
      } catch (err) {
        logger.error("R2 delete error", { key, err });
      }
    }
  },
);
