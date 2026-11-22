# Loocal for Android (MVP)

Native Kotlin + Jetpack Compose port. Decisions: [docs/adr/0001-android-port.md](../docs/adr/0001-android-port.md). Vocabulary: [CONTEXT.md](../CONTEXT.md).

## Scope

Text chat with one local model, chat history (saved and temporary chats), Copy / Edit / Regenerate, up to three personas, model download with SHA-256 verification and resume. Photos, voice, MCP, connectors and widgets are not in the MVP.

Chat rules match iOS (`app/src/main/java/app/loocal/domain/PromptBuilder.kt`): 2,048-token input, 768-token reply, last 7 messages, older turns dropped to fit, 6,000-character prompt guard.

## Build

Create `local.properties` with your SDK path (it is gitignored):

```
sdk.dir=/path/to/Android/Sdk
```

Use a full JDK 17 (with `javac`); a JRE-only `JAVA_HOME` fails with "does not provide the required capabilities: [JAVA_COMPILER]".

```sh
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :app:testDebugUnitTest :app:assembleDebug
```

## Not done yet

0. **Measured on a Xiaomi 14** (Snapdragon 8 Gen 3, 12 GB, Android 16), CPU only, Qwen3 4B Instruct 2507 from bartowski (revision `ae44f08`), 508-token prompt, 2026-11-22. Run with `LlamaBenchmark` (see its header for arguments).

   | Configuration | Prompt reading | Generation | Memory (PSS) |
   | --- | --- | --- | --- |
   | First version: Q4_K_M, 4 threads, mmap | 36 tok/s | 11 tok/s | 4.8–5.4 GB |
   | **Current defaults**, Q4_K_M | 48 tok/s | 9–10 tok/s | 3.3 GB |
   | **Current defaults**, Q4_0 | 62 tok/s | 10 tok/s | 3.2 GB |
   | Q4_0, build with i8mm (`-Ploocal.armArch=armv8.6-a+dotprod+fp16+i8mm`) | 77–90 tok/s | 10 tok/s | 3.2 GB |

   Current defaults (`LlamaEngine.Options`): 4 generation threads, 6 prompt threads (8 is slower: the efficiency cores hold the others back), weights loaded without mmap (with mmap and repacking, the file pages and the repacked copy were both resident), repacking on. Numbers move by ±20% with the phone's temperature; compare runs made back to back.

   Still open:
   - The shipped model is **Q4_0**: it reads prompts about 30% faster than Q4_K_M, at a small cost in answer quality.
   - **i8mm** gives another ~25–40% on prompt reading but crashes on CPUs without it. Shipping it safely needs llama.cpp's runtime CPU variants (`GGML_BACKEND_DL` + `GGML_CPU_ALL_VARIANTS`), not a fixed build flag.
   - A full 2,048-token input still takes 25–40 s before the first word. A GPU backend (Vulkan or OpenCL on Adreno) is the next lever.

1. **Native engine tested on an emulator and on a Xiaomi 14.** llama.cpp is a git submodule at `app/src/main/cpp/llama.cpp`, pinned to tag `b12634` (run `git submodule update --init` after cloning). `loocal_llama.cpp` is the JNI bridge: CPU only, chat template from the GGUF metadata, fresh KV cache per reply. Release builds ship arm64 only; debug adds x86_64 for the emulator. Not yet run on a real phone or with a 4B model, so speed and memory are unmeasured.

   Engine tests (`app/src/androidTest/.../LlamaEngineTest.kt`) run against any small GGUF model, for example SmolLM2 135M Instruct Q8_0:

   ```sh
   ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
   adb install -r -t app/build/outputs/apk/debug/app-debug.apk
   adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
   adb shell mkdir -p /sdcard/Android/data/app.loocal/files
   adb push model.gguf /sdcard/Android/data/app.loocal/files/test-model.gguf
   adb shell am instrument -w -e class app.loocal.inference.LlamaEngineTest app.loocal.test/androidx.test.runner.AndroidJUnitRunner
   ```
2. **Model.** `app/src/main/assets/model-4b.json` pins Qwen3 4B Instruct 2507 Q4_0 (2.4 GB; revision, size and SHA-256 checked against Hugging Face). Tested end to end on the Xiaomi 14 on 2026-11-22: Settings → Download model (about 85 s on Wi-Fi, hash verified), a French question answered correctly with live streaming and Stop, and the chat still in History after the app restarted. Not tested: resuming an interrupted download, Edit/Regenerate/personas in the UI, and the download refusing mobile data (it doesn't yet — Wi-Fi-only is an open question in the ADR).
3. **Background replies and Wi-Fi-only downloads** (WorkManager / foreground service) are open questions in the ADR.
