# ADR 0001: Android port

- **Status:** Proposed. Becomes Accepted once decision 4 is confirmed and the distribution question is settled.
- **Date:** 2026-11-22

## Context

Loocal is a SwiftUI iPhone app that runs open models on device through MLX Swift. Several parts depend on Apple frameworks: Foundation Models, ActivityKit / Dynamic Island, EventKit, Keychain, on-device Speech and BGContinuedProcessingTask. MLX does not run on Android, and the pinned MLX checkpoint cannot be used directly there.

## Decisions

1. **Stack:** native Android app in Kotlin with Jetpack Compose. The Swift code is not shared; the UI is rebuilt for Android.
2. **Location and identity:** the Android project lives in this repository under `android/`. Its application ID is `app.loocal`. It does not reuse the iOS working name `IslandAI`.
3. **Inference:** llama.cpp via JNI, using GGUF conversions of the same model families. Vision support (Qwen3-VL) and Gemma 3 must be checked per model before the parity milestone.
4. **MVP scope:** text chat with one local model, chat history, temporary chats, Copy / Edit / Regenerate, personas, and model download. Photos, voice, MCP, connectors and the widget are deferred.
   - **Apple-only features:** none in the MVP. Reproducing them all (Calendar Provider, Keystore, SpeechRecognizer, notifications or foreground services) applies to the complete app, not the MVP. *This interpretation of "reproduce everything" is to be confirmed.*
5. **MVP model:** Qwen3 4B Instruct 2507 in Q4_0 (`bartowski/Qwen_Qwen3-4B-Instruct-2507-GGUF`, revision `ae44f08`, 2.4 GB), pinned in `android/app/src/main/assets/model-4b.json`. Q4_0 was chosen over Q4_K_M because it reads prompts about 30% faster on ARM CPUs (measured on a Xiaomi 14), at a small cost in answer quality. The 8B and 12B are too close to the memory limits of most Android phones to be the default. Unlike the iOS models, this is the official, non-abliterated Qwen model.
6. **Model download:** every file is pinned by repository revision (40-character commit), size and SHA-256, like `App/ModelManifest*.json` on iOS. Partial files resume with HTTP Range requests, and a file is kept only after its hash matches.
7. **Privacy on Android:** app data is excluded from Android backup and device transfer (`allowBackup=false`). Chats and models stay in app-private storage (`filesDir`), which Android encrypts at rest through file-based encryption. Network use is limited to model downloads in the MVP.
8. **Sync:** none. Devices are independent, as on iPhone today.

## Open decisions

- **Distribution: Play Store or sideload (APK / F-Droid).** This could block publication. The models are "abliterated" community models with no moderation layer. Google Play's AI-generated content policy expects apps that generate content to prevent restricted content and to offer in-app reporting. Publishing on Play likely needs at least a report mechanism and possibly a different model choice. Decide before the first release.
- **Download conditions:** Wi-Fi only, as on iOS, or allowed on mobile data with a warning? Background transfer through WorkManager so a download survives leaving the app?
- **Background replies:** foreground service with a notification, or pause when the app leaves the screen? This is the Android counterpart of the iOS GPU/CPU handoff and needs its own ADR.
- **Reference devices** for memory and speed measurements.

## Consequences

- Two codebases to maintain. Shared behaviour (chat rules, context budget, persona handling) is described in `CONTEXT.md` so both stay aligned.
- Model parity is not guaranteed: the GGUF conversions are different artifacts from the MLX ones, with their own provenance and hashes. Each Android model needs its own manifest.
- Memory fit and speed are not measured. They must be checked on real devices before any model larger than 4B is offered.

Later decisions (background replies, distribution, each Apple-only feature) get their own ADR instead of extending this one.
