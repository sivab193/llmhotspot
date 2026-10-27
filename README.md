# LLMHotspot

LLMHotspot turns an Android phone into a private, offline, OpenAI-compatible API server for nearby laptops. The Android app creates a local-only hotspot, loads a GGUF model through llama.cpp, starts an authenticated HTTP server, and presents connection details as a QR code.

## Current scope

This repository contains the Phase 1 vertical slice:

- Android 8+ app built with Kotlin and Jetpack Compose
- local-only hotspot and same-Wi-Fi modes
- foreground service, Wi-Fi/wake locks, battery and thermal stops
- per-session Bearer token authentication
- `/v1/models`, `/v1/chat/completions`, `/v1/completions`, `/v1/embeddings`, `/health`, and `/`
- SSE streaming for chat and text completions
- QR pairing payload and copyable API configuration
- curated model catalog with Wi-Fi-only resumable downloads, SHA-256 verification, and manual GGUF import
- llama.cpp JNI boundary and host-testable server core

The first reproducible native build uses llama.cpp's arm64 KleidiAI CPU path. Vulkan remains a measured follow-up because llama.cpp's Vulkan build also requires a host Vulkan SDK/glslc toolchain; it is not silently claimed as part of this unverified first build.

No account, telemetry, prompt log, or public-internet binding is included.

## Prerequisites

- Android Studio compatible with AGP 8.13.2
- JDK 17
- Android SDK 36 and Build Tools 35.0.0+
- NDK 27.0.12077973 and CMake 3.22.1
- Git submodules initialized

```powershell
git submodule update --init --recursive
./gradlew.bat :app:assembleDebug
./gradlew.bat test
```

Install on an API 26+ phone:

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

See [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) for setup and [docs/DEVICE_TEST.md](docs/DEVICE_TEST.md) for the real-device acceptance checklist.

## Benchmark log

Real-device results belong here; estimates are intentionally not presented as measurements.

| Device / RAM | Model | Backend | Context | Prompt t/s | Generation t/s | Battery / 10 min | Peak °C | Screen-off 30 min |
|---|---|---|---:|---:|---:|---:|---:|---|
| S23 Ultra / 12 GB | Qwen3 4B Q4_K_M | KleidiAI CPU | 8192 | TBD | TBD | TBD | TBD | TBD |

## Status and honest limitation

The source is prepared for an end-to-end device build. This checkout has not been compiled on the current Windows host because it does not have a JDK or Android SDK installed. Native inference also requires the pinned llama.cpp submodule. The app refuses to report the server as ready until a valid GGUF model loads.

## License

Apache-2.0. See [LICENSE](LICENSE).
