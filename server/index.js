import express from "express";
import { OAuth2Client } from "google-auth-library";
import admin from "firebase-admin";
import { startHeartbeatChecker, initFirebaseForChecker } from "./heartbeat-checker.js";
import {
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

const app = express();
const PORT = Number(process.env.PORT) || 3000;
const GOOGLE_CLIENT_ID = process.env.GOOGLE_CLIENT_ID;

const googleClient = new OAuth2Client(GOOGLE_CLIENT_ID);

// Initialize Firebase Admin SDK for FCM
const firebaseCredentials = process.env.FIREBASE_SERVICE_ACCOUNT_JSON;
if (firebaseCredentials) {
  try {
    const serviceAccount = JSON.parse(firebaseCredentials);
    admin.initializeApp({
      credential: admin.credential.cert(serviceAccount)
    });
    initFirebaseForChecker(admin);
    console.log("[FIREBASE] Admin SDK initialized");
  } catch (e) {
    console.error("[FIREBASE] Failed to initialize:", e.message);
  }
} else {
  console.warn("[FIREBASE] FIREBASE_SERVICE_ACCOUNT_JSON not set, FCM notifications disabled");
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

    let existingUser = getUserByGoogleUid(googleUid);

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

    const result = registerGoogleUser({
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
app.get("/api/profile/:username", (req, res) => {
  const user = getUserProfile(req.params.username);
  if (!user) return res.status(404).json({ error: "User not found" });
  res.json(user);
});

// Registration (Legacy/Fallback)
app.post("/api/register", (req, res) => {
  let { username } = req.body;
  console.log(`[REGISTER] Legacy request for username: ${username}`);
  if (!username) return res.status(400).json({ error: "Username required" });
  username = username.trim().toLowerCase();
  const result = registerUser(username);
  if (result.error) return res.status(result.status).json({ error: result.error });
  res.status(result.status).json(result.user);
});

// Search users (to add friend)
app.get("/api/users/search", (req, res) => {
  const { q, exclude } = req.query;
  console.log(`Search request received: q="${q}", exclude="${exclude}"`);
  if (!q) return res.json([]);

  const results = searchUsers(q, exclude);
  console.log(`Search results for "${q}": ${results.map(r => r.username).join(", ")}`);
  res.json(results);
});

// Create extension request
app.post("/api/requests", (req, res) => {
  const { requester, approver, minutes } = req.body;
  if (!requester || !approver) return res.status(400).json({ error: "Invalid request" });
  const request = createRequest({
    requester: requester.toLowerCase(),
    approver: approver.toLowerCase(),
    minutes: Number(minutes) || 15
  });
  res.status(201).json(request);
});

// Get incoming requests for a user
app.get("/api/requests/inbox/:username", (req, res) => {
  const requests = getInbox(req.params.username.toLowerCase());
  res.json(requests);
});

// Approve/Reject a request
app.post("/api/requests/:id/decision", (req, res) => {
  const { status } = req.body; // "APPROVED" or "REJECTED"
  if (!["APPROVED", "REJECTED"].includes(status)) {
    return res.status(400).json({ error: "Invalid status" });
  }
  const updated = updateRequestStatus(req.params.id, status);
  if (!updated) return res.status(404).json({ error: "Request not found" });
  res.json(updated);
});

// Poll specific request status
app.get("/api/requests/:id", (req, res) => {
  const request = getRequestById(req.params.id);
  if (!request) return res.status(404).json({ error: "Request not found" });
  res.json(request);
});

// Receive heartbeat from app
app.post("/api/heartbeat", (req, res) => {
  const { username, protectionActive, friends } = req.body;
  if (!username) return res.status(400).json({ error: "username required" });
  recordHeartbeat(username, protectionActive !== false, friends || []);
  res.json({ status: "ok" });
});

// Register/update FCM token for push notifications
app.post("/api/fcm-token", (req, res) => {
  const { username, token } = req.body;
  if (!username || !token) return res.status(400).json({ error: "username and token required" });
  registerFcmToken(username, token);
  res.json({ status: "ok" });
});

// Protection events reported by app's ProtectionMonitor
app.post("/api/protection-events", (req, res) => {
  const { username, reason, timestamp, friends } = req.body;
  if (!username) return res.status(400).json({ error: "username required" });
  recordProtectionEvent({ username, reason, timestamp: timestamp || Date.now(), friends: friends || [] });
  res.json({ status: "ok" });
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
