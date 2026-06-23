import { randomUUID } from "crypto";

let db = null;

export function initStore(firebaseAdmin) {
  if (firebaseAdmin && firebaseAdmin.apps.length > 0) {
    db = firebaseAdmin.firestore();
    console.log("[STORE] Firestore initialized");
  } else {
    console.warn("[STORE] Firebase Admin not initialized, Firestore unavailable");
  }
}

// Helper to ensure DB is available
function checkDb() {
  if (!db) throw new Error("Firestore not initialized. Please set FIREBASE_SERVICE_ACCOUNT_JSON and create a Firestore Database in Firebase Console.");
}

export async function registerUser(username) {
  checkDb();
  const userRef = db.collection("users").doc(username);
  const doc = await userRef.get();
  if (doc.exists) {
    return { error: "Username already exists", status: 409 };
  }
  const user = { username, createdAt: Date.now() };
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
  const userRef = db.collection("users").doc(username);
  const doc = await userRef.get();
  if (doc.exists) {
    return { error: "Username already exists", status: 409 };
  }
  const user = {
    googleUid,
    email,
    displayName,
    photoUrl,
    username,
    createdAt: Date.now()
  };
  await userRef.set(user);
  return { user, status: 201 };
}

export async function getUserProfile(username) {
  checkDb();
  const userRef = db.collection("users").doc(username.toLowerCase());
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
    requester,
    approver,
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
  
  // 1. Fetch pending friend requests
  const reqSnapshot = await db.collection("requests")
    .where("approver", "==", username)
    .where("status", "==", "PENDING")
    .get();
  
  const inboxItems = [];
  reqSnapshot.forEach(doc => inboxItems.push(doc.data()));

  // 2. Fetch pending protection events from audit log
  const auditSnapshot = await db.collection("audit_log")
    .where("status", "==", "PENDING")
    .get();

  auditSnapshot.forEach(doc => {
    const data = doc.data();
    // Check if this event is meant for `username`
    if ((data.type === "PROTECTION_LOST" || data.type === "PROTECTION_EVENT") && data.friends && data.friends.includes(username)) {
      inboxItems.push({
        id: data.id,
        requester: data.username,
        approver: username,
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

// --- Heartbeat storage ---

export async function recordHeartbeat(username, protectionActive, friends) {
  if (!db) return { transitionedToLost: false, transitionedToActive: false };
  const now = Date.now();
  const ref = db.collection("heartbeats").doc(username);

  let transitionedToLost = false;
  let transitionedToActive = false;

  await db.runTransaction(async (transaction) => {
    const doc = await transaction.get(ref);
    let existingFriends = [];
    let previousStatus = "ACTIVE";

    if (doc.exists) {
      existingFriends = doc.data().friends || [];
      previousStatus = doc.data().protectionStatus || "ACTIVE";
    }
    
    const isAccessibilityDisabled = protectionActive === false;
    const newStatus = isAccessibilityDisabled ? "ACCESSIBILITY_DISABLED" : "ACTIVE";
    
    transitionedToLost = (previousStatus !== "ACCESSIBILITY_DISABLED" && isAccessibilityDisabled);
    transitionedToActive = (previousStatus !== "ACTIVE" && newStatus === "ACTIVE");

    console.log(`[STATE] User: ${username} | Prev: ${previousStatus} | New: ${newStatus} | ActiveFlag: ${protectionActive}`);

    if (transitionedToLost) {
      console.log(`[NOTIFY] Reason for notification: User transitioned from ${previousStatus} to ACCESSIBILITY_DISABLED`);
    } else if (isAccessibilityDisabled) {
      console.log(`[SKIP] Reason for skipping notification: User is already ACCESSIBILITY_DISABLED`);
    } else if (transitionedToActive) {
      console.log(`[STATE] User ${username} restored from ${previousStatus} to ACTIVE. State reset.`);
    }

    transaction.set(ref, {
      lastHeartbeat: now,
      protectionActive,
      protectionStatus: newStatus,
      lastSeen: now,
      friends: friends || existingFriends,
      lostAt: isAccessibilityDisabled ? (doc.exists ? doc.data().lostAt || now : now) : null
    }, { merge: true });
  });

  return { transitionedToLost, transitionedToActive };
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

export async function markProtectionLost(username, timestamp) {
  if (!db) return;
  await db.collection("heartbeats").doc(username).update({
    protectionStatus: "HEARTBEAT_LOST",
    lostAt: timestamp
  });
}

// --- FCM token storage ---

export async function registerFcmToken(username, token) {
  if (!db) return;
  await db.collection("fcm_tokens").doc(username).set({ token });
}

export async function getFcmTokensForUsers(usernames) {
  if (!db) return {};
  const result = {};
  if (usernames.length === 0) return result;
  
  for (const username of usernames) {
    const doc = await db.collection("fcm_tokens").doc(username).get();
    if (doc.exists) {
      result[username] = doc.data().token;
    }
  }
  return result;
}

// --- Audit log ---

export async function recordAuditEvent(event) {
  if (!db) return;
  const id = randomUUID();
  await db.collection("audit_log").doc(id).set({
    id,
    status: "PENDING",
    ...event,
    recordedAt: Date.now()
  });
}

// --- Protection events (called by app's ProtectionMonitor) ---

export async function recordProtectionEvent({ username, reason, timestamp, friends }) {
  await recordAuditEvent({
    type: "PROTECTION_EVENT",
    username,
    reason,
    timestamp,
    friends
  });
  // Also mark heartbeat as lost since the app reported a problem
  await markProtectionLost(username, timestamp || Date.now());
}
