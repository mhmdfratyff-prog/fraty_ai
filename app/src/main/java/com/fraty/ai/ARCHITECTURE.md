# Package boundaries

The current UI is intentionally compact; these package boundaries mark integration work that should remain separate as the app grows:

- `auth/`: Credential Manager and Firebase Auth, token/session state, logout.
- `chat/`: conversation UI, view-model/state, streaming client integration.
- `core/`: shared network client and common configuration (`FratyApi` currently contains the chat endpoint).
- `history/`: local conversation persistence (`LocalStore` currently stores a single local thread).
- `settings/`: backend and provider settings UI/state.
- `voice/`: realtime audio session and lifecycle; not implemented.
- `screen_sharing/`: MediaProjection consent and foreground service; not implemented.
- `coder/`, `app_builder/`: prompt workflows and secure project-build worker contract; not implemented beyond UI entry points.
- `image/`, `video/`: provider-backed generation jobs; not implemented beyond honest setup gates.

Move screen state into ViewModels and add Room/DataStore, dependency injection, tests, and encrypted/short-lived auth credentials before production deployment. Do not enable a UI action until the associated service and failure handling are implemented.
