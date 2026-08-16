# Structify Release Source

This release repository contains the reviewed application source, contracts, tests, deployment templates, and operator documentation for Structify.

The release source intentionally excludes private courseware and local state. The following paths stay outside Git and are mounted or provisioned separately in production:

- `private/` (including `source-ppt/`, `pdfs/`, `knowledge/`,
  `presentation-materials/`, and `course-content/`)
- `data.db*`, `.jwt-secret`, `.env` files, uploads, and backups
- `node_modules/`, `backend/spring/target/`, `private/state/node/data.db*`,
  `private/artifacts/`, `output/`, and `.playwright-cli/`
- local planning and audit logs (`task_plan.md`, `findings.md`, and `progress.md`)
- local production-input answers and generated design/planning notes
  (`docs/production-input-*.md` and `docs/superpowers/`)

The release repository is initialized with fresh history so it does not inherit private-material objects from the legacy public repository. Production secrets, private presentation resources, database state, and Caddy data must be supplied through the server secret and backup procedures described in `PRODUCTION_DEPLOYMENT_GUIDE.md`.
