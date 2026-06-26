import {
  getAllHeartbeats,
  markHeartbeatLost,
  recordAuditEvent,
  getFcmTokensForUsers,
  ProtectionStatus,
} from "./store.js";

const HEARTBEAT_INTERVAL_MS = 5 * 60 * 1000;
const MISSED_THRESHOLD_MS = 6 * HEARTBEAT_INTERVAL_MS; // 30 minutes
const CHECK_INTERVAL_MS = 2.5 * 60 * 1000;

let firebaseAdmin = null;
let checkerRunning = false;

export function initFirebaseForChecker(admin) {
  firebaseAdmin = admin;
}

export async function handleProtectionTransition(notification) {
  if (!notification) return;

  const now = Date.now();
  const auditType = notification.auditType || "PROTECTION_LOST";

  await recordAuditEvent({
    type: auditType,
    username: notification.username,
    friends: notification.friends || [],
    timestamp: now,
    details: notification.details,
  });

  const message = notification.type === "PROTECTION_DISABLED"
    ? "has disabled ScrollBuddy protection."
    : "lost connection, uninstalled the app, or stopped responding.";

  if (notification.friends && notification.friends.length > 0) {
    console.log(`[NOTIFY] ${notification.type} for ${notification.username} -> friends: ${notification.friends.join(", ")}`);
    await sendProtectionLostNotifications(notification.username, notification.friends, message);
  } else {
    console.log(`[NOTIFY] ${notification.type} for ${notification.username} but no friends to notify`);
  }
}

export function startHeartbeatChecker() {
  console.log("[HEARTBEAT] Checker started (interval: 2.5 min, threshold: 10 min)");

  setInterval(async () => {
    if (checkerRunning) {
      console.log("[HEARTBEAT] Previous check still running, skipping");
      return;
    }
    checkerRunning = true;
    try {
      await checkMissedHeartbeats();
    } catch (e) {
      console.error("[HEARTBEAT] Checker error:", e.message);
    } finally {
      checkerRunning = false;
    }
  }, CHECK_INTERVAL_MS);
}

async function checkMissedHeartbeats() {
  const heartbeats = await getAllHeartbeats();
  const now = Date.now();
  console.log(`[HEARTBEAT] Running check at ${new Date(now).toISOString()}... Total: ${Object.keys(heartbeats).length}`);

  for (const [username, data] of Object.entries(heartbeats)) {
    const status = data.protectionStatus || ProtectionStatus.ACTIVE;

    if (status !== ProtectionStatus.ACTIVE) {
      console.log(`[HEARTBEAT] -> SKIP: ${username} status=${status}`);
      continue;
    }

    if (!data.lastHeartbeat) {
      console.log(`[HEARTBEAT] -> SKIP: ${username} has no lastHeartbeat`);
      continue;
    }

    const elapsed = now - data.lastHeartbeat;
    console.log(`[HEARTBEAT] ${username}: elapsed=${elapsed}ms threshold=${MISSED_THRESHOLD_MS}ms`);

    if (elapsed > MISSED_THRESHOLD_MS) {
      const details = `No heartbeat for ${Math.round(elapsed / 1000)} seconds`;
      const { notification } = await markHeartbeatLost(username, now, details);
      await handleProtectionTransition(notification);
    }
  }
}

async function sendProtectionLostNotifications(username, friends, reasonStr) {
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
          body: `${username} ${reasonStr || "may have disabled ScrollBuddy protection."}`
        },
        data: {
          type: "PROTECTION_LOST",
          username,
          timestamp: Date.now().toString(),
          reason: reasonStr || "ScrollBuddy protection may have been disabled"
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
