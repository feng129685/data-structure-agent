# API v1 Freeze Report

- Freeze date: 2026-08-11
- Contract base: `contracts/openapi-v1.yaml`
- HTTP examples: `fixtures/http/spring-v1-admin-and-ai.json`
- Contract verification: `node scripts/verify-api-contract-fixtures.js` passed with `cases=21 paths=20`
- Status: v1 behavior freeze for the currently implemented Spring surface. This is a contract baseline, not a declaration that every UI flow has been deployed to production.

## Scope

The canonical v1 base path is `/api/v1`. This freeze covers the implemented Spring auth and user APIs, reviewed chapters/resources/knowledge, chat and chat history, classroom sessions, DSVP and animation, code runs and analysis, learning progress/events, AI readiness, and the administrator capability, user, audit, review, background-task, and model-configuration paths.

Node `/api/*` remains a compatibility surface, not a lower-version spelling of Spring v1. A client must select one complete workflow and its matching request, response, authorization, persistence, and error semantics. In particular, Node tokens, SQLite conversation/assignment shapes, `/pdfs/*`, and legacy chat frames are not Spring v1 equivalents.

## Major Resources

| Resource | Canonical v1 surface | Frozen boundary |
|---|---|---|
| User identity | `/auth/*`, `/users/me` | Spring JWT or `ds_session`; active database user and current database roles are authoritative. |
| Chapters and course resources | `/chapters`, `/chapters/{chapterId}/resources`, `/resources/{resourceId}`, `/resources/{resourceId}/content` | Publication, audience, and license checks apply to list, metadata, and content access. |
| Reviewed knowledge | `/knowledge/search` | Only published, audience-visible material is searchable; a search result is evidence metadata, not a generated conclusion. |
| Chat and sessions | `/chat`, `/chat/stream`, `/chat/sessions*` | Formal generation is authenticated, evidence- and quota-gated, and owned history is isolated by user. |
| Classroom, animation, code, and learning | `/classroom/*`, `/animations/*`, `/code/*`, `/learning/*` | Domain services own their state transitions and evidence; a client cannot create trusted evidence by posting a look-alike generic event. |
| Administrator resources | `/admin/users*`, `/admin/audit-events`, `/admin/reviews*`, `/admin/background-tasks*`, `/admin/model-config*` | Database-backed `ADMIN` authorization, safe projections, and audited writes are mandatory. |

The review resource type set is frozen to `RESOURCE`, `KNOWLEDGE_CHUNK`, `PRESENTATION_MANIFEST`, `PRESENTATION_PAGE`, and `DSVP_REQUEST_SNAPSHOT`. A DSVP snapshot enters that domain only when it is PPT-backed and its `source_ref` resolves through a presentation page and manifest. `CLASSROOM_SCRIPT` and `ANIMATION_OBSERVATION` are domain records but are not review types and cannot enter the review `VERIFIED` state through `/admin/reviews`.

## Authentication And Authorization

Spring v1 accepts either `Authorization: Bearer <Spring JWT>` or the `ds_session` cookie. In production the cookie is `HttpOnly`, `Secure`, and `SameSite=Strict`. Authorization is evaluated against the active database user and current database roles; a stale role claim is not authority to use an administrator endpoint.

Formal chat, streaming chat, animation generation, code analysis, readiness, and all administrator APIs are authenticated. Administrator endpoints require the current database `ADMIN` role. The optional Node-token compatibility bridge is intentionally limited to the documented learning and animation paths; it never carries Node roles into Spring or grants administrator authority.

Node's `POST /api/auth/request-code` has its own error envelope. A missing or unsupported `purpose` returns `400 CODE_PURPOSE_INVALID`; callers must not substitute that code or envelope for Spring v1 authentication errors.

## Administrator APIs

All administrator routes require the current database `ADMIN` role; a token claim alone is insufficient.

| Area | Routes | Contract |
|---|---|---|
| Capabilities | `GET /admin/capabilities` | Returns the current safe module map; it does not prove deployment health for every downstream integration. |
| Users and roles | `GET /admin/users*`, `PATCH /admin/users/{id}/status`, `PATCH /admin/users/{id}/roles` | Safe user projections, self/last-admin safeguards, and audited mutations. |
| Audit | `GET /admin/audit-events` | Paginated, filterable, non-sensitive summaries with request correlation. |
| Review | `GET/PATCH /admin/reviews*` | Only the five frozen ReviewType values are accepted. PPT-backed DSVP may be verified through its published presentation source chain; classroom scripts, animation observations, and non-PPT DSVP are excluded. |
| Background tasks | `GET/POST /admin/background-tasks*` | The only current submitted, executable, retryable, and cancelable type is `STALE_TASK_RECOVERY`. |
| Model configuration | `GET/PUT /admin/model-config`, `POST /admin/model-config/test` | Credential-free reads, encrypted writes, guarded provider probes, and non-sensitive audit summaries. |

## SSE

Spring streaming uses `POST /api/v1/chat/stream` with `Content-Type: text/event-stream` and named `sources`, `delta`, `done`, and `error` events. The client and proxy must retain no-cache, keep-alive, and disabled response buffering. A disconnect, upstream failure, or stream timeout releases the server-side reservation and concurrency slot.

Node uses legacy `POST /api/chat` streaming on the same path as non-streaming chat with ordinary `data:` frames. It is not wire-compatible with Spring named events and must not be consumed by a Spring v1 SSE parser.

## Errors And Pagination

Spring controlled errors use the v1 `ApiError` shape with a stable `code`, human-readable `message`, request correlation data, and optional safe validation details. Clients must branch on HTTP status and `code`, not on translated message text. Authentication, authorization, validation, ownership, quota, evidence, model-upstream, and conflict failures remain distinct from successful capability responses.

List endpoints use `{items, page, size, total}`. Page numbering is zero-based; `page` cannot be negative and `size` is bounded by the route contract. Administrator list filters are validated rather than silently repaired. A valid readiness request with unavailable evidence or model state is still `200` with `allowFormalGeneration: false`; it is not a successful generation.

## AI Readiness

`GET /api/v1/ai/readiness` accepts optional `operation`, `chapterId`, and `prompt`. `operation` defaults to `CHAT` and accepts the canonical values `CHAT`, `CODE_ANALYSIS`, and `ANIMATION_GENERATION`; an unsupported value returns `400 AI_READINESS_OPERATION_INVALID`.

The response always states `operation` and `evidenceRequired` along with model, evidence, source-count, quota, and blocking-reason fields. `CHAT` requires reviewed, audience-visible evidence. `CODE_ANALYSIS` and `ANIMATION_GENERATION` do not require chat evidence, so they can report `allowFormalGeneration: true` while `evidenceAvailable: false` when the model and caller quota are available.

Readiness is an observation endpoint. It does not reserve quota or guarantee that a later generation request will pass; the generation request performs its own atomic quota and concurrency checks.

## Model Configuration

`GET /api/v1/admin/model-config` returns `{available, reason, configuration?}` without an API key. `available` means generation-eligible, not merely decryptable. A readable saved configuration can therefore include `configuration` while returning `available: false` with `PERSISTED_CONFIGURATION_DISABLED` or `PERSISTED_QUOTA_NOT_CONFIGURED`. Other capability states include `MASTER_KEY_UNAVAILABLE`, `NOT_CONFIGURED`, and `MODEL_CONFIG_UNAVAILABLE`.

`PUT /api/v1/admin/model-config` is the only public write path for an API key. The key is AES-GCM encrypted, never returned or logged, and must be supplied again when the provider or base URL changes. HTTPS-only, public-target validation, pinned address resolution, and redirect refusal are part of the contract.

`POST /api/v1/admin/model-config/test` has no caller-supplied body. It uses the saved configuration to send a pinned HTTPS `POST` to `{baseUrl}/chat/completions` with the saved `model`, one `Connection test` user message, `max_tokens: 1`, `temperature: 0`, and `stream: false`. A 2xx result is successful only when `choices[0].message.content` is non-blank. A 2xx response with a missing, empty, malformed, or oversized completion body returns `{connected:false, code:"CONNECTION_RESPONSE_INVALID"}`. Other controlled outcomes include `CONNECTION_FAILED`, `REDIRECT_REJECTED`, and `UPSTREAM_REJECTED`.

The `MODEL_CONFIG_CONNECTION_TESTED` audit entry carries the caller's `requestId`, a non-negative `connectionTestElapsedMs`, and `credentialRedacted=true` in its safe `afterSummary`. Neither the request, response, logs, nor audit summaries contain the API key.

`lastConnectionTestStatus` and `lastConnectionTestedAt` are diagnostic history from the latest explicit probe. They are not consulted by `ModelGenerationReadiness`, do not make `available` true, and do not promise that the provider is reachable now. Clients must use `/ai/readiness` for the current controlled capability view and still handle the generation request's authoritative result.

## Background Tasks

The administrator background-task API is a narrow maintenance ledger. `POST /api/v1/admin/background-tasks/recover-timeouts` creates the only currently supported executable type, `STALE_TASK_RECOVERY`. It marks expired pending or running tasks as failed and records the affected count in terminal `resultCount`.

Retry is allowed only for a failed `STALE_TASK_RECOVERY` record below its attempt limit. Cancel is allowed only for a pending `STALE_TASK_RECOVERY` record. Existing ledger rows with another type may be readable for diagnosis, but retry and cancel return the documented 409 errors. This is not a general durable queue for AI, compiler, or arbitrary application work.

## Client Non-Inference Rules

- Do not infer Spring authorization from a Node token, an old JWT role claim, a visible navigation item, or a successful response from another route. The target route re-evaluates its own policy.
- Do not infer publication, audience visibility, source completeness, or `VERIFIED` status from the existence of a database row or resource identifier.
- Do not infer that every DSVP snapshot is reviewable. Only a PPT-backed snapshot joined to a presentation page and manifest appears in the review domain.
- Do not infer that classroom scripts or animation observations can be listed or verified through `/admin/reviews`; they currently return an invalid ReviewType boundary rather than a review item.
- Do not infer generation readiness from `lastConnectionTestStatus`, `lastConnectionTestedAt`, model configuration presence, or an administrator capability tile.
- Do not treat `/ai/readiness` as a quota reservation or future-success guarantee; the generation request remains authoritative.
- Do not translate Node `/api/*` paths, payloads, error envelopes, sessions, assignments, or SSE frames into Spring v1 by string replacement.

## Known Limits And Compatibility Boundaries

- `GET /api/knowledge/search` on Node is a debug/inspection route. `NODE_ENV=production` forces it off even when `KNOWLEDGE_DEBUG_API=true`; the response is 404. Spring knowledge search is the production retrieval contract.
- Node `GET /api/assignments` is an authenticated recipient read of active tasks visible to the current user. Node `POST /api/teacher/assignments` is teacher-only create/update constrained by `teacher_id`; it is not the same operation. Deleting a teacher assignment is also creator-only.
- Node and Spring have distinct chat, token, session, assignment, and error contracts. No compatibility promise exists for blind path rewriting from `/api/*` to `/api/v1/*`.
- The current freeze documents server behavior. Frontend adoption and production rollout must be verified separately against the release and proxy runbooks.

## Known Unimplemented Capabilities

- The review service does not expose `CLASSROOM_SCRIPT` or `ANIMATION_OBSERVATION`, and neither can be transitioned to `VERIFIED` through the administrator review API.
- API-backed, classroom-backed, context-free, or otherwise non-PPT DSVP snapshots do not enter the review domain. Only the PPT source-chain case is implemented.
- Spring has no equivalent for the Node teacher overview, recipient assignment list, teacher assignment create/update, or creator-only assignment archive workflow.
- Spring has no public upload-to-publish API and no equivalent HTTP browsing/asset-serving workflow for the private Node presentation plan.
- No current Spring or Node route implements a WebSocket protocol; streaming remains HTTP SSE.
- The background-task API is not a general job queue and does not enqueue model generation, compiler work, content import, or arbitrary maintenance types.
- Administrator UI adoption and per-route production authorization smoke are separate release work; the server contract alone does not establish either.

## Compatibility And Breaking-Change Rules

Non-breaking v1 changes may add an optional response field, a new independently documented endpoint, a new optional request field with a server default, or a new controlled error code when existing success and error meanings remain unchanged. Clients should ignore unrecognized optional response fields and error codes safely.

A change is breaking when it removes or renames a field or endpoint; changes a field type, nullability, requiredness, pagination semantics, authentication/role policy, ownership rule, HTTP status, SSE event name/order, generation gate, or the meaning of an existing error code. It is also breaking to replace a Node route with a Spring route while preserving only the path-like appearance.

Breaking behavior requires a new versioned surface such as `/api/v2`, a migration note, a compatibility window where practical, and updated caller evidence. Every v1 contract change must update the OpenAPI file, the representative HTTP fixture, the relevant integration or boundary test, and the two API matrices in the same change. The freeze acceptance command is:

```powershell
node scripts/verify-api-contract-fixtures.js
```

## Evidence

The freeze is grounded in `AiReadinessController`, `AiReadinessService`, `AiReadinessApiIntegrationTest`, `AiReadinessOperationApiIntegrationTest`, `ModelConfigController`, `ModelConfigService`, `HttpModelConfigConnectionTester`, `HttpModelConfigConnectionTesterTest`, `ReviewService`, `ReviewRepository`, `ReviewQueueApiIntegrationTest`, `BackgroundTaskService`, `BackgroundTaskApiIntegrationTest`, `backend/node/server.js`, `verify-security-hardening.js`, and `verify-node-production-debug-knowledge.js`.
