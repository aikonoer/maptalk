import { readFileSync } from 'node:fs';
import { after, before, beforeEach, describe, it } from 'node:test';

import {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} from '@firebase/rules-unit-testing';
import {
  deleteDoc,
  doc,
  getDoc,
  increment,
  serverTimestamp,
  setDoc,
  updateDoc,
  writeBatch,
} from 'firebase/firestore';

const PROJECT_ID = 'maptalk-rules-test';
const ALICE = 'alice-uid';
const BOB = 'bob-uid';

let testEnv;

/** A thread document that satisfies every create rule. */
function validThread(authorId = ALICE, overrides = {}) {
  return {
    title: 'Anyone else at the show?',
    kind: 'event',
    lat: -33.8688,
    lng: 151.2093,
    geohash: 'r3gx2f303j',
    authorId,
    authorName: 'Alice',
    createdAt: serverTimestamp(),
    lastMessageAt: serverTimestamp(),
    messageCount: 0,
    ...overrides,
  };
}

function validMessage(authorId = ALICE, overrides = {}) {
  return {
    text: 'Front left, sounds great',
    authorId,
    authorName: 'Alice',
    createdAt: serverTimestamp(),
    ...overrides,
  };
}

before(async () => {
  const [host, port] = (process.env.FIRESTORE_EMULATOR_HOST ?? 'localhost:8080').split(':');
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules: readFileSync(new URL('./firestore.rules', import.meta.url), 'utf8'),
      host,
      port: Number(port),
    },
  });
});

after(async () => {
  await testEnv?.cleanup();
});

beforeEach(async () => {
  await testEnv.clearFirestore();
});

/** Seeds a thread with the rules bypassed, so tests can exercise updates and messages. */
async function seedThread(threadId = 'thread-1') {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await setDoc(doc(context.firestore(), 'threads', threadId), {
      ...validThread(),
      createdAt: new Date(),
      lastMessageAt: new Date(),
    });
  });
  return threadId;
}

describe('threads', () => {
  it('lets a signed-in user create a well formed thread', async () => {
    const db = testEnv.authenticatedContext(ALICE).firestore();
    await assertSucceeds(setDoc(doc(db, 'threads', 'new-thread'), validThread()));
  });

  it('rejects a thread from a signed-out client', async () => {
    const db = testEnv.unauthenticatedContext().firestore();
    await assertFails(setDoc(doc(db, 'threads', 'new-thread'), validThread()));
  });

  it('rejects a thread created under someone else\'s uid', async () => {
    const db = testEnv.authenticatedContext(ALICE).firestore();
    await assertFails(setDoc(doc(db, 'threads', 'new-thread'), validThread(BOB)));
  });

  it('rejects an empty or oversized title', async () => {
    const db = testEnv.authenticatedContext(ALICE).firestore();
    await assertFails(
      setDoc(doc(db, 'threads', 'empty-title'), validThread(ALICE, { title: '' })),
    );
    await assertFails(
      setDoc(doc(db, 'threads', 'long-title'), validThread(ALICE, { title: 'x'.repeat(81) })),
    );
  });

  it('rejects an unknown kind', async () => {
    const db = testEnv.authenticatedContext(ALICE).firestore();
    await assertFails(
      setDoc(doc(db, 'threads', 'bad-kind'), validThread(ALICE, { kind: 'spam' })),
    );
  });

  it('rejects out of range coordinates', async () => {
    const db = testEnv.authenticatedContext(ALICE).firestore();
    await assertFails(setDoc(doc(db, 'threads', 'bad-lat'), validThread(ALICE, { lat: 91 })));
    await assertFails(setDoc(doc(db, 'threads', 'bad-lng'), validThread(ALICE, { lng: -181 })));
  });

  it('rejects a thread that starts with messages already counted', async () => {
    const db = testEnv.authenticatedContext(ALICE).firestore();
    await assertFails(
      setDoc(doc(db, 'threads', 'preloaded'), validThread(ALICE, { messageCount: 7 })),
    );
  });

  it('rejects a client supplied createdAt', async () => {
    const db = testEnv.authenticatedContext(ALICE).firestore();
    await assertFails(
      setDoc(doc(db, 'threads', 'faked-time'), validThread(ALICE, { createdAt: new Date(0) })),
    );
  });

  it('rejects unexpected extra fields', async () => {
    const db = testEnv.authenticatedContext(ALICE).firestore();
    await assertFails(
      setDoc(doc(db, 'threads', 'extra'), validThread(ALICE, { pinned: true })),
    );
  });

  it('lets any signed-in user read threads', async () => {
    const threadId = await seedThread();
    const db = testEnv.authenticatedContext(BOB).firestore();
    await assertSucceeds(getDoc(doc(db, 'threads', threadId)));
  });

  it('hides threads from signed-out clients', async () => {
    const threadId = await seedThread();
    const db = testEnv.unauthenticatedContext().firestore();
    await assertFails(getDoc(doc(db, 'threads', threadId)));
  });

  it('allows bumping activity by exactly one message', async () => {
    const threadId = await seedThread();
    const db = testEnv.authenticatedContext(BOB).firestore();
    await assertSucceeds(
      updateDoc(doc(db, 'threads', threadId), {
        lastMessageAt: serverTimestamp(),
        messageCount: increment(1),
      }),
    );
  });

  it('rejects inflating the message count', async () => {
    const threadId = await seedThread();
    const db = testEnv.authenticatedContext(BOB).firestore();
    await assertFails(
      updateDoc(doc(db, 'threads', threadId), {
        lastMessageAt: serverTimestamp(),
        messageCount: increment(50),
      }),
    );
  });

  it('rejects editing any other field', async () => {
    const threadId = await seedThread();
    const db = testEnv.authenticatedContext(ALICE).firestore();
    await assertFails(updateDoc(doc(db, 'threads', threadId), { title: 'Hijacked' }));
    await assertFails(
      updateDoc(doc(db, 'threads', threadId), {
        lastMessageAt: serverTimestamp(),
        messageCount: increment(1),
        lat: 0,
      }),
    );
  });

  it('rejects deleting a thread, even by its author', async () => {
    const threadId = await seedThread();
    const db = testEnv.authenticatedContext(ALICE).firestore();
    await assertFails(deleteDoc(doc(db, 'threads', threadId)));
  });
});

describe('messages', () => {
  it('accepts the message plus activity bump as one batch', async () => {
    const threadId = await seedThread();
    const db = testEnv.authenticatedContext(BOB).firestore();
    const batch = writeBatch(db);
    batch.set(doc(db, 'threads', threadId, 'messages', 'm1'), validMessage(BOB, { authorName: 'Bob' }));
    batch.update(doc(db, 'threads', threadId), {
      lastMessageAt: serverTimestamp(),
      messageCount: increment(1),
    });
    await assertSucceeds(batch.commit());
  });

  it('rejects a message attributed to another user', async () => {
    const threadId = await seedThread();
    const db = testEnv.authenticatedContext(BOB).firestore();
    await assertFails(
      setDoc(doc(db, 'threads', threadId, 'messages', 'm1'), validMessage(ALICE)),
    );
  });

  it('rejects empty and oversized text', async () => {
    const threadId = await seedThread();
    const db = testEnv.authenticatedContext(ALICE).firestore();
    await assertFails(
      setDoc(doc(db, 'threads', threadId, 'messages', 'empty'), validMessage(ALICE, { text: '' })),
    );
    await assertFails(
      setDoc(
        doc(db, 'threads', threadId, 'messages', 'long'),
        validMessage(ALICE, { text: 'x'.repeat(1001) }),
      ),
    );
  });

  it('accepts an image message with a caption or empty text', async () => {
    const threadId = await seedThread();
    const db = testEnv.authenticatedContext(ALICE).firestore();
    await assertSucceeds(
      setDoc(doc(db, 'threads', threadId, 'messages', 'photo'), {
        text: 'Sunset',
        authorId: ALICE,
        authorName: 'Alice',
        createdAt: serverTimestamp(),
        messageKind: 'image',
        imagePath: 'https://example.com/photo.jpg',
        imageWidth: 1280,
        imageHeight: 960,
      }),
    );
    await assertSucceeds(
      setDoc(doc(db, 'threads', threadId, 'messages', 'photo-only'), {
        text: '',
        authorId: ALICE,
        authorName: 'Alice',
        createdAt: serverTimestamp(),
        messageKind: 'image',
        imagePath: 'https://example.com/photo2.jpg',
        imageWidth: 800,
        imageHeight: 600,
      }),
    );
  });

  it('rejects an image message without a path or dimensions', async () => {
    const threadId = await seedThread();
    const db = testEnv.authenticatedContext(ALICE).firestore();
    await assertFails(
      setDoc(doc(db, 'threads', threadId, 'messages', 'bad-image'), {
        text: '',
        authorId: ALICE,
        authorName: 'Alice',
        createdAt: serverTimestamp(),
        messageKind: 'image',
      }),
    );
  });

  it('accepts a voice note with empty text and a duration', async () => {
    const threadId = await seedThread();
    const db = testEnv.authenticatedContext(ALICE).firestore();
    await assertSucceeds(
      setDoc(doc(db, 'threads', threadId, 'messages', 'voice'), {
        text: '',
        authorId: ALICE,
        authorName: 'Alice',
        createdAt: serverTimestamp(),
        messageKind: 'voice',
        audioPath: 'https://example.com/note.m4a',
        audioDurationMs: 4_200,
      }),
    );
  });

  it('rejects a voice note with caption text or an overlong duration', async () => {
    const threadId = await seedThread();
    const db = testEnv.authenticatedContext(ALICE).firestore();
    await assertFails(
      setDoc(doc(db, 'threads', threadId, 'messages', 'voice-text'), {
        text: 'hello',
        authorId: ALICE,
        authorName: 'Alice',
        createdAt: serverTimestamp(),
        messageKind: 'voice',
        audioPath: 'https://example.com/note.m4a',
        audioDurationMs: 1_000,
      }),
    );
    await assertFails(
      setDoc(doc(db, 'threads', threadId, 'messages', 'voice-long'), {
        text: '',
        authorId: ALICE,
        authorName: 'Alice',
        createdAt: serverTimestamp(),
        messageKind: 'voice',
        audioPath: 'https://example.com/note.m4a',
        audioDurationMs: 60_001,
      }),
    );
  });

  it('accepts a sticker glyph and rejects oversized sticker text', async () => {
    const threadId = await seedThread();
    const db = testEnv.authenticatedContext(ALICE).firestore();
    await assertSucceeds(
      setDoc(doc(db, 'threads', threadId, 'messages', 'sticker'), {
        text: '🔥',
        authorId: ALICE,
        authorName: 'Alice',
        createdAt: serverTimestamp(),
        messageKind: 'sticker',
      }),
    );
    await assertFails(
      setDoc(doc(db, 'threads', threadId, 'messages', 'sticker-long'), {
        text: 'x'.repeat(9),
        authorId: ALICE,
        authorName: 'Alice',
        createdAt: serverTimestamp(),
        messageKind: 'sticker',
      }),
    );
  });

  it('accepts a text reply with denormalised reply fields', async () => {
    const threadId = await seedThread();
    const db = testEnv.authenticatedContext(BOB).firestore();
    await assertSucceeds(
      setDoc(doc(db, 'threads', threadId, 'messages', 'reply'), {
        text: 'Same here',
        authorId: BOB,
        authorName: 'Bob',
        createdAt: serverTimestamp(),
        messageKind: 'text',
        replyToId: 'parent-msg',
        replyToText: 'Front left, sounds great',
        replyToAuthorName: 'Alice',
      }),
    );
  });

  it('rejects a partial reply payload', async () => {
    const threadId = await seedThread();
    const db = testEnv.authenticatedContext(ALICE).firestore();
    await assertFails(
      setDoc(doc(db, 'threads', threadId, 'messages', 'half-reply'), {
        text: 'Incomplete reply',
        authorId: ALICE,
        authorName: 'Alice',
        createdAt: serverTimestamp(),
        messageKind: 'text',
        replyToId: 'parent-msg',
      }),
    );
  });

  it('lets any signed-in user toggle reactions and rejects text edits', async () => {
    const threadId = await seedThread();
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await setDoc(doc(context.firestore(), 'threads', threadId, 'messages', 'm1'), {
        ...validMessage(),
        createdAt: new Date(),
      });
    });
    const db = testEnv.authenticatedContext(BOB).firestore();
    await assertSucceeds(
      updateDoc(doc(db, 'threads', threadId, 'messages', 'm1'), {
        reactions: { '👍': [BOB] },
      }),
    );
    await assertFails(
      updateDoc(doc(db, 'threads', threadId, 'messages', 'm1'), { text: 'edited' }),
    );
    await assertFails(
      updateDoc(doc(db, 'threads', threadId, 'messages', 'm1'), {
        reactions: { '👍': [BOB] },
        text: 'sneaky',
      }),
    );
    await assertFails(deleteDoc(doc(db, 'threads', threadId, 'messages', 'm1')));
  });
});

describe('blocks and reports', () => {
  it('lets a user block someone else and read their own blocks', async () => {
    const db = testEnv.authenticatedContext(ALICE).firestore();
    await assertSucceeds(
      setDoc(doc(db, 'users', ALICE, 'blocks', BOB), {
        blockedUid: BOB,
        displayName: 'Bob',
        createdAt: serverTimestamp(),
      }),
    );
    await assertSucceeds(getDoc(doc(db, 'users', ALICE, 'blocks', BOB)));
  });

  it('rejects blocking yourself or writing another user\'s block list', async () => {
    const db = testEnv.authenticatedContext(ALICE).firestore();
    await assertFails(
      setDoc(doc(db, 'users', ALICE, 'blocks', ALICE), {
        blockedUid: ALICE,
        displayName: 'Alice',
        createdAt: serverTimestamp(),
      }),
    );
    await assertFails(
      setDoc(doc(db, 'users', BOB, 'blocks', ALICE), {
        blockedUid: ALICE,
        displayName: 'Alice',
        createdAt: serverTimestamp(),
      }),
    );
  });

  it('accepts a message report and rejects incomplete ones', async () => {
    const db = testEnv.authenticatedContext(ALICE).firestore();
    await assertSucceeds(
      setDoc(doc(db, 'users', ALICE, 'reports', 'r1'), {
        targetType: 'message',
        targetId: 'msg-1',
        threadId: 'thread-1',
        targetAuthorId: BOB,
        reason: 'spam',
        createdAt: serverTimestamp(),
      }),
    );
    await assertFails(
      setDoc(doc(db, 'users', ALICE, 'reports', 'r2'), {
        targetType: 'message',
        targetId: 'msg-1',
        threadId: '',
        targetAuthorId: BOB,
        reason: 'spam',
        createdAt: serverTimestamp(),
      }),
    );
    await assertFails(
      setDoc(doc(db, 'users', ALICE, 'reports', 'r3'), {
        targetType: 'thread',
        targetId: 'thread-1',
        threadId: '',
        targetAuthorId: ALICE,
        reason: 'spam',
        createdAt: serverTimestamp(),
      }),
    );
  });

  it('rejects editing or deleting a report', async () => {
    await testEnv.withSecurityRulesDisabled(async (context) => {
      await setDoc(doc(context.firestore(), 'users', ALICE, 'reports', 'r1'), {
        targetType: 'user',
        targetId: BOB,
        threadId: '',
        targetAuthorId: BOB,
        reason: 'harassment',
        createdAt: new Date(),
      });
    });
    const db = testEnv.authenticatedContext(ALICE).firestore();
    await assertFails(
      updateDoc(doc(db, 'users', ALICE, 'reports', 'r1'), { reason: 'other' }),
    );
    await assertFails(deleteDoc(doc(db, 'users', ALICE, 'reports', 'r1')));
  });
});

describe('users', () => {
  it('lets a user write their own profile', async () => {
    const db = testEnv.authenticatedContext(ALICE).firestore();
    await assertSucceeds(
      setDoc(doc(db, 'users', ALICE), { displayName: 'Alice', createdAt: serverTimestamp() }),
    );
  });

  it('rejects writing someone else\'s profile', async () => {
    const db = testEnv.authenticatedContext(ALICE).firestore();
    await assertFails(
      setDoc(doc(db, 'users', BOB), { displayName: 'Not Bob', createdAt: serverTimestamp() }),
    );
  });

  it('rejects an empty or oversized display name', async () => {
    const db = testEnv.authenticatedContext(ALICE).firestore();
    await assertFails(
      setDoc(doc(db, 'users', ALICE), { displayName: '', createdAt: serverTimestamp() }),
    );
    await assertFails(
      setDoc(doc(db, 'users', ALICE), {
        displayName: 'x'.repeat(25),
        createdAt: serverTimestamp(),
      }),
    );
  });
});
