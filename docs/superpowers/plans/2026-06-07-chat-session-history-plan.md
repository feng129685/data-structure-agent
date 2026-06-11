# Chat Session History Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add saved conversation history and new-conversation support for “智能体伴学” and “课堂讨论”.

**Architecture:** Keep the existing single-file frontend shape and add a small session-history layer around the current message state. Add a new server-side `chat_threads` table and REST endpoints while preserving the existing `/api/conversations` compatibility API.

**Tech Stack:** Vanilla HTML/CSS/JavaScript in `prototype.html`, Node.js HTTP server in `server.js`, SQLite via `better-sqlite3`, static verification scripts in Node.js.

---

### Task 1: Server-Side Chat Thread Storage

**Files:**
- Modify: `server.js`
- Test: `scripts/verify-chat-threads-static.js`

- [ ] **Step 1: Add database table**

Add `chat_threads` in `initDatabase()`:

```sql
CREATE TABLE IF NOT EXISTS chat_threads (
  id TEXT PRIMARY KEY,
  user_id INTEGER NOT NULL,
  type TEXT NOT NULL,
  scenario TEXT NOT NULL,
  title TEXT NOT NULL,
  messages TEXT NOT NULL DEFAULT '[]',
  classroom_state TEXT,
  created_at TEXT DEFAULT (datetime('now')),
  updated_at TEXT DEFAULT (datetime('now')),
  FOREIGN KEY (user_id) REFERENCES users(id)
);
```

- [ ] **Step 2: Add thread handlers**

Implement handlers for listing, creating/updating, and deleting user-owned threads:

```js
function sanitizeChatThreadPayload(body, fallbackId) {
  const type = body.type === "classroom" ? "classroom" : "coach";
  const id = String(body.id || fallbackId || "").replace(/[^a-zA-Z0-9_-]/g, "").slice(0, 48);
  const scenario = String(body.scenario || "choose").replace(/[^a-z]/g, "").slice(0, 24) || "choose";
  const title = String(body.title || "新的对话").trim().slice(0, 60) || "新的对话";
  const messages = Array.isArray(body.messages) ? body.messages.slice(-80) : [];
  const classroomState = body.classroomState && typeof body.classroomState === "object" ? body.classroomState : null;
  return { id, type, scenario, title, messages, classroomState };
}
```

- [ ] **Step 3: Add routes**

Add:

```text
GET /api/chat-threads
POST /api/chat-threads
PUT /api/chat-threads/:id
DELETE /api/chat-threads/:id
```

All endpoints require login and only read/write the authenticated user’s rows.

- [ ] **Step 4: Verify static markers**

Create `scripts/verify-chat-threads-static.js` and assert that `server.js` contains the table, handlers, and routes.

### Task 2: Frontend Session State

**Files:**
- Modify: `prototype.html`
- Test: `scripts/verify-chat-threads-static.js`

- [ ] **Step 1: Add state fields**

Add:

```js
chatThreads: [],
activeCoachThreadId: "",
activeClassroomThreadId: "",
threadDrawerOpen: false,
threadDrawerType: "coach"
```

- [ ] **Step 2: Add helpers**

Add helpers to create IDs, infer titles, normalize threads, find the active thread, save current messages into the active thread, and start a new thread.

- [ ] **Step 3: Preserve existing behavior**

Keep `currentSession()` and the existing `sessions` object working, so older code paths and `/api/conversations` do not break.

### Task 3: Frontend UI

**Files:**
- Modify: `prototype.html`

- [ ] **Step 1: Add lightweight controls**

Add small “新对话” and “历史” buttons in the coach panel header and classroom stage header.

- [ ] **Step 2: Add one shared drawer**

Add a shared drawer component with:

```text
历史对话
登录后可云端同步
thread list
关闭
```

Each item shows title, type, updated time, and buttons for continuing or deleting.

- [ ] **Step 3: Wire events**

Clicking “新对话” saves the current thread and opens a blank conversation.

Clicking “历史” opens the drawer for the current area.

Clicking “继续” restores that thread.

Clicking “删除” asks for confirmation and removes only that thread.

### Task 4: Sync and Verification

**Files:**
- Modify: `prototype.html`
- Modify: `server.js`
- Test: `scripts/verify-chat-threads-static.js`

- [ ] **Step 1: Add API sync**

When logged in, load `/api/chat-threads` after auth initialization and merge into local `chatThreads`.

When messages change, save the active thread with `POST` or `PUT`.

- [ ] **Step 2: Local fallback**

For guests or failed network saves, keep localStorage state and show a small toast if cloud sync fails.

- [ ] **Step 3: Run verification**

Run:

```powershell
node scripts/verify-chat-threads-static.js
node scripts/verify-classroom-turns-static.js
node scripts/verify-orchestrator-static.js
node scripts/verify-orchestrator-behavior.js
node scripts/verify-animation-loop-static.js
node -e "const fs=require('fs'); const html=fs.readFileSync('prototype.html','utf8'); const scripts=[...html.matchAll(/<script>([\\s\\S]*?)<\\/script>/g)].map(m=>m[1]); scripts.forEach((s,i)=>new Function(s)); console.log('inline scripts OK', scripts.length)"
```

Expected result: all scripts pass and inline scripts parse without syntax errors.

### Task 5: Deploy

**Files:**
- Local: `prototype.html`, `server.js`
- Remote: `/home/feng/sites/data-structure-agent`
- Remote static root: `/var/www/data-structure-agent`

- [ ] **Step 1: Copy changed files to the server**

Copy `prototype.html`, `server.js`, and the new verification script to the application directory.

- [ ] **Step 2: Copy static HTML to public root**

Copy `prototype.html` to both:

```text
/var/www/data-structure-agent/prototype.html
/var/www/data-structure-agent/index.html
```

- [ ] **Step 3: Restart backend and health check**

Restart the Node service and check:

```text
GET https://agent.example.com/healthz
```

Expected: health response is OK and model remains `mimo-v2.5-pro`.

### Self-Review

- Spec coverage: The plan covers visible new conversation controls, history drawer, cloud storage, guest local fallback, classroom state restoration, and compatibility with old conversation APIs.
- Placeholder scan: No placeholder tasks remain.
- Type consistency: The design uses `chatThreads`, `activeCoachThreadId`, `activeClassroomThreadId`, and `classroomState` consistently across frontend and backend.
