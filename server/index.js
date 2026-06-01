import express from "express";
import { randomUUID } from "crypto";
import {
  createRequest,
  decideByToken,
  getById,
  getByToken,
  normalizeStatus,
} from "./store.js";

const app = express();
const PORT = Number(process.env.PORT) || 3000;

// Improved PUBLIC_URL detection for real-world network switching
app.use((req, res, next) => {
  if (!process.env.PUBLIC_URL) {
    const host = req.get("host");
    const protocol = req.protocol;
    // Dynamically set PUBLIC_URL if not hardcoded in env
    req.dynamicPublicUrl = `${protocol}://${host}`;
  } else {
    req.dynamicPublicUrl = process.env.PUBLIC_URL.replace(/\/$/, "");
  }
  next();
});

app.use(express.json());

app.use((req, res, next) => {
  res.setHeader("Access-Control-Allow-Origin", "*");
  res.setHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
  res.setHeader("Access-Control-Allow-Headers", "Content-Type");
  if (req.method === "OPTIONS") return res.sendStatus(204);
  next();
});

function publicRecord(record) {
  const r = normalizeStatus(record);
  if (!r) return null;
  return {
    id: r.id,
    status: r.status,
    minutes: r.minutes,
    createdAt: r.createdAt,
    expiresAt: r.expiresAt,
    decidedAt: r.decidedAt,
  };
}

/** App: create a pending unlock request */
app.post("/api/requests", (req, res) => {
  const minutes = Number(req.body?.minutes) || 15;
  const token = randomUUID();
  const record = createRequest({ token, minutes });
  res.status(201).json({
    ...publicRecord(record),
    approvalUrl: `${req.dynamicPublicUrl}/approve/${token}`,
  });
});

/** App: poll until APPROVED / REJECTED / EXPIRED */
app.get("/api/requests/:id", (req, res) => {
  const record = normalizeStatus(getById(req.params.id));
  if (!record) return res.status(404).json({ error: "Request not found" });
  res.json(publicRecord(record));
});

/** Friend: approve / reject page */
app.get("/approve/:token", (req, res) => {
  const record = normalizeStatus(getByToken(req.params.token));
  if (!record) {
    return res.status(404).send(page("Request not found", "", false));
  }
  if (record.status !== "PENDING") {
    const label =
      record.status === "APPROVED"
        ? "already approved"
        : record.status === "REJECTED"
          ? "already rejected"
          : "expired";
    return res.send(
      page(
        `This request was ${label}.`,
        `<p>No further action is needed.</p>`,
        false
      )
    );
  }

  const body = `
    <p><strong>Scroll Sentry</strong> — your friend hit their Instagram limit and is asking for <strong>${record.minutes} more minutes</strong>.</p>
    <form method="post" action="/api/approve/${record.token}" style="display:flex;gap:12px;margin-top:24px">
      <button name="decision" value="approve" type="submit" style="flex:1;padding:14px;font-size:16px;background:#16a34a;color:#fff;border:none;border-radius:8px;cursor:pointer">Approve</button>
      <button name="decision" value="reject" type="submit" style="flex:1;padding:14px;font-size:16px;background:#dc2626;color:#fff;border:none;border-radius:8px;cursor:pointer">Reject</button>
    </form>
  `;
  res.send(page("Friend approval needed", body, true));
});

/** Friend: form POST from browser */
app.post("/api/approve/:token", express.urlencoded({ extended: true }), (req, res) => {
  const decision = req.body?.decision === "approve" ? "approve" : "reject";
  const record = decideByToken(req.params.token, decision);
  if (!record) {
    return res.status(404).send(page("Request not found", "", false));
  }
  const msg =
    record.status === "APPROVED"
      ? `Approved. Your friend gets ${record.minutes} extra minutes.`
      : record.status === "REJECTED"
        ? "Rejected. Instagram stays locked on their phone."
        : `This request is ${record.status.toLowerCase()}.`;
  res.send(page("Done", `<p>${msg}</p>`, false));
});

/** Friend: JSON API (optional) */
app.post("/api/approve/:token/json", (req, res) => {
  const decision = req.body?.decision === "approve" ? "approve" : "reject";
  const record = decideByToken(req.params.token, decision);
  if (!record) return res.status(404).json({ error: "Request not found" });
  res.json(publicRecord(record));
});

function page(title, body, showHeader) {
  return `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>${title} — Scroll Sentry</title>
  <style>
    body { font-family: system-ui, sans-serif; max-width: 420px; margin: 40px auto; padding: 0 16px; color: #111; }
    h1 { font-size: 1.35rem; }
    .card { background: #f4f4f5; border-radius: 12px; padding: 20px; margin-top: 16px; }
  </style>
</head>
<body>
  ${showHeader ? "<h1>Scroll Sentry</h1>" : ""}
  <div class="card">
    <h1 style="margin-top:0">${title}</h1>
    ${body}
  </div>
</body>
</html>`;
}

app.listen(PORT, "0.0.0.0", () => {
  console.log(`Scroll Sentry server running on port ${PORT}`);
  console.log(`If running locally, use your PC's LAN IP in the Android app.`);
  console.log(`If deployed, ensure PUBLIC_URL environment variable is set for link generation.`);
});
