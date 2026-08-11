# Node and Spring API difference matrix

The public proxy is intentionally path-based during migration. Caddy sends
`/api/v1/*` to Spring on 8792 and the older `/api/*` surface to Node on 8791.
These are not interchangeable versions of one contract. Clients must choose a
complete workflow and use the matching request and response shape.

| Workflow | Node compatibility API | Spring API | Difference / migration rule |
|---|---|---|---|
| Health | `GET /healthz` | `GET /actuator/health` (loopback only) | Node health is public through the root service; Actuator is not routed by Caddy and hides details in prod. |
| Request code | `POST /api/auth/request-code` | `POST /api/v1/auth/request-code` | Both require an email and purpose; Node returns `400 CODE_PURPOSE_INVALID` when `purpose` is missing or unsupported. Production requires real SMTP and never echoes a code. Do not assume the Node error envelope or code is the Spring v1 error contract. |
| Register | `POST /api/auth/register` | `POST /api/v1/auth/register` | Node accepts a 6-character minimum password; Spring validates at least 8 characters and persists roles/status in MySQL. |
| Password login | `POST /api/auth/login` | `POST /api/v1/auth/login` | Both return a Bearer token. Spring also emits the `HttpOnly; Secure; SameSite=Strict` `ds_session` cookie. |
| Code login | `POST /api/auth/verify-code` | No equivalent endpoint | Spring code flow is request-code followed by register/login/reset; do not map this Node endpoint to register. |
| Current user | `GET /api/auth/me` | `GET /api/v1/users/me` | Response field names and role representation differ; use the OpenAPI contract for Spring. |
| Logout | No Node endpoint; client drops local token | `POST /api/v1/auth/logout` | Spring clears the secure cookie. A Node token remains valid until its 7-day expiry unless the client removes it. |
| One chat turn | `POST /api/chat` | `POST /api/v1/chat` | Node accepts the legacy UI envelope and may persist to SQLite; Spring uses the reviewed `ChatRequest` and MySQL session/message rows. |
| Chat SSE | `POST /api/chat` with stream request | `POST /api/v1/chat/stream` | Node streams on the same path with legacy `data:` frames. Spring has named `sources`, `delta`, `done`, and `error` events. |
| Chat history | `GET/PUT/DELETE /api/conversations` and `/api/chat-threads` | `GET/DELETE /api/v1/chat/sessions` and `/api/v1/chat/sessions/{id}` | Node has scenario drafts and typed thread JSON. Spring exposes owned, bounded sessions and message records only. |
| Chapters/resources | `GET /pdfs/*` and upload routes | `GET /api/v1/chapters`, `/chapters/{id}/resources`, `/resources/{id}`, `/resources/{id}/content` | Spring gates chapter/resource status and license scope in every list/detail/content path. Do not expose `RESOURCE_DIR` or map `/pdfs` to it. |
| Knowledge search | `GET /api/knowledge/search?q=...&scenario=...` (Node debug route) | `GET /api/v1/knowledge/search?q=...&chapterId=...&limit=...` | Spring is the reviewed, guest-compatible contract. It requires a non-blank query (max 500), optionally scopes by `chapterId` (max 64), and bounds numeric `limit` to 1-6 (default 4); a non-numeric `limit` returns `400 KNOWLEDGE_LIMIT_INVALID`. Only `PUBLISHED` chunks in published chapters/resources are eligible; guests see `PUBLIC`, students add `CLASSROOM_ONLY`, and teachers/admins add `TEAM_ONLY`. Spring returns `{ok, query, results}` with safe source/page/review/publication metadata, bounded excerpts, and scores. Node returns `{ok, query, knowledge, results}`, uses its fixed configured limit, and is a debug/inspection surface. `NODE_ENV=production` forces that route off even when `KNOWLEDGE_DEBUG_API=true`, returning 404; it must stay disabled in production. |
| Private PPT plan | `GET /api/classroom/presentation-plan?lessonId=...` | No equivalent HTTP route yet | Node returns sanitized slide cards and signed image URLs. The `/presentation/*` asset route must remain on Node. |
| Classroom | `GET /api/assignments` is an authenticated recipient read of active, target-visible tasks; `POST /api/teacher/assignments` is teacher-only create/update of the caller's own task; `DELETE /api/teacher/assignments/{id}` is creator-only archive. Node also has `/api/teacher/*` and `/api/learning-snapshot`. | `GET /api/v1/classroom/scripts`, `POST /api/v1/classroom/sessions`, owned session/actions, and `/api/v1/learning/*` | Do not collapse Node's student-facing `GET /api/assignments` into the teacher write route: the GET only needs authentication and filters task visibility for the current user, while the POST requires the teacher rule and constrains updates by `teacher_id`. Node stores aggregate snapshots and teacher assignments in SQLite. Spring stores reviewed scripts, state transitions, evidence, and per-chapter progress in MySQL. |
| Animation simulation | `POST /api/animation/simulate` (Node also has a direct-only `/api/v1/animations/simulate` branch) | `POST /api/v1/animations/simulate` | Public `/api/v1/*` always reaches Spring, so the duplicate Node branch is not a migration bridge. Both normalize the optional DSVP `context`; Node returns renderer data only. Spring verifies classroom/PPT/explicit chapter sources, returns `evidencePersisted=false` for a context-free preview, and atomically links formal evidence to `animation_records`, `dsvp_request_snapshots`, and chapter progress. |
| Animation generation/observation | No stable Node equivalent | `POST /api/v1/animations/generate`, `POST /api/v1/animations/{id}/observations` | Spring validates the schema, binds ownership, and persists append-only observations. |
| Code execution | `POST /api/execute` | `POST /api/v1/code/runs` | Node tries Judge0 then Piston and accepts C, C++, Python, JavaScript, and Java. Spring accepts C/Python through Piston and persists signed-in runs. Both enforce size/time/concurrency limits. |
| Code analysis | No stable Node endpoint | `POST /api/v1/code/analyze` | Spring can analyze guest code or an owned `runId`; it creates trusted `CODE_REVIEW` evidence only for an owned run. |
| Learning activity | Aggregate `PUT/GET/DELETE /api/learning-snapshot` | `GET /api/v1/learning/progress`, `POST /api/v1/learning/events` | Spring accepts only the four generic event types; classroom answers, animation observations, DSVP simulations, and code reviews are server-managed. |
| Uploads | `POST /api/upload`, teacher-only `/api/upload-pdf` | No public upload-to-publish route | Production resource import is an operator/database workflow. Uploaded files must not become a public static directory. |

## Authentication and browser behavior

Node returns a token in JSON and the existing frontend stores it as a Bearer
token. Node does not set a session cookie. Spring accepts both the Bearer token
and `ds_session`; its cookie is secure and strict in production. A frontend
change must not assume that a successful Node response can be replayed against
Spring without adapting the endpoint, password policy, ownership rules, and
response schema.

During the current migration, Node signs its local Bearer token with the
separate `NODE_COMPAT_JWT_SECRET`; Spring keeps `JWT_SECRET` private. When
`NODE_COMPAT_ENABLED=true`, Spring accepts a valid Node token only from the
`Authorization: Bearer` header and only for `GET /api/v1/learning/progress`,
`POST /api/v1/learning/events`, `POST /api/v1/animations/simulate`, and an
owned `POST /api/v1/animations/{id}/observations`. It resolves an existing
MySQL user by verified email and uses only that account's database roles,
never the Node token's roles. If no Spring account exists, the compatibility
bridge creates a non-loginable `STUDENT` mirror only; it never grants teacher
or administrator access implicitly. All other Spring endpoints require a
standard Spring token or `ds_session` cookie.

For knowledge search specifically, the current browser sends the v1 request first and retries the legacy Node route only when v1 returns HTTP 404. A v1 `400` (for example, a missing or oversized query) or any upstream/server error is shown as an error and is not silently downgraded to the debug route. The Node route is not merely default-disabled: `NODE_ENV=production` forces it off even if `KNOWLEDGE_DEBUG_API=true`, so production clients must treat the Spring response shape as canonical and keep the fallback only for older non-production-compatible deployments that have not yet exposed v1.

Both services use exact-origin CORS with credentials enabled. The only allowed
production origin is `https://structify.cn`; do not use `*` with credentials.

## Streaming and proxy rules

- Keep `Content-Type: text/event-stream`, `Cache-Control: no-cache`, and
  connection keep-alive intact for Spring `/chat/stream` and legacy Node chat
  streaming.
- Disable proxy buffering for those paths. The supplied Caddyfile uses
  `flush_interval -1` and does not add a WebSocket-only route.
- No current controller or Node branch implements WebSocket messages. A future
  WebSocket endpoint must add an explicit contract, authentication decision,
  smoke test, and route before the frontend uses it.

## Contract change rule

Change `contracts/openapi-v1.yaml` or the relevant JSON Schema first, then
update one complete caller workflow. Keep this matrix and the data-model
matrix in the same release when a field, authorization rule, or persistence
boundary changes. Never batch-rewrite `/api/*` to `/api/v1/*` by string replace.
