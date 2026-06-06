import express from "express";
import {
  registerUser,
  searchUsers,
  createRequest,
  getInbox,
  updateRequestStatus,
  getRequestById
} from "./store.js";

const app = express();
const PORT = Number(process.env.PORT) || 3000;

app.use(express.json());

// Registration
app.post("/api/register", (req, res) => {
  let { username } = req.body;
  console.log(`Registration request received for username: ${username}`);
  if (!username) {
    console.log("Response status: 400 (Username required)");
    return res.status(400).json({ error: "Username required" });
  }

  // Sanitize
  username = username.trim().toLowerCase();

  // Validate: lowercase letters, numbers, underscores, dashes, 3-20 chars
  const usernameRegex = /^[a-z0-9_-]{3,20}$/;
  if (!usernameRegex.test(username)) {
    console.log(`Response status: 400 (Invalid username: ${username})`);
    return res.status(400).json({
      error: "Invalid username. Use 3-20 characters: letters, numbers, underscores, or dashes only."
    });
  }

  const result = registerUser(username);
  if (result.error) {
    console.log(`Response status: ${result.status} (Error: ${result.error})`);
    return res.status(result.status).json({ error: result.error });
  }

  console.log(`Response status: ${result.status} (Success)`);
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

app.listen(PORT, "0.0.0.0", () => {
  console.log(`ScrollBuddy In-App API running on port ${PORT}`);
});
