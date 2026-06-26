import { randomUUID } from "crypto";

let db = null;

export const ProtectionStatus = {
  ACTIVE: "ACTIVE",
  PROTECTION_DISABLED: "PROTECTION_DISABLED",
  HEARTBEAT_LOST: "HEARTBEAT_LOST",
};

export function initStore(firebaseAdmin) {
  if (firebaseAdmin && firebaseAdmin.apps.length > 0) {
    db = firebaseAdmin.firestore();
    console.log("[STORE] Firestore initialized");
  } else {
    console.warn("[STORE] Firebase Admin not initialized, Firestore unavailable");
  }
}

function checkDb() {
  if (!db) throw new Error("Firestore not initialized. Please set FIREBASE_SERVICE_ACCOUNT_JSON and create a Firestore Database in Firebase Console.");
}

function normalizeUsername(username) {
  return username.trim().toLowerCase();
}


export async function registerUser(username) {
  checkDb();
  const normalized = normalizeUsername(username);
  const userRef = db.collection("users").doc(normalized);
  const doc = await userRef.get();
  if (doc.exists) {
    return { error: "Username already exists", status: 409 };
  }
  const user = { username: normalized, createdAt: Date.now() };
  await userRef.set(user);
  return { user, status: 201 };
}

export async function getUserByGoogleUid(googleUid) {
  checkDb();
  const snapshot = await db.collection("users").where("googleUid", "==", googleUid).limit(1).get();
  if (snapshot.empty) return null;
  return snapshot.docs[0].data();
}

export async function registerGoogleUser({ googleUid, email, displayName, photoUrl, username }) {
  checkDb();
  const normalized = normalizeUsername(username);
  const userRef = db.collection("users").doc(normalized);
  const doc = await userRef.get();
  if (doc.exists) {
    return { error: "Username already exists", status: 409 };
  }
  const user = {
    googleUid,
    email,
    displayName,
    photoUrl,
    username: normalized,
    createdAt: Date.now()
  };
  await userRef.set(user);
  return { user, status: 201 };
}

export async function getUserProfile(username) {
  checkDb();
  const userRef = db.collection("users").doc(normalizeUsername(username));
  const doc = await userRef.get();
  return doc.exists ? doc.data() : null;
}

export async function searchUsers(query, exclude) {
  checkDb();
  console.log(`[SEARCH] incoming query: "${query}", exclude: "${exclude}"`);
  const q = query.toLowerCase();
  const excludeLower = exclude ? exclude.toLowerCase() : null;

  try {
    const snapshot = await db.collection("users").get();
    console.log(`[SEARCH] total users loaded from Firestore: ${snapshot.size}`);

    const results = [];
    const allUsernames = [];

    snapshot.forEach(doc => {
      const u = doc.data();
      if (!u.username) {
        return;
      }

      allUsernames.push(u.username);

      const uName = u.username.toLowerCase();
      if (uName.includes(q) && uName !== excludeLower) {
        results.push({ username: u.username });
      }
    });

    console.log(`[SEARCH] every username found in DB: [${allUsernames.join(", ")}]`);
    console.log(`[SEARCH] final filtered results: ${JSON.stringify(results)}`);

    return results;
  } catch (error) {
    console.error("[SEARCH] Error inside searchUsers:", error);
    throw error;
  }
}

export async function createRequest({ requester, approver, minutes }) {
  checkDb();
  const id = randomUUID();
  const now = Date.now();
  const record = {
    id,
    requester: normalizeUsername(requester),
    approver: normalizeUsername(approver),
    minutes,
    status: "PENDING",
    createdAt: now,
    expiresAt: now + 24 * 60 * 60 * 1000,
  };
  await db.collection("requests").doc(id).set(record);
  return record;
}

export async function getInbox(username) {
  checkDb();
  const normalized = normalizeUsername(username);

  const reqSnapshot = await db.collection("requests")
    .where("approver", "==", normalized)
    .where("status", "==", "PENDING")
    .get();

  const inboxItems = [];
  reqSnapshot.forEach(doc => inboxItems.push(doc.data()));

  const auditSnapshot = await db.collection("audit_log")
    .where("type", "in", ["PROTECTION_LOST", "PROTECTION_EVENT"])
    .get();

  auditSnapshot.forEach(doc => {
    const data = doc.data();
    if ((data.type === "PROTECTION_LOST" || data.type === "PROTECTION_EVENT") &&
        data.friends && data.friends.includes(normalized)) {
      inboxItems.push({
        id: data.id,
        requester: data.username,
        approver: normalized,
        minutes: 0,
        status: data.status,
        type: data.type,
        reason: data.details || data.reason,
        createdAt: data.timestamp || data.recordedAt
      });
    }
  });

  return inboxItems.sort((a, b) => b.createdAt - a.createdAt);
}

export async function updateRequestStatus(id, status) {
  checkDb();
  const ref = db.collection("requests").doc(id);
  const doc = await ref.get();
  if (!doc.exists) return null;

  await ref.update({
    status,
    decidedAt: Date.now()
  });

  const updatedDoc = await ref.get();
  return updatedDoc.data();
}

export async function getRequestById(id) {
  checkDb();
  const doc = await db.collection("requests").doc(id).get();
  return doc.exists ? doc.data() : null;
}

export async function acknowledgeAuditEvent(id) {
  checkDb();
  const ref = db.collection("audit_log").doc(id);
  const doc = await ref.get();
  if (!doc.exists) return null;

  await ref.update({
    status: "ACKNOWLEDGED",
    decidedAt: Date.now()
  });

  const updatedDoc = await ref.get();
  return updatedDoc.data();
}

/**
 * Applies heartbeat state transitions. Returns a notification payload only when
 * entering a new lost state (PROTECTION_DISABLED or HEARTBEAT_LOST).
 */
export async function recordHeartbeat(username, protectionActive, friends) {
  if (!db) return { notification: null };
  const normalizedUsername = normalizeUsername(username);
  const now = Date.now();
  const ref = db.collection("heartbeats").doc(normalizedUsername);

  let notification = null;

  await db.runTransaction(async (transaction) => {
    const doc = await transaction.get(ref);
    let existingFriends = [];
    let previousStatus = ProtectionStatus.ACTIVE;

    if (doc.exists) {
      const data = doc.data();
      existingFriends = data.friends || [];
      previousStatus = data.protectionStatus || ProtectionStatus.ACTIVE;
    }

    const mergedFriends = friends && friends.length > 0 ? friends.map(normalizeUsername) : existingFriends;
    const isDisabled = protectionActive === false;

    let newStatus = previousStatus;
    let lostAt = doc.exists ? doc.data().lostAt || null : null;

    if (isDisabled) {
      if (previousStatus !== ProtectionStatus.PROTECTION_DISABLED) {
        newStatus = ProtectionStatus.PROTECTION_DISABLED;
        lostAt = now;
        notification = {
          type: "PROTECTION_DISABLED",
          username: normalizedUsername,
          friends: mergedFriends,
          details: "Protection disabled by user",
          auditType: "PROTECTION_EVENT",
        };
        console.log(`[STATE] ${normalizedUsername}: ${previousStatus} -> PROTECTION_DISABLED`);
      } else {
        console.log(`[SKIP] ${normalizedUsername} already PROTECTION_DISABLED`);
      }
    } else {
      if (previousStatus !== ProtectionStatus.ACTIVE) {
        console.log(`[STATE] ${normalizedUsername}: ${previousStatus} -> ACTIVE (recovered)`);
      }
      newStatus = ProtectionStatus.ACTIVE;
      lostAt = null;
    }

    transaction.set(ref, {
      lastHeartbeat: now,
      protectionActive: !isDisabled,
      protectionStatus: newStatus,
      lastSeen: now,
      friends: mergedFriends,
      lostAt,
    }, { merge: true });
  });

  return { notification };
}

/**
 * Marks heartbeat lost due to timeout/uninstall. Uses a transaction so only one
 * checker run can trigger a notification.
 */
export async function markHeartbeatLost(username, timestamp, details) {
  if (!db) return { notification: null };
  const normalizedUsername = normalizeUsername(username);
  const ref = db.collection("heartbeats").doc(normalizedUsername);

  let notification = null;

  await db.runTransaction(async (transaction) => {
    const doc = await transaction.get(ref);
    if (!doc.exists) return;

    const data = doc.data();
    const previousStatus = data.protectionStatus || ProtectionStatus.ACTIVE;

    if (previousStatus !== ProtectionStatus.ACTIVE) {
      console.log(`[SKIP] ${normalizedUsername} timeout skipped, status=${previousStatus}`);
      return;
    }

    if (!data.lastHeartbeat) {
      console.log(`[SKIP] ${normalizedUsername} timeout skipped, no lastHeartbeat`);
      return;
    }

    notification = {
      type: "HEARTBEAT_LOST",
      username: normalizedUsername,
      friends: data.friends || [],
      details: details || "No heartbeat received",
      auditType: "PROTECTION_LOST",
    };

    transaction.set(ref, {
      protectionStatus: ProtectionStatus.HEARTBEAT_LOST,
      protectionActive: false,
      lostAt: timestamp,
    }, { merge: true });

    console.log(`[STATE] ${normalizedUsername}: ACTIVE -> HEARTBEAT_LOST`);
  });

  return { notification };
}

export async function getAllHeartbeats() {
  if (!db) return {};
  const snapshot = await db.collection("heartbeats").get();
  const heartbeats = {};
  snapshot.forEach(doc => {
    heartbeats[doc.id] = doc.data();
  });
  return heartbeats;
}

export async function registerFcmToken(username, token) {
  if (!db) return;
  await db.collection("fcm_tokens").doc(normalizeUsername(username)).set({ token });
}

export async function getFcmTokensForUsers(usernames) {
  if (!db) return {};
  const result = {};
  if (usernames.length === 0) return result;

  for (const username of usernames) {
    const doc = await db.collection("fcm_tokens").doc(normalizeUsername(username)).get();
    if (doc.exists) {
      result[username] = doc.data().token;
    }
  }
  return result;
}

export async function recordAuditEvent(event) {
  if (!db) return null;
  const id = randomUUID();
  const status = (event.type === "PROTECTION_LOST" || event.type === "PROTECTION_EVENT") ? "PENDING" : "LOG";
  await db.collection("audit_log").doc(id).set({
    id,
    status,
    ...event,
    recordedAt: Date.now()
  });
  return id;
}

export async function recordProtectionEvent({ username, reason, timestamp, friends }) {
  const normalizedUsername = normalizeUsername(username);
  const normalizedFriends = (friends || []).map(normalizeUsername);
  const result = await recordHeartbeat(normalizedUsername, false, normalizedFriends);

  if (result.notification) {
    result.notification.details = reason || result.notification.details;
    result.notification.auditType = "PROTECTION_EVENT";
  } else {
    console.log(`[SKIP] Protection event for ${normalizedUsername} did not change state`);
  }

  return result;
}
