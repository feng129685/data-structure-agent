# Node SQLite and Spring MySQL data-model difference matrix

Node and Spring have different ownership, identity, and persistence models.
The migration is a reviewed staging import, not a live dual-write operation.
Spring Flyway migrations are the authoritative schema for the production
database.

## Storage map

| Node SQLite table/file | Spring MySQL target | Mapping and loss boundary |
|---|---|---|
| `users.id`, `email`, `password_hash`, `created_at` | `users` plus `user_roles` | Integer IDs are inserted as stable BIGINT IDs. Node scrypt hashes are not accepted by Spring BCrypt; the importer writes an active reset sentinel and grants `STUDENT` only. Privileged roles require separate review. |
| `conversations` | `chat_sessions` + `chat_messages` | Each legacy row becomes `legacy-conversation-<id>`. `scenario` is retained in the title, `chapter_id` is null, and one valid `updated_at` is reused for session/message chronology because the source has no per-message timestamps. Missing timestamps or invalid messages are blocked, not fabricated. |
| `chat_threads` | `chat_sessions` + `chat_messages` | Thread ID/title/messages and valid created/updated timestamps are preserved. `type`, `scenario`, and `classroom_state` have no lossless Spring columns and are reported as unmapped. |
| `learning_snapshots` | No direct row target; rebuild `learning_records` from trusted evidence | Aggregate progress, weak memory, report, stats, and teacher tasks are not losslessly event-sourced. They remain an audit-only unmapped report; do not convert JSON guesses into completed evidence. |
| `teacher_assignments` | No Spring assignment aggregate in the current schema | Rows are audit-only. Do not import them as classroom scripts or learning events. |
| `private/presentation-materials/` JSON and `rendered/` media | `presentation_manifests`, `presentation_pages`, `resources` | Node's offline slide index is filesystem data. Spring V11 stores reviewed manifest/page metadata and optional resource references in MySQL; binary media still belongs under a private `RESOURCE_DIR` or Node `PRESENTATION_DIR`. |
| Node `private/knowledge` Markdown | `knowledge_chunks` + `resources` + `content_reviews` | File presence does not publish content. Production keeps local auto-publish off; reviewed chunks must carry chapter/resource/license/status metadata. |
| Node `private/pdfs` and uploads | Spring `resources.file_path` under `RESOURCE_DIR` | Do not copy a directory into public web space. Insert relative, normalized paths only after review; Spring rejects absolute paths, traversal, and symlink escapes. |

## Spring schema additions after V1

Flyway V1 creates users/roles, verification and refresh tokens, chapters,
resources, knowledge, reviews, chat sessions/messages, classroom scripts and
events, animation records, code runs, and learning records. Later migrations:

- V5 adds append-only `animation_observations`.
- V6 preserves classroom answer status, misconception, and feedback.
- V7 freezes the classroom chapter and script snapshot boundary.
- V8 adds knowledge `license_scope` and backfills it from resources.
- V9/V10 backfill classroom script snapshots and legacy animation observations.
- V11 persists reviewed PPT manifests/pages, DSVP request snapshots, classroom
  presentation/animation references, and observation provenance/review fields.

The V11 foreign keys use `ON DELETE CASCADE` only where child evidence is
owned by its parent record; presentation/resource and classroom evidence links
use `SET NULL` where historical evidence should survive removal of an optional
asset. Operators must run the migration integration tests and a restore-tested
MySQL backup before production promotion.

The DSVP evidence repair uses the existing V11 `animation_record_id` foreign
key and existing chapter columns, so it does not rewrite V11 and does not add a
V12 migration. A formal simulation now writes the animation row, linked DSVP
snapshot, and chapter-scoped learning event in one short local transaction.
Requests without a verified chapter remain non-persisted previews. The same
normalized request is idempotent per user, and persisted trace identifiers are
user-scoped to avoid cross-user primary-key collisions.

## Import safeguards

`scripts/sqlite-to-mysql-import.js` opens SQLite read-only and reports:

- source SHA-256, row counts, missing source tables, blocked rows, and
  unmapped fields;
- no SQLite file, private content, or legacy password hash in the SQL bundle;
- deterministic `START TRANSACTION`/`COMMIT` output only for development, test,
  or staging; production targets are rejected;
- `user_roles` rows for imported students and a reset sentinel that keeps the
  normal Spring reset path reachable.

Run the default audit first, review every blocked/unmapped item, import to a
fresh staging database, verify role and password-reset recovery, then exercise
chat ownership and message ordering. A successful importer report is not a
production approval by itself.

## Data-model invariants for new code

1. User ownership is checked in SQL and service code; a foreign ID returns
   `404`, not another user's record.
2. A classroom session stores the reviewed script and chapter snapshot used at
   start time; later content edits do not rewrite its evidence.
3. Trusted `CLASSROOM_ANSWER`, `ANIMATION_OBSERVATION`, `ANIMATION_SIMULATION`, and `CODE_REVIEW`
   records are created by their backend workflow, never by a generic client
   event payload.
4. Resource and knowledge visibility requires publication plus license scope;
   filesystem paths never cross the API boundary.
5. Legacy aggregate JSON remains an audit input until a reviewed event mapping
   exists. It must not be presented as a completed learning record.
6. A DSVP simulation contributes to chapter progress only when the chapter is
   resolved from an owned classroom snapshot, an authorized published PPT
   page, or a published explicit chapter. Client/source conflicts fail before
   any of the three evidence rows are written.
