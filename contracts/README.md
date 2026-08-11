# Shared Contracts

These files are the integration boundary between the Spring Boot backend, the new frontend, the classroom-content owner, and the animation renderer.

| File | Purpose |
|---|---|
| `openapi-v1.yaml` | HTTP API contract for `/api/v1/*`. |
| `animation.schema.json` | Renderer-ready structured animation payload returned by the backend. |
| `dsvp.schema.json` | Versioned request envelope used by classroom and PPT animation adapters. |
| `classroom-script.schema.json` | Reviewed classroom script format stored in `classroom_scripts.script_json`. |
| `examples/animations/*.json` | Valid stack and queue animation payloads. |
| `examples/classroom/*.json` | Valid script-driven stack and queue lessons. |

## Change rules

1. Change a contract before changing an API response or a shared content shape.
2. Preserve existing fields unless all consumers migrate in the same release.
3. Do not put API keys, private textbook text, teacher PPT files, or server paths in this directory.
4. New animation and classroom examples must pass `ContractExampleCompatibilityTest` in `backend/spring`.

The server is the final validator: it rejects unsupported animation operations, prevents a student's classroom stage from exposing `expected` answers and `misconceptions`, and only streams published resource files from the configured private resource directory. DSVP `1.0` uses an explicit `structure`/`operation` request and is adapted to the legacy renderer payload; classroom scripts may reference a stable animation ID or an inline, validated request.

## DSVP evidence context

`POST /api/v1/animations/simulate` accepts an optional `context` object. The canonical fields are `chapter_id`, `lesson_id`, `presentation_id`, `presentation_page_id`, `classroom_session_id`, `source_type`, and `source_ref`. Existing `source_ref` remains supported, and the documented camelCase/top-level ID aliases are normalized into the canonical object.

The server resolves a chapter in this order: an owned classroom session snapshot, a published and authorized presentation page, a published explicit chapter, then a reviewed animation binding when one exists. A client chapter that conflicts with an authoritative session or page returns `409 DSVP_CHAPTER_CONFLICT`; inaccessible, unpublished, unreviewed, or foreign sources return the same non-disclosing `403 DSVP_SOURCE_FORBIDDEN` response.

A request with no resolvable chapter remains a renderer preview: `evidencePersisted=false`, `matchSource=NONE`, and no animation record, DSVP snapshot, or learning event is written. Formal evidence returns `evidencePersisted=true`, matching `recordId`/`animationRecordId`, `resolvedChapterId`, and `matchSource`. The animation row, snapshot foreign key, and learning event commit in one local transaction. Retrying the same normalized request for the same user returns the existing record; trace identifiers are user-scoped so two users cannot collide.

The deployment boundary is documented in [`docs/api-node-spring-differences.md`](../docs/api-node-spring-differences.md). It records which legacy `/api/*` routes remain on Node and which reviewed `/api/v1/*` routes are served by Spring, including the SSE event contract and the fact that no WebSocket endpoint currently exists. The corresponding persistence boundary and Flyway/import rules are in [`docs/data-model-node-spring-differences.md`](../docs/data-model-node-spring-differences.md).
