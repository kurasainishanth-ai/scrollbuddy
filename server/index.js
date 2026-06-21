import express from "express";
import { OAuth2Client } from "google-auth-library";
import admin from "firebase-admin";
import { startHeartbeatChecker, initFirebaseForChecker } from "./heartbeat-checker.js";
import {
  initStore,
  registerUser,
  registerGoogleUser,
  getUserByGoogleUid,
  getUserProfile,
  searchUsers,
  createRequest,
  getInbox,
  updateRequestStatus,
  getRequestById,
  recordHeartbeat,
  registerFcmToken,
  recordProtectionEvent
} from "./store.js";

const inMemoryLogs = [];
const originalLog = console.log;
console.log = function(...args) {
  inMemoryLogs.push(new Date().toISOString() + " " + args.join(" "));
  if (inMemoryLogs.length > 500) inMemoryLogs.shift();
  originalLog.apply(console, args);
};

const app = express();
app.get("/api/debug/logs", (req, res) => {
  res.json(inMemoryLogs);
});

const PORT = Number(process.env.PORT) || 3000;
const GOOGLE_CLIENT_ID = process.env.GOOGLE_CLIENT_ID;

const googleClient = new OAuth2Client(GOOGLE_CLIENT_ID);

// Initialize Firebase Admin SDK for FCM and Firestore
const firebaseCredentials = process.env.FIREBASE_SERVICE_ACCOUNT_JSON;
if (firebaseCredentials) {
  try {
    const serviceAccount = JSON.parse(firebaseCredentials);
    admin.initializeApp({
      credential: admin.credential.cert(serviceAccount)
    });
    initStore(admin);
    initFirebaseForChecker(admin);
    console.log("[FIREBASE] Admin SDK initialized");
  } catch (e) {
    console.error("[FIREBASE] Failed to initialize:", e.message);
  }
} else {
  console.warn("[FIREBASE] FIREBASE_SERVICE_ACCOUNT_JSON not set, Firebase unavailable");
}

// logger first
app.use((req, res, next) => {
  // Normalize trailing slashes
  if (req.url.length > 1 && req.url.endsWith('/')) {
    req.url = req.url.slice(0, -1);
    console.log(`[DEBUG] Normalized URL to: ${req.url}`);
  }
  console.log(`[REQUEST] ${new Date().toISOString()} ${req.method} ${req.url}`);
  next();
});

app.use(express.json());

// JSON error handler
app.use((err, req, res, next) => {
  if (err instanceof SyntaxError && err.status === 400 && 'body' in err) {
    console.error(`[ERROR] Bad JSON: ${err.message}`);
    return res.status(400).send({ error: "Invalid JSON body" });
  }
  next();
});

// Health check
app.get("/api/health", (req, res) => {
  res.json({ status: "ok" });
});

// Explicit duplicate for robustness
app.post("/auth/google", (req, res, next) => {
  console.log("[DEBUG] Redirecting /auth/google to /api/auth/google");
  req.url = "/api/auth/google";
  next();
});

// Google Auth / Registration
app.post("/api/auth/google", async (req, res) => {
  const { idToken, username } = req.body;
  console.log(`[AUTH] POST /api/auth/google received. Token length: ${idToken?.length}, Username: ${username}`);

  if (!idToken) {
    return res.status(400).json({ error: "idToken required" });
  }

  try {
    // In a real production app, you MUST verify the idToken
    console.log(`[AUTH] Verifying token for GOOGLE_CLIENT_ID: ${GOOGLE_CLIENT_ID || 'NOT SET'}`);
    let googleUser;
    if (GOOGLE_CLIENT_ID) {
      try {
        const ticket = await googleClient.verifyIdToken({
          idToken,
          audience: GOOGLE_CLIENT_ID,
        });
        googleUser = ticket.getPayload();
        console.log(`[AUTH] Token verified successfully for: ${googleUser.email}`);
      } catch (err) {
        console.error(`[AUTH] verifyIdToken failed: ${err.message}`);
        throw err;
      }
    } else {
      console.warn("GOOGLE_CLIENT_ID not set, skipping token verification (DEVELOPMENT ONLY)");
      // Decode without verification if no client ID is provided (DANGEROUS in prod)
      const base64Url = idToken.split('.')[1];
      const base64 = base64Url.replace(/-/g, '+').replace(/_/g, '/');
      const jsonPayload = decodeURIComponent(Buffer.from(base64, 'base64').toString().split('').map(function(c) {
          return '%' + ('00' + c.charCodeAt(0).toString(16)).slice(-2);
      }).join(''));
      googleUser = JSON.parse(jsonPayload);
    }

    const { sub: googleUid, email, name: displayName, picture: photoUrl } = googleUser;

    let existingUser = await getUserByGoogleUid(googleUid);

    if (existingUser) {
      console.log(`Existing user found: ${existingUser.username}`);
      return res.json(existingUser);
    }

    // If no existing user and no username provided, we need to ask for one
    if (!username) {
      console.log("New Google user, username required");
      return res.status(202).json({
        message: "First login, please choose a username",
        googleUid,
        email,
        displayName,
        photoUrl
      });
    }

    // Register new user
    const sanitizedUsername = username.trim().toLowerCase();
    const usernameRegex = /^[a-z0-9_-]{3,20}$/;
    if (!usernameRegex.test(sanitizedUsername)) {
      return res.status(400).json({ error: "Invalid username format" });
    }

    const result = await registerGoogleUser({
      googleUid,
      email,
      displayName,
      photoUrl,
      username: sanitizedUsername
    });

    if (result.error) {
      return res.status(result.status).json({ error: result.error });
    }

    console.log(`New user registered: ${result.user.username}`);
    res.status(201).json(result.user);

  } catch (error) {
    console.error("Auth error:", error);
    res.status(401).json({ error: "Invalid Google token" });
  }
});

// Get profile
app.get("/api/profile/:username", async (req, res) => {
  try {
    const user = await getUserProfile(req.params.username);
    if (!user) return res.status(404).json({ error: "User not found" });
    res.json(user);
  } catch (e) {
    console.error(e);
    res.status(500).json({ error: "Server error" });
  }
});

// Registration (Legacy/Fallback)
app.post("/api/register", async (req, res) => {
  try {
    let { username } = req.body;
    console.log(`[REGISTER] Legacy request for username: ${username}`);
    if (!username) return res.status(400).json({ error: "Username required" });
    username = username.trim().toLowerCase();
    const result = await registerUser(username);
    if (result.error) return res.status(result.status).json({ error: result.error });
    res.status(result.status).json(result.user);
  } catch (e) {
    console.error(e);
    res.status(500).json({ error: "Server error" });
  }
});

// Search users (to add friend)
app.get("/api/users/search", async (req, res) => {
  try {
    const { q, exclude } = req.query;
    console.log(`Search request received: q="${q}", exclude="${exclude}"`);
    if (!q) return res.json([]);

    const results = await searchUsers(q, exclude);
    console.log(`Search results for "${q}": ${results.map(r => r.username).join(", ")}`);
    res.json(results);
  } catch (e) {
    console.error(e);
    res.status(500).json({ error: "Server error" });
  }
});

// Create extension request
app.post("/api/requests", async (req, res) => {
  try {
    const { requester, approver, minutes } = req.body;
    if (!requester || !approver) return res.status(400).json({ error: "Invalid request" });
    const request = await createRequest({
      requester: requester.toLowerCase(),
      approver: approver.toLowerCase(),
      minutes: Number(minutes) || 15
    });
    res.status(201).json(request);
  } catch (e) {
    console.error(e);
    res.status(500).json({ error: "Server error" });
  }
});

// Get incoming requests for a user
app.get("/api/requests/inbox/:username", async (req, res) => {
  try {
    const requests = await getInbox(req.params.username.toLowerCase());
    res.json(requests);
  } catch (e) {
    console.error(e);
    res.status(500).json({ error: "Server error" });
  }
});

// Approve/Reject a request
app.post("/api/requests/:id/decision", async (req, res) => {
  try {
    const { status } = req.body; // "APPROVED" or "REJECTED"
    if (!["APPROVED", "REJECTED"].includes(status)) {
      return res.status(400).json({ error: "Invalid status" });
    }
    const updated = await updateRequestStatus(req.params.id, status);
    if (!updated) return res.status(404).json({ error: "Request not found" });
    res.json(updated);
  } catch (e) {
    console.error(e);
    res.status(500).json({ error: "Server error" });
  }
});

// Acknowledge a protection event
app.patch("/api/events/:id/acknowledge", async (req, res) => {
  try {
    const { id } = req.params;
    const { acknowledgeAuditEvent } = await import("./store.js");
    const updated = await acknowledgeAuditEvent(id);
    if (!updated) {
      return res.status(404).json({ error: "Event not found" });
    }
    res.json(updated);
  } catch (e) {
    console.error(e);
    res.status(500).json({ error: "Server error" });
  }
});

// Poll specific request status
app.get("/api/requests/:id", async (req, res) => {
  try {
    const request = await getRequestById(req.params.id);
    if (!request) return res.status(404).json({ error: "Request not found" });
    res.json(request);
  } catch (e) {
    console.error(e);
    res.status(500).json({ error: "Server error" });
  }
});

// Receive heartbeat from app
app.get("/api/debug/heartbeats", async (req, res) => {
  try {
    const { getAllHeartbeats } = await import("./store.js");
    const heartbeats = await getAllHeartbeats();
    res.json(heartbeats);
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

app.post("/api/heartbeat", async (req, res) => {
  try {
    const { username, protectionActive, friends } = req.body;
    console.log(`[HEARTBEAT_POST] Received heartbeat from ${username}. Active: ${protectionActive}`);
    if (!username) return res.status(400).json({ error: "username required" });
    await recordHeartbeat(username, protectionActive !== false, friends || []);
    res.json({ status: "ok" });
  } catch (e) {
    console.error(e);
    res.status(500).json({ error: "Server error" });
  }
});

// Register/update FCM token for push notifications
app.post("/api/fcm-token", async (req, res) => {
  try {
    const { username, token } = req.body;
    if (!username || !token) return res.status(400).json({ error: "username and token required" });
    await registerFcmToken(username, token);
    res.json({ status: "ok" });
  } catch (e) {
    console.error(e);
    res.status(500).json({ error: "Server error" });
  }
});

// Protection events reported by app's ProtectionMonitor
app.post("/api/protection-events", async (req, res) => {
  try {
    const { username, reason, timestamp, friends } = req.body;
    if (!username) return res.status(400).json({ error: "username required" });
    await recordProtectionEvent({ username, reason, timestamp: timestamp || Date.now(), friends: friends || [] });
    res.json({ status: "ok" });
  } catch (e) {
    console.error(e);
    res.status(500).json({ error: "Server error" });
  }
});

// Catch-all for debugging 404s
app.use((req, res) => {
  console.log(`[404] Unmatched request: ${req.method} ${req.url}`);
  if (req.url.includes("google")) {
    console.log("[DEBUG] Auth route mismatch detected!");
  }
  console.log(`[404] Headers: ${JSON.stringify(req.headers)}`);
  res.status(404).json({
    error: "Not Found",
    method: req.method,
    url: req.url,
    message: `Route ${req.method} ${req.url} not found on this server.`
  });
});

app.listen(PORT, "0.0.0.0", () => {
  console.log(`ScrollBuddy In-App API running on port ${PORT}`);
  console.log(`GOOGLE_CLIENT_ID: ${GOOGLE_CLIENT_ID ? 'Configured' : 'MISSING'}`);
  console.log(`Firebase Admin: ${admin.apps.length > 0 ? 'Initialized' : 'Not configured'}`);

  // Start heartbeat checker
  startHeartbeatChecker();

  // List all routes for debugging
  console.log("Registered Routes:");
  app._router.stack.forEach((r) => {
    if (r.route && r.route.path) {
      const methods = Object.keys(r.route.methods).join(',').toUpperCase();
      console.log(`  ${methods.padEnd(6)} ${r.route.path}`);
    }
  });
});
