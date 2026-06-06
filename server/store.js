import fs from "fs";
import path from "path";
import { fileURLToPath } from "url";
import { randomUUID } from "crypto";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const DB_DIR = path.join(__dirname, "data");
const USERS_PATH = path.join(DB_DIR, "users.json");
const REQUESTS_PATH = path.join(DB_DIR, "requests.json");

function ensureDb() {
  if (!fs.existsSync(DB_DIR)) fs.mkdirSync(DB_DIR, { recursive: true });
  if (!fs.existsSync(USERS_PATH)) fs.writeFileSync(USERS_PATH, "{}", "utf8");
  if (!fs.existsSync(REQUESTS_PATH)) fs.writeFileSync(REQUESTS_PATH, "{}", "utf8");
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
