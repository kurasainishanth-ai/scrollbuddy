import fs from "fs";
import path from "path";
import { fileURLToPath } from "url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const DB_PATH = path.join(__dirname, "data", "requests.json");

function ensureDb() {
  const dir = path.dirname(DB_PATH);
  if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true });
  if (!fs.existsSync(DB_PATH)) fs.writeFileSync(DB_PATH, "{}", "utf8");
}

function readAll() {
  ensureDb();
  return JSON.parse(fs.readFileSync(DB_PATH, "utf8"));
}

function writeAll(data) {
  ensureDb();
  fs.writeFileSync(DB_PATH, JSON.stringify(data, null, 2), "utf8");
}

export function createRequest({ token, minutes, approverPhone }) {
  const data = readAll();
  const id = crypto.randomUUID();
  const now = Date.now();
  const record = {
    id,
    token,
    minutes,
    approverPhone: approverPhone || null,
    status: "PENDING",
    createdAt: now,
    expiresAt: now + 24 * 60 * 60 * 1000,
    decidedAt: null,
  };
  data[id] = record;
  writeAll(data);
  return record;
}

export function getById(id) {
  return readAll()[id] ?? null;
}

export function getByToken(token) {
  const data = readAll();
  return Object.values(data).find((r) => r.token === token) ?? null;
}

export function decideByToken(token, decision) {
  const data = readAll();
  const entry = Object.entries(data).find(([, r]) => r.token === token);
  if (!entry) return null;

  const [id, record] = entry;
  if (record.status !== "PENDING") return record;
  if (Date.now() > record.expiresAt) {
    record.status = "EXPIRED";
    record.decidedAt = Date.now();
    data[id] = record;
    writeAll(data);
    return record;
  }

  record.status = decision === "approve" ? "APPROVED" : "REJECTED";
  record.decidedAt = Date.now();
  data[id] = record;
  writeAll(data);
  return record;
}

export function normalizeStatus(record) {
  if (!record) return null;
  if (record.status === "PENDING" && Date.now() > record.expiresAt) {
    return { ...record, status: "EXPIRED" };
  }
  return record;
}
