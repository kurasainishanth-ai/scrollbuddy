import fs from "fs";
import path from "path";
import { fileURLToPath } from "url";
import { randomUUID } from "crypto";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const DB_DIR = path.join(__dirname, "data");
const USERS_PATH = path.join(DB_DIR, "users.json");
const REQUESTS_PATH = path.join(DB_DIR, "requests.json");
const HEARTBEATS_PATH = path.join(DB_DIR, "heartbeats.json");
const FCM_TOKENS_PATH = path.join(DB_DIR, "fcm_tokens.json");
const AUDIT_LOG_PATH = path.join(DB_DIR, "audit_log.json");

function ensureDb() {
  if (!fs.existsSync(DB_DIR)) fs.mkdirSync(DB_DIR, { recursive: true });
  if (!fs.existsSync(USERS_PATH)) fs.writeFileSync(USERS_PATH, "{}", "utf8");
  if (!fs.existsSync(REQUESTS_PATH)) fs.writeFileSync(REQUESTS_PATH, "{}", "utf8");
  if (!fs.existsSync(HEARTBEATS_PATH)) fs.writeFileSync(HEARTBEATS_PATH, "{}", "utf8");
  if (!fs.existsSync(FCM_TOKENS_PATH)) fs.writeFileSync(FCM_TOKENS_PATH, "{}", "utf8");
  if (!fs.existsSync(AUDIT_LOG_PATH)) fs.writeFileSync(AUDIT_LOG_PATH, "[]", "utf8");
}

function readUsers() {
  ensureDb();
  return JSON.parse(fs.readFileSync(USERS_PATH, "utf8"));
}

function writeUsers(data) {
  fs.writeFileSync(USERS_PATH, JSON.stringify(data, null, 2), "utf8");
}

function readRequests() {
  ensureDb();
  return JSON.parse(fs.readFileSync(REQUESTS_PATH, "utf8"));
}

function writeRequests(data) {
  fs.writeFileSync(REQUESTS_PATH, JSON.stringify(data, null, 2), "utf8");
}

export function registerUser(username) {
  const users = readUsers();
  if (users[username]) {
    return { error: "Username already exists", status: 409 };
  }
  const user = { username, createdAt: Date.now() };
  users[username] = user;
  writeUsers(users);
  return { user, status: 201 };
}

export function getUserByGoogleUid(googleUid) {
  const users = readUsers();
  return Object.values(users).find(u => u.googleUid === googleUid) || null;
}

export function registerGoogleUser({ googleUid, email, displayName, photoUrl, username }) {
  const users = readUsers();
  if (users[username]) {
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
  users[username] = user;
  writeUsers(users);
  return { user, status: 201 };
}

export function getUserProfile(username) {
  const users = readUsers();
  return users[username.toLowerCase()] || null;
}

export function searchUsers(query, exclude) {
  const users = readUsers();
  const q = query.toLowerCase();
  const excludeLower = exclude ? exclude.toLowerCase() : null;
  return Object.values(users)
    .filter((u) => {
      const uName = u.username.toLowerCase();
      return uName.includes(q) && uName !== excludeLower;
    })
    .map((u) => ({ username: u.username }));
}

export function createRequest({ requester, approver, minutes }) {
  const requests = readRequests();
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
  requests[id] = record;
  writeRequests(requests);
  return record;
}

export function getInbox(username) {
  const requests = readRequests();
  return Object.values(requests)
    .filter((r) => r.approver === username && r.status === "PENDING")
    .sort((a, b) => b.createdAt - a.createdAt);
}

export function updateRequestStatus(id, status) {
  const requests = readRequests();
  if (!requests[id]) return null;
  requests[id].status = status;
  requests[id].decidedAt = Date.now();
  writeRequests(requests);
  return requests[id];
}

export function getRequestById(id) {
  return readRequests()[id] || null;
}

// --- Heartbeat storage ---

function readHeartbeats() {
  ensureDb();
  return JSON.parse(fs.readFileSync(HEARTBEATS_PATH, "utf8"));
}

function writeHeartbeats(data) {
  fs.writeFileSync(HEARTBEATS_PATH, JSON.stringify(data, null, 2), "utf8");
}

export function recordHeartbeat(username, protectionActive, friends) {
  const heartbeats = readHeartbeats();
  const now = Date.now();
  const existing = heartbeats[username] || {};
  heartbeats[username] = {
    lastHeartbeat: now,
    protectionActive,
    protectionStatus: "ACTIVE",
    lastSeen: now,
    friends: friends || existing.friends || [],
    lostAt: null
  };
  writeHeartbeats(heartbeats);
}

export function getAllHeartbeats() {
  return readHeartbeats();
}

export function markProtectionLost(username, timestamp) {
  const heartbeats = readHeartbeats();
  if (!heartbeats[username]) return;
  heartbeats[username].protectionStatus = "LOST";
  heartbeats[username].protectionActive = false;
  heartbeats[username].lostAt = timestamp;
  writeHeartbeats(heartbeats);
}

// --- FCM token storage ---

function readFcmTokens() {
  ensureDb();
  return JSON.parse(fs.readFileSync(FCM_TOKENS_PATH, "utf8"));
}

function writeFcmTokens(data) {
  fs.writeFileSync(FCM_TOKENS_PATH, JSON.stringify(data, null, 2), "utf8");
}

export function registerFcmToken(username, token) {
  const tokens = readFcmTokens();
  tokens[username] = token;
  writeFcmTokens(tokens);
}

export function getFcmTokensForUsers(usernames) {
  const tokens = readFcmTokens();
  const result = {};
  for (const username of usernames) {
    if (tokens[username]) {
      result[username] = tokens[username];
    }
  }
  return result;
}

// --- Audit log ---

function readAuditLog() {
  ensureDb();
  return JSON.parse(fs.readFileSync(AUDIT_LOG_PATH, "utf8"));
}

function writeAuditLog(data) {
  fs.writeFileSync(AUDIT_LOG_PATH, JSON.stringify(data, null, 2), "utf8");
}

export function recordAuditEvent(event) {
  const log = readAuditLog();
  log.push({
    id: randomUUID(),
    ...event,
    recordedAt: Date.now()
  });
  // Keep last 1000 entries
  if (log.length > 1000) log.splice(0, log.length - 1000);
  writeAuditLog(log);
}

// --- Protection events (called by app's ProtectionMonitor) ---

export function recordProtectionEvent({ username, reason, timestamp, friends }) {
  recordAuditEvent({
    type: "PROTECTION_EVENT",
    username,
    reason,
    timestamp,
    friends
  });
  // Also mark heartbeat as lost since the app reported a problem
  markProtectionLost(username, timestamp || Date.now());
}
