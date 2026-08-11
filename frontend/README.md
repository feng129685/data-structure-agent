# Frontend

`index.html` is the formal Structify learning workspace served for `/` and
`/index.html`. It includes question answering, mainline learning, PPT study,
knowledge retrieval, classroom flow, and animation entry points.

`prototype.html` is preserved during the migration so uncommitted local work is
not lost. The Node compatibility server continues to expose `/prototype.html`
until the formal workspace has been verified against every retained flow.

The root `index.html` and `prototype.html` files are small file-open
compatibility redirects only. They are not deployed as the application UI.

PPT binaries and manifests do not belong in this directory or in Docker image
layers. Production mounts the reviewed `presentation-materials` bundle read-only
and Node serves selected images through the signed `/presentation/*` route.
