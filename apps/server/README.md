# Data Structure Agent Spring Backend

`apps/server` is the Spring Boot backend for the data-structure learning agent. It is a modular monolith designed for the team's first vertical slice: approved stack-and-queue content, course-grounded chat, a script-driven classroom, structured animations, and sandboxed code experiments.

The existing Node.js prototype remains a reference implementation during migration. The Spring API uses `/api/v1/*` on port `8792`; it does not replace the legacy `/api/*` endpoints until the new frontend has completed integration.

## Implemented modules

- Account registration, login, password reset, email verification codes, JWT cookie/Bearer authentication, and `STUDENT` / `TEACHER` / `ADMIN` roles.
- Published chapter and resource metadata, plus safe resource streaming without exposing server filesystem paths.
- Reviewed textbook knowledge loading, retrieval, source-aware chat, SSE streaming, and persisted chat history for signed-in users.
- Reviewed classroom scripts, a deterministic classroom state machine, answer evaluation, misconception feedback, and classroom session persistence.
- Whitelist-validated animation definitions for stack, queue, list, tree, heap, hash table, and array demonstrations.
- C/Python execution through a remote Piston sandbox only, with bounded input/output, timeout, rate limiting, and concurrency limiting. User code is never run with `ProcessBuilder`, a shell, or a temporary executable on the Spring host.
- Shared learning records and per-chapter activity progress across chat, classroom, animation, resource, and code workflows.

## Run locally

Requirements: Java 21. Maven is provided through the wrapper.

```powershell
cd apps/server
.\mvnw.cmd clean test
.\mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=dev
```

The explicit `dev` profile starts with an in-memory H2 database at `http://127.0.0.1:8792`, enables local verification-code echoing, and may import local knowledge files. The base configuration requires `JWT_SECRET` and keeps those development conveniences disabled, so an accidentally unprofiled deployment fails closed. Health endpoint:

```text
GET http://127.0.0.1:8792/actuator/health
```

Without `MODEL_API_KEY`, authentication, chapters, resources, classroom sessions, and progress still start. Chat, animation generation, and code analysis return `MODEL_NOT_CONFIGURED` instead of silently inventing an answer.

## Environment variables

Use [`deployment/.env.spring.example`](../../deployment/.env.spring.example) as the starting point. Keep the real file outside source control.

| Variable | Purpose |
|---|---|
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | MySQL JDBC connection. Local development defaults to H2. |
| `JWT_SECRET` | Random secret with at least 32 characters. |
| `CORS_ALLOWED_ORIGINS` | Comma-separated exact frontend origins allowed to call the API with credentials. |
| `BOOTSTRAP_ADMIN_EMAIL` | Email that receives `ADMIN`, `TEACHER`, and `STUDENT` roles when it registers. |
| `TEACHER_EMAILS` | Comma-separated teacher emails. |
| `MODEL_PROVIDER`, `MODEL_API_KEY`, `MODEL_BASE_URL`, `MODEL_NAME` | OpenAI-compatible model configuration. DeepSeek/OpenAI-compatible providers use Bearer auth; `azure` / `azure-openai` use the `api-key` header. |
| `MODEL_MAX_RESPONSE_BYTES` | Maximum buffered or streamed model response size; defaults to 1 MiB. |
| `KNOWLEDGE_DIR` | Private textbook directory containing `lessons/*.md`. |
| `KNOWLEDGE_AUTO_PUBLISH_LOCAL` | Keep `false` in production so only database-reviewed chunks enter retrieval. |
| `RESOURCE_DIR` | Private root for published PDF, PPT, code, and exercise files. |
| `SMTP_HOST`, `SMTP_PORT`, `SMTP_USER`, `SMTP_PASS`, `SMTP_FROM` | Verification-code mail delivery configuration. |
| `SMTP_SSL`, `SMTP_STARTTLS`, `SMTP_STARTTLS_REQUIRED` | SMTP transport security. Use implicit TLS or required STARTTLS, not both. |
| `SMTP_*_TIMEOUT_MS`, `SMTP_SSL_CHECK_SERVER_IDENTITY` | SMTP connection/read/write limits and TLS hostname verification; keep hostname verification enabled. |
| `PISTON_BASE_URL` | Piston service root. Production requires an explicit value. |
| `EXECUTE_*` | Compiler timeout, input/output length, and concurrency limits. |

There is no public default administrator password. Configure `BOOTSTRAP_ADMIN_EMAIL` before registration, then register that email through the normal verification-code flow. Changing the environment variable does not elevate an already-existing account.

For implicit TLS, normally on port 465, set `SMTP_SSL=true`, `SMTP_STARTTLS=false`, and `SMTP_STARTTLS_REQUIRED=false`. For STARTTLS, normally on port 587, set `SMTP_SSL=false`, `SMTP_STARTTLS=true`, and `SMTP_STARTTLS_REQUIRED=true`. The application defaults `SMTP_STARTTLS_REQUIRED` to the value of `SMTP_STARTTLS`, so an enabled upgrade cannot silently fall back to plaintext.

## Reviewed course content

Private OCR textbook files, teacher PPT files, and other restricted course material must remain outside the public Git repository. The recommended private directory is:

```text
course-content-private/
  chapters/
    03-stack-queue/
      slides/
      code/
      exercises/
      classroom/
```

Resource metadata is stored in MySQL. Both the resource and its parent chapter must be `PUBLISHED` before delivery. Guests see `PUBLIC`, signed-in students also see `CLASSROOM_ONLY`, and `TEACHER` / `ADMIN` accounts also see `TEAM_ONLY`. The same rule applies to list, detail, and content endpoints. `resources.file_path` must be a relative path below `RESOURCE_DIR`; the API exposes only `/api/v1/resources/{id}/content`, never the underlying path. Files outside that root, traversal paths, absolute paths, and symlink escapes are rejected.

Classroom scripts are also versioned MySQL records. New content should follow these contracts before review:

- [`contracts/classroom-script.schema.json`](../../contracts/classroom-script.schema.json)
- [`contracts/animation.schema.json`](../../contracts/animation.schema.json)
- [`contracts/examples`](../../contracts/examples)

The detailed handoff for the classroom and content owners is in [`docs/content-import-guide.md`](../../docs/content-import-guide.md).

## Trusted learning evidence

Learning progress distinguishes self-reported activity from evidence produced by a completed backend workflow:

- A guest code run is not persisted and returns a null `runId`. A signed-in run is persisted and returns an owned `runId`.
- `POST /api/v1/code/analyze` accepts ad-hoc code for guests, but a signed-in client should submit `runId`. The backend reloads the owned source and result, ignores client-supplied run fields, and creates `CODE_REVIEW` evidence only after successful analysis.
- Every classroom session stores the exact reviewed script and chapter snapshot used when it starts. Answer events retain the evaluation status, matched misconception, and feedback, and the backend creates `CLASSROOM_ANSWER` evidence.
- Animation observations are append-only. `animation_records.observation` retains the latest value for compatibility, while `animation_observations` preserves the history and the backend creates `ANIMATION_OBSERVATION` evidence.
- The generic learning-event endpoint accepts only `RESOURCE_VIEW`, `RESOURCE_DOWNLOAD`, `REVIEW_COMPLETED`, and `WEAKNESS_RECORDED`. Clients cannot manufacture the three server-managed evidence types above.

Unknown or foreign-owned run, animation, classroom, and chat identifiers are returned as `404` so ownership details are not disclosed.

## API contract

The full HTTP contract is [`contracts/openapi-v1.yaml`](../../contracts/openapi-v1.yaml). Important integration endpoints include:

- `POST /api/v1/chat` and `POST /api/v1/chat/stream`: guest-compatible course chat; signed-in sessions persist.
- `GET /api/v1/chat/sessions`: signed-in users receive their 50 most recently active sessions.
- `GET|DELETE /api/v1/chat/sessions/{id}`: signed-in users can read or delete only their own sessions; reads include the latest 200 messages in chronological order.
- `GET /api/v1/resources/{id}/content`: published resource file streaming.
- `POST /api/v1/classroom/sessions/{id}/actions`: answer, pause, resume, continue, or finish a script-driven class.
- `POST /api/v1/animations/generate` and `POST /api/v1/animations/{id}/observations`: validated animations and owned observations.
- `POST /api/v1/learning/events`: signed-in, self-reported learning activity only; trusted workflow evidence is server-managed.
- `POST /api/v1/code/runs` and `POST /api/v1/code/analyze`: remote sandbox execution plus ad-hoc or owned-run analysis.

## Verify before merging

```powershell
cd apps/server
.\mvnw.cmd clean test
.\mvnw.cmd -DskipTests package
```

The root project also contains legacy prototype regression checks:

```powershell
npm test
```

The GitHub Actions workflow runs both checks for pushes and pull requests. Docker Compose and Caddy examples are under [`deployment`](../../deployment).
