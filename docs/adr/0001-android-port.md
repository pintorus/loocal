# ADR 0001: Android port

- **Status:** Accepted
- **Date:** 2026-11-22

## Context

Loocal is a SwiftUI iPhone app that runs open models on device through MLX Swift. Several parts depend on Apple frameworks: Foundation Models, ActivityKit / Dynamic Island, EventKit, Keychain, on-device Speech and BGContinuedProcessingTask. MLX does not run on Android, and the pinned MLX checkpoint cannot be used directly there.

## Decision

1. **Stack:** native Android app in Kotlin with Jetpack Compose. The Swift code is not shared; the UI is rebuilt for Android.
2. **Inference:** llama.cpp via JNI, using GGUF conversions of the same model families. Vision support (Qwen3-VL) and Gemma 3 must be checked per model before the parity milestone.
3. **MVP scope:** text chat with one local model, chat history, model download with hash verification, and personas. Photos, voice, MCP, connectors and the widget are deferred.
4. **Apple-only features:** none in the MVP. The "reproduce everything" choice applies to the complete app, not the MVP. Android equivalents (Calendar Provider, Keystore, SpeechRecognizer, notifications and foreground services) are chosen when each feature is scheduled.
5. **Sync:** none. Devices are independent, as on iPhone today.

## Consequences

- Two codebases to maintain. Shared behaviour (chat rules, context budget, persona handling) is described in `CONTEXT.md` so both stay aligned.
- Model parity is not guaranteed: the GGUF conversions are different artifacts from the MLX ones, with their own provenance and hashes. Each Android model needs its own manifest and verification.
- Background generation has no direct equivalent to the iOS GPU/CPU handoff. The Android design for background work is open and needs its own ADR.
- Memory fit must be measured on real Android devices; the 8B-class model is near practical limits on mid-range phones.

## Open questions

- Which Android devices are the reference for memory and speed?
- Background reply behaviour on Android (foreground service vs. stopping when backgrounded).
- Which Android equivalent covers each Apple-only feature when it is scheduled.
