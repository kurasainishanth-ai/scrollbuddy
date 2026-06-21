import {
  getAllHeartbeats,
  markProtectionLost,
  recordAuditEvent,
  getFcmTokensForUsers
} from "./store.js";

const HEARTBEAT_INTERVAL_MS = 5 * 60 * 1000; // 5 minutes
const MISSED_THRESHOLD_MS = 2 * HEARTBEAT_INTERVAL_MS; // 10 minutes (2 missed heartbeats)
const CHECK_INTERVAL_MS = 2.5 * 60 * 1000; // Check every 2.5 minutes

let firebaseAdmin = null;

export function initFirebaseForChecker(admin) {
  firebaseAdmin = admin;
}

export function startHeartbeatChecker() {
  console.log("[HEARTBEAT] Checker started (interval: 2.5 min, threshold: 10 min)");

  setInterval(async () => {
    try {
      await checkMissedHeartbeats();
    } catch (e) {
      console.error("[HEARTBEAT] Checker error:", e.message);
    }
  }, CHECK_INTERVAL_MS);
}

async function checkMissedHeartbeats() {
  const heartbeats = await getAllHeartbeats();
  const now = Date.now();
  console.log(`[HEARTBEAT] Running check at ${new Date(now).toISOString()}... Total registered heartbeats: ${Object.keys(heartbeats).length}`);

  for (const [username, data] of Object.entries(heartbeats)) {
    console.log(`[HEARTBEAT] Evaluating user: ${username}`);
    
    // Skip users already marked as lost
    if (data.protectionStatus === "LOST") {
      console.log(`[HEARTBEAT] -> SKIP: ${username} is already marked as LOST.`);
      continue;
    }
    // Skip users who never sent a heartbeat
    if (!data.lastHeartbeat) {
      console.log(`[HEARTBEAT] -> SKIP: ${username} has no lastHeartbeat timestamp.`);
      continue;
    }

    const elapsed = now - data.lastHeartbeat;
    console.log(`[HEARTBEAT] -> INFO for ${username}:`);
    console.log(`             lastHeartbeat: ${data.lastHeartbeat} (${new Date(data.lastHeartbeat).toISOString()})`);
    console.log(`             currentTime  : ${now} (${new Date(now).toISOString()})`);
    console.log(`             elapsedMs    : ${elapsed}`);
    console.log(`             thresholdMs  : ${MISSED_THRESHOLD_MS}`);

    if (elapsed > MISSED_THRESHOLD_MS) {
      console.log(`[HEARTBEAT] -> DECISION: Protection LOST for ${username}! Elapsed ${elapsed} > Threshold ${MISSED_THRESHOLD_MS}.`);

      await markProtectionLost(username, now);

      await recordAuditEvent({
        type: "PROTECTION_LOST",
        username,
        friends: data.friends || [],
        timestamp: now,
        details: `No heartbeat for ${Math.round(elapsed / 1000)} seconds`
      });

      // Send FCM push to friends
      if (data.friends && data.friends.length > 0) {
        console.log(`[HEARTBEAT] -> Triggering FCM for ${username}'s friends: ${data.friends.join(", ")}`);
        await sendProtectionLostNotifications(username, data.friends);
      } else {
        console.log(`[HEARTBEAT] -> ${username} has no friends listed to notify.`);
      }
    } else {
      console.log(`[HEARTBEAT] -> DECISION: OK for ${username}. Elapsed ${elapsed} <= Threshold ${MISSED_THRESHOLD_MS}.`);
    }
  }
}

async function sendProtectionLostNotifications(username, friends) {
  if (!firebaseAdmin || !firebaseAdmin.apps || firebaseAdmin.apps.length === 0) {
    console.warn("[HEARTBEAT] Firebase Admin not initialized, skipping FCM notifications");
    return;
  }

  const tokens = await getFcmTokensForUsers(friends);

  for (const [friendUsername, token] of Object.entries(tokens)) {
    if (!token) continue;

    try {
      await firebaseAdmin.messaging().send({
        token,
        notification: {
          title: "ScrollBuddy Protection Alert",
          body: `${username} may have disabled ScrollBuddy protection.`
        },
        data: {
          type: "PROTECTION_LOST",
          username,
          timestamp: Date.now().toString()
        },
        android: {
          priority: "high"
        }
      });
      console.log(`[FCM] Notification sent to ${friendUsername} about ${username}`);

      await recordAuditEvent({
        type: "FCM_SENT",
        from: username,
        to: friendUsername,
        timestamp: Date.now(),
        details: "Protection lost notification delivered"
      });
    } catch (e) {
      console.error(`[FCM] Failed to send to ${friendUsername}:`, e.message);

      await recordAuditEvent({
        type: "FCM_FAILED",
        from: username,
        to: friendUsername,
        timestamp: Date.now(),
        details: e.message
      });
    }
  }
}
