# Android parity with iOS

Goal: the Android app is a faithful clone of the iOS app — same features, same look, same behaviour. Each line names the iOS source and the Android approach. Status: ✅ done · 🚧 in progress · ⬜ not started · ➖ no Android equivalent (stated why).

## Chat

| Feature | iOS | Android approach | Status |
| --- | --- | --- | --- |
| Chat, history, temporary chats, pin, delete, search | `RootView.swift` | Compose port | ✅ |
| Copy / Edit / Regenerate / Select text / Share | `RootView.swift` | Compose port | ✅ |
| Read aloud | `Speech.swift` | Android `TextToSpeech` | ✅ |
| Personas (3, icon, hold-send capsule) | `Persona.swift`, `SendControl.swift` | Compose port | ✅ |
| Persona bot look (shape, tint) for Errands | `Pebble.swift` | Canvas port, Chat / Errands switch in the persona editor | ✅ |
| Performance line under a reply | `AppState.performance` | tokens/s and free memory | ✅ |
| Page swipe between tabs | `RootView.pageSwipe` | horizontal drag on the page | ✅ |

## Models

| Feature | iOS | Android approach | Status |
| --- | --- | --- | --- |
| Three models, carousel selector | `ModelSelector.swift`, `ModelSpec.swift` | Compose port; GGUF of the same three Huihui / mlabonne abliterated models | ✅ |
| Photo understanding (Qwen3-VL) | MLX VLM | llama.cpp `mtmd` with the model's `mmproj`; tested on a Xiaomi 14 (4B, 25 s with a 512 px photo) | ✅ |
| Pinned, verified, resumable downloads | `ModelDownload.swift` | 6 parallel ranges over 128 MB, SHA-256, Wi-Fi only | ✅ |
| Context rules (2,048 in / 768 out / 7 messages) | `InferenceEngine.swift` | `PromptBuilder.kt` | ✅ |

## Attachments and context

| Feature | iOS | Android approach | Status |
| --- | --- | --- | --- |
| + tray | `AttachMenu.swift` | Compose port | ✅ |
| Photos, camera (JPEG re-encode, no EXIF, 512 px) | `AppState.attachPhoto` | Photo Picker, `TakePicture` | ✅ |
| Files (text, code, CSV, JSON, PDF; 4,000 chars) | `AppState.attachFile` | Storage Access Framework, PdfBox-Android | ✅ |
| Connectors: Calendar (7 days), Reminders | `Connectors.swift` (EventKit) | Calendar Provider; Reminders kept by Loocal with notifications (Android has no shared reminders app) | ✅ |
| Connectors: Clipboard, Wikipedia | `Connectors.swift` | `ClipboardManager`, Wikipedia REST | ✅ |
| Web search | `Connectors.swift` (`WebSearch`) | same DuckDuckGo HTML parsing, top result read | ✅ |
| MCP servers (Streamable HTTP, token in Keychain) | `Connectors.swift` | `HttpURLConnection`; token encrypted with an Android Keystore key | ✅ |
| Tool calls (`<tool_call>`, 2 rounds) | `ToolCall`, `AppState.send` | same parser; tools described in the system prompt | ✅ |
| Local tools: calculator, places/routes, page reader, translation | `Tools.swift` | port; places and routes via OpenStreetMap (Nominatim, OSRM), translation via ML Kit on device | ✅ |
| Memory (remember / forget) | `Actions.swift` | port, Settings › Memory | ✅ |
| Actions with undo: reminder, event, memory | `Actions.swift` | Calendar Provider inserts, Loocal reminders, memory; Shortcuts actions ➖ (no Shortcuts app on Android) | ✅ |

## Voice

| Feature | iOS | Android approach | Status |
| --- | --- | --- | --- |
| Conversation mode (Tide UI, 1.1 s pause, sentence-by-sentence speech) | `VoiceConversation.swift`, `ConversationView.swift` | on-device `SpeechRecognizer`, Compose port | ✅ built, not yet tried by voice on the phone |
| Apple on-device model for voice | Foundation Models | ➖ not on Android: the selected local model answers (iOS already does this when Apple Intelligence is off) | ➖ |
| System voices page with previews | `VoiceSettings.swift` | `TextToSpeech` voices | ✅ |
| Natural voices (Kokoro-82M) | `NeuralVoices.swift` | sherpa-onnx 2.0.1 with Kokoro v1.0 (same 11 voices); one 350 MB engine download holds every voice; faster than real time on a Xiaomi 14 | ✅ |

## Errands

| Feature | iOS | Android approach | Status |
| --- | --- | --- | --- |
| Second tab set (Results, New errand, Routines) with bar flip | `RootView.swift` | Compose port, swipe on the bar | ✅ |
| Errand runs, queue, stages, results | `Errands.swift` | port: crew of 4, sources read in parallel, persona arc, ticket flight | ✅ |
| Routines on a cadence, run when due | `Errands.swift` | alarm → notification, run on open and every 30 s while open | ✅ |
| Pebble bot with moods | `Pebble.swift` | Canvas port: 6 shapes, 15 moods, 6 reactions | ✅ |
| Errand voice | `ErrandVoice.swift` | port: spoken errands join the crew, results read back | ✅ built, not yet tried by voice |
| Notifications when a run finishes | `ErrandDesk.announce` | `NotificationManager` | ✅ |

## System

| Feature | iOS | Android approach | Status |
| --- | --- | --- | --- |
| Replies continue in the background | `BGContinuedProcessingTask`, GPU→CPU handoff | foreground service (CPU only already) | ✅ |
| Live Activity / Dynamic Island | `IslandActivity` | ongoing notification: phase and token count only | ✅ |
| Siri Shortcuts (run errand / routine, open page) | `Shortcuts.swift` | launcher shortcuts: New chat, New errand, Results (running a routine from outside ➖ for now) | 🚧 |
| App icon choice (loop / bot) | `AppearancePage` | `activity-alias` switching | ✅ |
| Privacy: no backup, local-only clipboard, photo cleanup | various | `allowBackup=false`, sensitive clip, photos swept at launch and on edit/delete, temporary photos cleared | ✅ |
