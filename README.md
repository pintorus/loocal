# Loocal

A native SwiftUI iPhone app for private chat with open models that run entirely on the phone, through [MLX Swift](https://github.com/ml-explore/mlx-swift). No account, no server, no API key: after the one-time model download, text, photos and voice are processed on device. Built for iPhone Air on iOS 26 or later.

<img src="Preview.png" width="300" alt="Loocal chat screen">

The Xcode project and bundle identifier still use the working name `IslandAI`.

## Open and run

1. Open `IslandAI.xcodeproj` in Xcode 27.2 or later.
2. If Xcode reports that the Metal compiler is missing, install it with `xcodebuild -downloadComponent MetalToolchain`.
3. Select the **IslandAI** scheme and your connected iPhone Air.
4. Under **Signing & Capabilities**, select your own Apple development team (none is set in the repository) for both **IslandAI** and **IslandActivity**. Use a unique bundle identifier if needed; keep the extension identifier beneath the app identifier. The background task identifier is derived from the bundle identifier.
5. Ensure the app's **Background GPU Access** and **Increased Memory Limit** capabilities are provisioned. Their entitlements are included. An Apple development team/profile that supports the requested capabilities is required for the full background path. Xcode may require a paid developer membership for capabilities unavailable to a personal team.
6. Build and run. On the phone, open **Settings → Download model** over Wi-Fi. Allow about **5.8 GB** for model files and at least **7 GB** of free space before downloading.
7. Start a chat, or attach a photo using **+**. Once downloaded, text and image inference operate offline.

To use a foreground-only provisioning profile, remove the Background GPU Access entitlement in the app target's entitlements file, then build. Replies then move to the CPU in the background, as on iPhone Air. If you remove Increased Memory Limit too, the 8B model may exceed the process memory limit; actual memory fit must be measured on the phone.

## Design

Monochrome, light and dark (Settings → Appearance). Type is [Geist](https://github.com/vercel/geist-font) for the interface and [Instrument Serif](https://github.com/Instrument/instrument-serif) for display, both bundled under the SIL Open Font License (`App/Fonts`). The bottom bar follows Soma's destination bar: three label-free icons — History, New chat, Settings — with a soft pill behind the selected one. Tapping New chat while viewing a chat starts a fresh one. The loader is a native SwiftUI port of the MIT-licensed [21st.dev Spiral Loader](https://21st.dev/@21st/components/spiral-loader) (`App/SpiralLoader.swift`), and the app icon is the same writing loop, black on white.

**Models.** Drag the centered selector sideways to pick one of three uncensored ("abliterated") community models, small to large, left to right. Each downloads separately from Settings → Models, with every weight file pinned by commit and SHA-256 (`App/ModelManifest*.json`):

| Model | Repository | Size | Input |
| --- | --- | --- | --- |
| Qwen3 VL 4B | [`EZCon/Huihui-Qwen3-VL-4B-Instruct-abliterated-4bit-mlx`](https://huggingface.co/EZCon/Huihui-Qwen3-VL-4B-Instruct-abliterated-4bit-mlx) | 3.1 GB | words, photos |
| Qwen3 VL 8B | [`veeceey/Huihui-Qwen3-VL-8B-Instruct-abliterated-mlx-4bit`](https://huggingface.co/veeceey/Huihui-Qwen3-VL-8B-Instruct-abliterated-mlx-4bit) | 5.8 GB | words, photos |
| Gemma 3 12B | [`mlx-community/gemma-3-12b-it-qat-abliterated-lm-4bit`](https://huggingface.co/mlx-community/gemma-3-12b-it-qat-abliterated-lm-4bit) | 7.4 GB | words only |

The Qwen models are Apache 2.0; Gemma 3 is under the [Gemma Terms of Use](https://ai.google.dev/gemma/terms). The 12B is larger than the 8B that already sits near the iPhone Air's memory ceiling; whether it fits must be confirmed on the device. The 4B and 12B download in parallel: files over 128 MB are split into byte ranges fetched over up to six connections, stitched in order and SHA-256 verified; finished pieces survive retries and relaunches. The 8B keeps its original folder, background-session identifier and single-stream transfer so an in-progress download survives app updates.

**Personas.** Settings → Personas holds up to three named system prompts, each with an icon. Hold the send button and a capsule unrolls leftward with the persona icons; slide along it and release on one, or release and tap. The loop icon nearest the button means no persona (default prompt). The composer reads "Ask <persona>" while one is active.

**Conversation mode** (button beside send). On-device speech recognition → Apple's on-device model (Foundation Models), or the selected local model when Apple Intelligence is off → spoken reply sentence by sentence. The mic closes while Loocal speaks; a 1.1 s pause ends your turn. Turns are saved into the current chat. Settings → Voice opens a page of voices with gender and quality tags; tapping one previews it. System voices are the best installed by default (iPhone ships compact ones; Enhanced/Premium are free downloads in Settings › Accessibility › Spoken Content › Voices). Natural voices are open-source Kokoro-82M (Apache 2.0, [`mlx-community/Kokoro-82M-bf16`](https://huggingface.co/mlx-community/Kokoro-82M-bf16), pinned and SHA-256-verified) run on device through [KokoroSwift](https://github.com/mlalma/kokoro-ios) 1.0.10 and [MisakiSwift](https://github.com/mlalma/MisakiSwift) 1.0.5, vendored unchanged in `Vendor/KokoroSwift` (licenses included) as a static package — upstream ships dynamic frameworks, which crashed launch when not embedded and otherwise duplicated MLX in the app: the first downloads the 327 MB engine, each voice adds 0.5 MB. English only.

**Temporary chats.** Tap the loop logo on a new chat: it turns dashed and the chat is never saved or listed in History; it's discarded when you leave it. Tap again for a normal chat.

**Message actions.** Long-press a prompt for Copy, Edit (rewinds the chat to it) and Select Text; long-press a reply for Copy, Read Aloud, Share, Regenerate (latest reply) and Select Text.

**Tool islands.** Tools, connectors and attachments sit *inside* the prompt as small coloured capsules (Maps green, Calendar red, Reminders orange, Web blue, files amber, photos pink), the one splash of colour on the monochrome page. Picking one in the + tray puts its island at the cursor; long-press an island and drag it anywhere else in the text. Attachments still float above the prompt bar, and their island in the text points at them, so you can say where a file or photo matters. Tool islands apply to that message only: deleting one switches the tool off. The model reads islands by name ("find a café [Maps] …"); a Maps island searches Apple Maps even without "near me". Replies show what tools found as widgets in the text, right after the opening paragraph: a map with numbered places, a calculator result, a translation. The tool calls themselves fold into one grey line ("Used 2 tools") that opens to show each step and its sources.

**Attachments and connectors** (the + tray). Photos, Camera, and Files (text, Markdown, code, CSV, JSON, PDF; trimmed to 4,000 characters to fit the 2,048-token context). Connectors add context right before a reply: Calendar (next 7 days) and Reminders via EventKit, Clipboard, and Wikipedia (sends only the question's key terms). Apple Notes has no public API, so it isn't offered. MCP servers (Streamable HTTP, optional bearer token in the Keychain) expose up to 8 tools each; the model calls them with `<tool_call>` blocks, at most two rounds per reply.

In a Debug simulator build, launch with `-uiDemo` to load sample chats (never saved) for interface review.

## The selected model

- Family: **Qwen3-VL 8B Instruct**, Huihui abliterated variant, MLX 4-bit affine quantization, group size 64.
- Exact repository: [`veeceey/Huihui-Qwen3-VL-8B-Instruct-abliterated-mlx-4bit`](https://huggingface.co/veeceey/Huihui-Qwen3-VL-8B-Instruct-abliterated-mlx-4bit).
- Pinned revision: `5da6bcf64013f3c81dba671088873361f1b27684`.
- Model origin: [Huihui model card](https://huggingface.co/huihui-ai/Huihui-Qwen3-VL-8B-Instruct-abliterated), derived from [Qwen3-VL 8B Instruct](https://huggingface.co/Qwen/Qwen3-VL-8B-Instruct). The upstream cards list Apache 2.0. The selected community conversion does not supply a model card or provenance statement of its own; its architecture and quantization metadata match the selected family, but the conversion has not been independently audited.
- Files and expected sizes are recorded in `App/ModelManifest.json`. SHA-256 verification covers both weight shards and `tokenizer.json`; remaining small files are pinned by repository commit and checked for expected length.
- Runtime compatibility: the checkpoint declares `tie_word_embeddings: false` at the root, while the pinned Swift implementation reads it inside `text_config`. A dedicated model factory copies that setting into the in-memory decoded configuration so the trained output head is used. The downloaded files remain unchanged.
- “Abliterated” describes a community modification intended to reduce refusals. There is no extra app moderation layer. The model may still refuse requests and its answers may be wrong.

The app downloads the manifest's model files to Application Support using a background `URLSession`. Completed verified files are reused on retry. Transfers are Wi-Fi only; iOS manages background scheduling. Model weights are excluded from device backup. Chats and imported images stay in the app sandbox, protected until the device's first unlock after reboot. Imported photos are re-encoded as JPEG without their original EXIF metadata.

## Background work and Dynamic Island

Each reply asks iOS for a user-initiated `BGContinuedProcessingTask`. If iOS doesn't answer within two seconds, the reply starts anyway, and a grant that arrives later still keeps the app alive in the background.

iOS forbids GPU work from a backgrounded app unless the device grants background GPU, and iPhone Air doesn't. Switching device in the middle of a reply left GPU work in flight, and iOS killed the app for it. So generation is coordinated with the app's lifecycle (`ComputeTarget` in `App/InferenceEngine.swift`):

- **Leaving** (`willResignActive`): generation pauses at the next token and waits for the GPU to finish its queued work.
- **Back right away** (Control Center, a notification): it continues on the GPU from where it paused.
- **Really in the background** (`didEnterBackground`): the reply is rebuilt on the CPU from the prompt plus the tokens written so far, and keeps going (slower).
- **Return to the app**: generation moves back to the GPU between two tokens.

Known limitation: leaving while the prompt is still being read (before the first token) finishes that one pass on the GPU, which iOS may refuse once the app is in the background.

The bundled WidgetKit extension shows a Live Activity when iOS doesn't provide its own. It ends immediately when the reply finishes in the app, after 8 seconds from the background, and any leftover is cleared when the app opens. No prompt, reply, or photo appears on the Lock Screen or in Dynamic Island. Force-quitting the app stops local inference.

## Privacy

- Inference, speech recognition (on-device only; voice mode refuses to listen otherwise) and the natural voices all run on the phone. Network use: model and voice downloads from Hugging Face, the Wikipedia connector (only the question's key terms), and MCP servers you add.
- Everything the app stores is excluded from iCloud and computer backups, and protected by iOS Data Protection.
- Temporary chats are never written to the chat archive. Their photos live in a separate folder that is deleted when the chat ends and at every launch.
- Deleting a chat or editing a message deletes the photos it used. At launch, any photo no saved chat refers to, and the app's scratch folder, are removed.
- Copying text uses a local-only pasteboard (no Universal Clipboard).

## Memory and context

The 8B vision model is intentionally substantial for a phone. The source does not promise a measured speed or memory fit on iPhone Air until a signed physical-device run is completed.

- Maximum prepared input: **2,048 tokens**, including image placeholders and the chat template.
- Maximum reply: **768 tokens**.
- History: up to the most recent seven messages, retaining only the most recent attached image within those messages.
- Image preprocessing: **512 × 512** bound; imported photo thumbnails are stored up to 1,024 pixels.
- Prompt character guard: 6,000 characters, with the actual token count checked before inference.
- A fresh KV cache is created per reply. Model and GPU cache are released when each job finishes. Older turns are automatically omitted when needed to fit the input budget. A single overlong prompt is rejected with a clear message.

**How memory is kept down** (`App/InferenceEngine.swift`):

- *Vision tower on disk.* Qwen3 VL's vision tower is stored unquantized (bf16). Loocal loads the language model itself and leaves the vision weights as lazy arrays over the model file: they're read in only for a reply with a photo, then put back. Loading is faster and its peak no longer includes them.
- *Prompt read in steps, without next-word scores.* The stock Qwen path read the whole prompt in one pass and computed vocabulary scores (152k words) for every prompt token, about 600 MB at 2,048 tokens. The prompt now goes in 512-token steps (128 when tight) and only the KV cache is evaluated.
- *8-bit KV cache past 1,024 tokens.* Short chats keep a bf16 cache, which is faster; long ones switch to 8 bits, about half the memory.
- *Check before loading.* A model that can't fit in what iOS gives Loocal is refused with a message, not loaded until iOS kills the app. Memory from a model just released is waited for (it returns within about a second).

Measured on an iPhone Air with the 8B, old engine → new: free memory after a short reply 466 MB → 1,566 MB; a 1,500-token prompt was killed by iOS → finished with 1,090 MB left; load 4.0 s → 2.8 s; writing speed about the same (8.5–8.9 tokens/s).

**Several replies at once.** Chats and errands can write at the same time, up to three. They share one copy of the weights, each with its own KV cache, and take turns a token at a time, so each writes more slowly but none waits for another to finish. A new reply starts only when its whole cache fits next to the others; otherwise it waits its turn ("Next up"). A reply with a photo has the model to itself, because Qwen's image positions live on the shared model. If memory still runs low, the newest reply stops first and keeps its text. On the iPhone Air: two at once, about 4.3–4.9 tokens/s combined; three, about 3.6–4.0.

The recent-history limit is deliberate. Older messages remain in the saved transcript but are not all sent to the model. The app is a local chat client, not a permanently running HTTP server.

## Build and verify

The checked-in `.xcodeproj` is ready to open. `project.yml` is its XcodeGen source; regenerate with `xcodegen generate` after changing the project layout.

```sh
xcodebuild -project IslandAI.xcodeproj -scheme IslandAI \
  -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath build CODE_SIGNING_ALLOWED=NO build

xcodebuild -project IslandAI.xcodeproj -scheme IslandAI \
  -destination 'generic/platform=iOS' \
  -derivedDataPath build-device CODE_SIGNING_ALLOWED=NO build

xcodebuild -project IslandAI.xcodeproj -scheme IslandAI \
  -destination 'platform=iOS Simulator,name=iPhone Air' \
  -derivedDataPath build CODE_SIGNING_ALLOWED=NO test
```

The simulator supports interface validation and persistence/integrity tests. It deliberately refuses MLX inference because Apple GPU behavior and background GPU scheduling need a physical device. The non-simulator engine is separately compiled by the device build.

Before relying on background execution on the phone, verify: text generation offline; photo generation offline; Go Home and Lock Screen progress; system Live Activity cancellation; in-app Stop; expiration/low-memory behavior; process termination and transcript recovery. Use Instruments to measure peak memory and thermal behavior. These checks cannot be established by a simulator build.

Dependencies are pinned through Xcode's `Package.resolved`: [MLX Swift LM](https://github.com/ml-explore/mlx-swift-lm) 2.29.3 and [MLX Swift](https://github.com/ml-explore/mlx-swift) 0.29.1, with their transitive Swift packages. Consult each package's license before redistribution.


## Status

A personal project, tested on one iPhone Air. The simulator runs the interface and unit tests but refuses MLX inference; generation, memory fit and background behaviour need a physical device. The models are community "abliterated" conversions with no added moderation, and their answers may be wrong.

## License

App code: MIT (`LICENSE`). Bundled fonts: SIL Open Font License (`App/Fonts`). Vendored KokoroSwift (MIT) and MisakiSwift (Apache 2.0): `Vendor/KokoroSwift`. Model weights are downloaded at runtime under their own licenses (see the table above).
