# Loocal — Domain Context

Shared vocabulary for the project. Use these terms in code, docs and discussion.

## Terms

- **Chat**: a saved conversation, listed in History. Holds an ordered list of messages.
- **Temporary chat**: a chat that is never saved or listed; its photos are deleted when it ends.
- **Message**: one prompt from the user or one reply from the model, optionally with attachments.
- **Reply**: a model-generated message. A reply can be regenerated (latest reply only).
- **Model**: one of three local, quantized, community-converted language models the user can download. Each has a pinned manifest of files and hashes.
- **Model manifest**: the list of files, sizes and SHA-256 hashes (plus pinned revision) that defines an installable model.
- **Download**: the transfer of a model's files to on-device storage. Wi-Fi only on iOS.
- **Persona**: a named system prompt with an icon. Up to three exist; the default is "no persona".
- **Connector**: a source of extra context added right before a reply (Calendar, Reminders, Clipboard, Wikipedia). Apple-only connectors have no Android equivalent in the MVP.
- **MCP server**: a user-configured remote tool server (Streamable HTTP). Out of MVP scope.
- **Conversation mode**: hands-free voice loop (speech in, model, spoken reply). Out of MVP scope.
- **Inference**: running a model on the device to produce a reply.
- **Context budget**: the maximum prepared input (2,048 tokens) including the chat template and image placeholders. Older turns are dropped to fit.
- **Recent history**: the most recent seven messages sent to the model. Older messages stay in the saved transcript.
- **Reply limit**: the maximum generated length (768 tokens).

## Platforms

- **iOS app** (current): SwiftUI, MLX Swift inference, Apple Foundation Models, Live Activity / Dynamic Island, EventKit, Keychain, on-device Speech.
- **Android app** (planned): Kotlin + Jetpack Compose, llama.cpp (GGUF) inference. The MVP is text chat with one local model. Platform-specific features are deferred.

## Principles

- **On-device only.** No account, no server, no API key. Network use is limited to model and voice downloads, the Wikipedia connector and user-added MCP servers.
- **No sync between devices.** Each device's chats and settings are independent.
- **Private storage.** Chats and photos stay in the app sandbox and are excluded from backups.

## Relationships

- A chat has many messages. A message may reference attachments.
- A reply is produced by exactly one model using one persona (or none).
- A model is downloaded once per device and can be used by many chats.

See [docs/adr/0001-android-port.md](docs/adr/0001-android-port.md) for the decisions behind the Android port.
