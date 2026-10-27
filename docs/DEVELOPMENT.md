# Development setup

1. Install Android Studio and its bundled JDK 17.
2. In SDK Manager install Android SDK 36, Build Tools 35.0.0 or newer, NDK `27.0.12077973`, CMake `3.22.1`, and platform-tools.
3. Set `ANDROID_HOME` (or create `local.properties` with `sdk.dir=...`).
4. Initialize the pinned dependency: `git submodule update --init --recursive`.
5. Run `scripts/check-toolchain.ps1`, then `./gradlew.bat test :app:assembleDebug`.

The server module uses an injectable `InferenceEngine`. Unit tests use a deterministic fake. Production wires `LlamaEngine`, which calls the native module through JNI.

## Architecture

- `app`: Compose UI, hotspot controller, foreground service, Android power/thermal/network integration, model downloads.
- `server`: Ktor API surface, Bearer authentication, request/response models, SSE, static landing page.
- `native`: llama.cpp build and JNI bridge.
- `scripts`: repeatable development and verification helpers.
- `docs`: build and device acceptance guides.

## Security boundaries

The HTTP server binds to a selected local interface address, never an automatically discovered public address. API routes require a freshly generated session key. The root landing page and health endpoint contain no prompt or key data. HTTP is acceptable only because hotspot/LAN mode is local; TLS is required before adding any remote-access mode.
