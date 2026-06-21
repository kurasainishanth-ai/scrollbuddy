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

  for (const [username, data] of Object.entries(heartbeats)) {
    // Skip users already marked as lost
    if (data.protectionStatus === "LOST") continue;
    // Skip users who never sent a heartbeat
    if (!data.lastHeartbeat) continue;

    const elapsed = now - data.lastHeartbeat;
    if (elapsed > MISSED_THRESHOLD_MS) {
      console.log(
        `[HEARTBEAT] Protection LOST for ${username} (${Math.round(elapsed / 1000)}s since last heartbeat)`
      );

      await markProtectionLost(username, now);

      await recordAuditEvent({
        type: "PROTECTION_LOST",
        username,
        timestamp: now,
        details: `No heartbeat for ${Math.round(elapsed / 1000)} seconds`
      });

      // Send FCM push to friends
      if (data.friends && data.friends.length > 0) {
        await sendProtectionLostNotifications(username, data.friends);
      }
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
