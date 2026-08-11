# Private Resource Bundle

The application source is publishable without courseware. The reviewed course
bundle is supplied outside Git under `private/` and is copied to the production
host separately. Do not put these files into public Git history or Docker image
layers.

## Runtime mapping

| Source package area | Main workspace location | Production use |
| --- | --- | --- |
| `teach_ppt/` | `private/source-ppt/` | Original PPTX archive for controlled rebuilds. It is not served to browsers. |
| `presentation-materials/` | `private/presentation-materials/` | Node reads manifests and serves signed rendered page images. |
| `lesson-materials/` | `private/knowledge/` | Node and Spring read reviewed lesson text and curriculum metadata. |
| `pdfs/` | `private/pdfs/` | Node seeds the writable PDF volume from a read-only host mount. |
| `normalized-materials/` | Not deployed separately | Generated intermediate material; its runtime content is already covered by lesson text and PDFs. |
| `output/`, browser profiles, local databases | `private/artifacts/`, `private/state/` | Local evidence/state only. Do not seed production from them. |

Spring's `RESOURCE_DIR` is separate from this bundle. Create the configured
`course-content` directory on the production host, then add only reviewed
resource files and matching published database metadata. An empty directory is
valid when no Spring-managed resource has been approved; do not invent content
to satisfy the mount.

## Required host directories

The production private root contains at least:

```text
private/
  knowledge/
  presentation-materials/
  pdfs/
  source-ppt/
  course-content/
```

`knowledge/`, `presentation-materials/`, and `course-content/` are mounted
read-only into the application containers. `pdfs/` is mounted read-only at
Node's default-PDF source; Node copies the reviewed defaults into its writable
PDF volume without exposing the host path.

## Verification

Run this before packaging or uploading private resources:

```powershell
$env:STRUCTIFY_REQUIRE_PRIVATE_RESOURCES = 'true'
node scripts/verify-private-resource-bundle.js
node scripts/check-ppt-offline.js
```

The first command validates the mounted curriculum, PDF manifest and signatures,
PPTX signatures, presentation manifests, and every rendered page reference. It
prints aggregate counts only. The second command verifies the offline slide
bundle. On a clean source checkout without `private/`, the verifier reports that
external resources are required and exits successfully; production validation
must set `STRUCTIFY_REQUIRE_PRIVATE_RESOURCES=true`.

`node scripts/verify-presentation-live-assets.js` follows the same boundary. It
performs the signed page-image HTTP check when the presentation bundle is
mounted. A clean release source reports
`PRESENTATION_LIVE_ASSETS_EXTERNAL_RESOURCES_REQUIRED`; strict validation or a
partially mounted bundle fails instead of treating missing slides as valid.
