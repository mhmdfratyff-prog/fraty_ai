# FRATY AI integration points

The backend implements OpenAI-compatible chat, Android source ZIP generation, and an asynchronous debug-APK build workflow through GitHub Actions. The OpenAI Images-compatible adapter is available only when separately configured. Video, realtime voice, Google sign-in, and screen sharing still need provider/service setup. All provider secrets belong on the backend, never in the APK.

## Implemented: OpenAI-compatible chat

Set these variables in the server environment (do not commit secret values):

- `APP_ACCESS_TOKEN`: long random application access token. The server rejects requests if it is absent.
- `GEMINI_API_KEY` or `OPENAI_API_KEY`: provider key, server-only.
- `OPENAI_MODEL`: a model available to the selected provider. The free-tier example uses `gemini-3.5-flash-lite`.
- Optional `OPENAI_BASE_URL`: defaults to `https://api.openai.com/v1`; Gemini OpenAI-compatible base URL is `https://generativelanguage.googleapis.com/v1beta/openai/`.
- Optional `PORT` and `MAX_REQUESTS_PER_MINUTE`.

The Android app calls `POST /api/v1/chat`, sends recent messages, and authenticates with `X-App-Token`. The server proxies to `/chat/completions`, enforces body/message limits, and returns only the assistant text. Confirm that the provider's current API supports the OpenAI-compatible Chat Completions shape before use.

## Implemented: Android source project ZIP

`POST /api/v1/projects` accepts `{ "prompt": "..." }`. The configured chat model converts a description or pasted code into a Kotlin/Jetpack Compose starter app, places the generated activity in a fixed Android Studio template, and returns a signed temporary ZIP URL. The endpoint does not execute generated code, run Gradle, or produce an APK. Generated files should be reviewed and built in Android Studio; the download link uses the same temporary media storage and expiry as images.

## Implemented: asynchronous debug APK builds

`POST /api/v1/builds` generates the same fixed-template Compose starter and dispatches `.github/workflows/build-generated-android.yml` with an opaque UUID only. The Android client polls `GET /api/v1/builds/{jobId}`; the runner downloads source from `GET /api/v1/build-jobs/{jobId}/source` and posts the APK to `POST /api/v1/build-jobs/{jobId}/result`. Worker endpoints require `X-Build-Worker-Token`; the client endpoints require `X-App-Token`. No generated source, signed download URL, or APK is sent as a public workflow input/artifact.

Configure `BUILD_GITHUB_TOKEN` on the backend as a fine-grained token for only `mhmdfratyff-prog/fraty_ai`, with repository permission **Actions: read/write**. Generate a random `BUILD_WORKER_SECRET` and store the exact same value in the Render service and repository Actions secrets under that name. Optional variables: `BUILD_GITHUB_REPOSITORY`, `BUILD_GITHUB_WORKFLOW`, and `BUILD_GITHUB_REF`. The standard GitHub-hosted Ubuntu runner is free for a public repository. The returned APK is debug-signed with a temporary CI key, not a Play Store signing key. Render Free storage is ephemeral. The template generates one Compose activity and fixed dependencies; review output and expect complex apps to need additional development.

## Implemented: OpenAI Images-compatible adapter

`POST /api/v1/images` accepts `{ "prompt": "..." }` and calls `/images/generations`. Configure `IMAGE_API_KEY` and `IMAGE_MODEL`; alternatively use `OPENAI_API_KEY` and `OPENAI_IMAGE_MODEL`. Optional variables are `IMAGE_API_BASE_URL`, `IMAGE_SIZE`, and `IMAGE_QUALITY`. The service needs a public HTTPS origin; Render supplies `RENDER_EXTERNAL_URL` automatically, while other hosts should set `PUBLIC_BASE_URL`. The server stores the generated image under `MEDIA_DIR`, returns a signed expiring URL, and serves it from `GET /api/v1/media/{id}.{ext}`. The link lifetime is `MEDIA_TTL_SECONDS` (default 24 hours). The provider must return `b64_json` or an HTTPS image URL. Check provider/model availability and billing before production use.

## Provider adapter contracts still required

Implement and test each adapter on the backend before enabling the corresponding UI action:

- **Google login:** Firebase project, Android app registration using package `com.fraty.ai`, SHA fingerprints, `google-services.json`, Credential Manager/Google Identity, Firebase Auth token verification on every private backend route, account deletion and privacy policy.
- **Realtime voice:** select a provider/API, issue short-lived client credentials from the backend, stream bidirectional audio over the provider's supported transport, implement interruption/cancellation, lifecycle and disclosure/consent. Do not ship provider credentials in the APK.
- **Screen sharing:** implement Android `MediaProjection` consent flow, foreground service with persistent notification and stop action, frame rate/size controls, explicit user stop/pause, secure transport, and server-side vision session. Never capture in background without consent.
- **Image generation:** the adapter above is implemented; configure it, test provider/model access, and add production-grade storage, per-user authorization, policy controls, and spend limits before launch.
- **Video generation:** select a provider; define job creation/status/cancel endpoints, progress states, polling/webhooks, storage and expiration. The app must not present a video before a real job succeeds.
- **File/project workspace:** starter-project ZIP and isolated GitHub Actions debug builds are implemented. Uploads, malware scanning, durable artifact storage, Play signing, multi-file code generation, and production-grade validation remain unimplemented.

`/api/v1/projects` generates a source ZIP when chat is configured. `/api/v1/builds` requires both GitHub secrets and returns HTTP 503 until they are set. `/api/v1/images` returns HTTP 503 until its provider key and model are set; `/api/v1/videos` remains unimplemented. `/api/v1/integrations` reports configured capabilities. Test the provider and deployment before claiming a capability is active.

## Deployment guidance

Run behind a TLS reverse proxy or managed HTTPS service. The Render free-tier example is for personal testing only: it sleeps on idle and its filesystem is ephemeral. Gemini's free tier has usage limits and Google may use inputs to improve products. A shared access token embedded in the APK can be extracted; add robust user authentication before public release.
