# FRATY AI integration points

The backend implements OpenAI-compatible chat, AI-generated Android source ZIPs, and an OpenAI Images-compatible generation adapter when configured. Video, realtime voice, Google sign-in, screen sharing, and automatic APK builds still need provider/service setup. All provider secrets belong on the backend, never in the APK.

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

## Implemented: OpenAI Images-compatible adapter

`POST /api/v1/images` accepts `{ "prompt": "..." }` and calls `/images/generations`. Configure `IMAGE_API_KEY` and `IMAGE_MODEL`; alternatively use `OPENAI_API_KEY` and `OPENAI_IMAGE_MODEL`. Optional variables are `IMAGE_API_BASE_URL`, `IMAGE_SIZE`, and `IMAGE_QUALITY`. The service needs a public HTTPS origin; Render supplies `RENDER_EXTERNAL_URL` automatically, while other hosts should set `PUBLIC_BASE_URL`. The server stores the generated image under `MEDIA_DIR`, returns a signed expiring URL, and serves it from `GET /api/v1/media/{id}.{ext}`. The link lifetime is `MEDIA_TTL_SECONDS` (default 24 hours). The provider must return `b64_json` or an HTTPS image URL. Check provider/model availability and billing before production use.

## Provider adapter contracts still required

Implement and test each adapter on the backend before enabling the corresponding UI action:

- **Google login:** Firebase project, Android app registration using package `com.fraty.ai`, SHA fingerprints, `google-services.json`, Credential Manager/Google Identity, Firebase Auth token verification on every private backend route, account deletion and privacy policy.
- **Realtime voice:** select a provider/API, issue short-lived client credentials from the backend, stream bidirectional audio over the provider's supported transport, implement interruption/cancellation, lifecycle and disclosure/consent. Do not ship provider credentials in the APK.
- **Screen sharing:** implement Android `MediaProjection` consent flow, foreground service with persistent notification and stop action, frame rate/size controls, explicit user stop/pause, secure transport, and server-side vision session. Never capture in background without consent.
- **Image generation:** the adapter above is implemented; configure it, test provider/model access, and add production-grade storage, per-user authorization, policy controls, and spend limits before launch.
- **Video generation:** select a provider; define job creation/status/cancel endpoints, progress states, polling/webhooks, storage and expiration. The app must not present a video before a real job succeeds.
- **File/project workspace:** the source-project ZIP endpoint is implemented. Uploads, malware scanning, isolated APK builds, artifact storage, and production-grade validation remain unimplemented; do not describe the source ZIP as a built APK.

`/api/v1/projects` generates a source ZIP when chat is configured. `/api/v1/images` has a real configurable adapter but returns HTTP 503 until its key and model are set; `/api/v1/videos` still returns HTTP 503 until a provider is selected and implemented. `/api/v1/integrations` reports configured capabilities. Do not claim a capability is active solely because its code path exists; test the provider and deployment first.

## Deployment guidance

Run behind a TLS reverse proxy or managed HTTPS service. The Render free-tier example is for personal testing only: it sleeps on idle and its filesystem is ephemeral. Gemini's free tier has usage limits and Google may use inputs to improve products. A shared access token embedded in the APK can be extracted; add robust user authentication before public release.
