# Shared Contracts

These files are the integration boundary between the Spring Boot backend, the new frontend, the classroom-content owner, and the animation renderer.

| File | Purpose |
|---|---|
| `openapi-v1.yaml` | HTTP API contract for `/api/v1/*`. |
| `animation.schema.json` | Renderer-ready structured animation payload returned by the backend. |
| `classroom-script.schema.json` | Reviewed classroom script format stored in `classroom_scripts.script_json`. |
| `examples/animations/*.json` | Valid stack and queue animation payloads. |
| `examples/classroom/*.json` | Valid script-driven stack and queue lessons. |

## Change rules

1. Change a contract before changing an API response or a shared content shape.
2. Preserve existing fields unless all consumers migrate in the same release.
3. Do not put API keys, private textbook text, teacher PPT files, or server paths in this directory.
4. New animation and classroom examples must pass `ContractExampleCompatibilityTest` in `apps/server`.

The server is the final validator: it rejects unsupported animation operations, prevents a student's classroom stage from exposing `expected` answers and `misconceptions`, and only streams published resource files from the configured private resource directory.
