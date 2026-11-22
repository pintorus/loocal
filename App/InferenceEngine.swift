import Foundation
import os
import MLX
import MLXLLM
import MLXLMCommon
import MLXNN
import MLXVLM

/// Where token generation runs. iOS forbids GPU work from a backgrounded app unless the
/// device grants background GPU (iPhone Air doesn't). Switching device in the middle of a
/// reply used to leave GPU work in flight as the app left, which iOS kills the app for.
/// Now the app says when it is *leaving*: generation pauses at the next token, drains the
/// GPU, and waits. If the app really goes to the background, the reply is rebuilt on the
/// CPU from the prompt plus the words written so far; if the user comes straight back, it
/// simply carries on on the GPU.
enum ComputeTarget {
    enum Mode: Sendable { case foreground, leaving, background }
    private static let lock = OSAllocatedUnfairLock(initialState: Mode.foreground)
    static var mode: Mode {
        get { lock.withLock { $0 } }
        set { lock.withLock { $0 = newValue } }
    }
    static var useCPU: Bool { mode == .background }
    static var device: Device { useCPU ? .cpu : .gpu }
    /// Whether writing may continue on the CPU in the background. Measured on an iPhone Air
    /// (Qwen3 VL 4B): the GPU writes ~12 tokens/s, the CPU didn't finish reading a short
    /// prompt in 7 minutes. So by default a reply pauses when the app leaves and resumes on
    /// the GPU when it's back.
    static let backgroundCPU = false

    /// Points MLX's process-wide default (used by anything not inside a scoped device,
    /// including the C++ core) at `device`. Scoped devices alone left some work on the GPU,
    /// which then waited on the CPU's slower results until Metal timed out and aborted.
    static func pin(_ device: Device) {
        Pinner.pin(device)
    }

    @available(*, deprecated)
    private enum Pinner {
        static func pin(_ device: Device) { Device.setDefault(device: device) }
    }
}

/// MLX isn't safe to drive from two places at once. The chat model and the natural voice
/// (Kokoro) both run on it, so each takes turns: one token, or one spoken sentence, at a time.
enum MLXGate {
    private static let lock = NSLock()
    static func run<T>(_ body: () throws -> T) rethrows -> T {
        lock.lock()
        defer { lock.unlock() }
        return try body()
    }
}

struct InferenceResult: Sendable {
    var text: String
    var tokens: Int
    var seconds: TimeInterval
    /// Lowest free memory seen during the reply, in megabytes.
    var headroom: Int
}

/// What a model costs in memory, worked out from its files before anything is loaded.
struct MemoryPlan: Sendable {
    /// Every weight file, as read at load.
    var weightBytes: Int
    /// KV cache per token of context, for the layers that keep the whole context (bf16).
    var kvPerToken: Int
    /// Sliding-window layers keep a fixed window whatever the length (bf16).
    var fixedKV: Int
    /// The vision tower, which stays on disk until a photo needs it.
    var visionBytes = 0

    /// 8-bit KV cache, group 64, with a bf16 scale and bias per group: 8.5 bits of 16.
    static let kvBits = 8
    static let quantizedShare = 8.5 / 16
    /// Below this many tokens the cache stays bf16: small, and attention over it is faster
    /// (about 10% on an iPhone Air). Past it, the cache switches to 8 bits.
    static let quantizedStart = 1024

    /// Cache bytes at `tokens` long: bf16 while short, 8-bit once it switches.
    func cache(_ tokens: Int) -> Int {
        tokens <= Self.quantizedStart ? kvPerToken * tokens : Int(Double(kvPerToken * tokens) * Self.quantizedShare)
    }

    init(model: ModelChoice, directory: URL) throws {
        let manager = FileManager.default
        weightBytes = try manager.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)
            .filter { $0.pathExtension == "safetensors" }
            .reduce(0) { $0 + ((try? $1.resolvingSymlinksInPath().resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0) }
        let root = (try? JSONSerialization.jsonObject(with: Data(contentsOf: directory.appendingPathComponent("config.json")))) as? [String: Any] ?? [:]
        let text = root["text_config"] as? [String: Any] ?? root
        func value(_ key: String) -> Int? { (text[key] as? NSNumber)?.intValue }
        let layers = value("num_hidden_layers") ?? 36
        let heads = value("num_attention_heads") ?? 32
        let kvHeads = value("num_key_value_heads") ?? heads
        let headDim = value("head_dim") ?? (value("hidden_size") ?? 4096) / max(heads, 1)
        let perLayer = kvHeads * headDim * 2 * 2 // keys and values, two bytes each
        if model.family == .gemmaText {
            // Gemma 3: one global layer in every `pattern`, the rest look back `window` tokens.
            let pattern = max(value("sliding_window_pattern") ?? 6, 1)
            let window = value("sliding_window") ?? 512
            let global = layers / pattern
            kvPerToken = global * perLayer
            fixedKV = (layers - global) * perLayer * window
        } else {
            kvPerToken = layers * perLayer
            fixedKV = 0
        }
    }

    /// Room a new reply needs: its whole KV cache at full length (8-bit), the prompt's cache
    /// once more in bf16 (prompt reading, before it is quantized), working space for a
    /// prompt step, and the vision tower for a photo.
    func need(prompt: Int, photo: Bool) -> Int {
        max(cache(prompt + ModelSpec.maxOutputTokens), kvPerToken * min(prompt + ModelSpec.maxOutputTokens, Self.quantizedStart))
            + kvPerToken * prompt + fixedKV + 96 * 1_048_576 + (photo ? visionBytes + 192 * 1_048_576 : 0)
    }

    /// What a reply that is already writing may still grow by (bf16, the larger case).
    func remaining(generated: Int) -> Int {
        kvPerToken * max(ModelSpec.maxOutputTokens - generated, 0)
    }
}

/// Runs the chat model. One copy of the weights is loaded; several replies (chats, errands,
/// voice) can write at the same time, each with its own KV cache, taking turns a token at a
/// time. A reply only starts once there is memory for all of it: the rest wait their turn,
/// in order, so the app stays inside iOS's per-app limit instead of being killed past it.
actor InferenceEngine {
    private var context: ModelContext?
    private var loadedID: String?
    private var loading: Task<Void, Error>?
    private var plan: MemoryPlan?
    private var directory: URL?
    /// The vision tower is on disk (lazy arrays), not in memory.
    private var visionParked = false

    /// One reply that is writing.
    private struct Lane {
        var seq: Int
        var need: Int
        var photo: Bool
        var prefilled = false
        var generated = 0
    }
    private var lanes: [UUID: Lane] = [:]
    /// Replies holding the loaded model (from load to finish), so it's never swapped under one.
    private var holders: Set<UUID> = []
    /// Replies waiting to start, first come first served.
    private var queue: [UUID] = []
    private var nextSeq = 0

    /// At most this many replies write at once; past that they're each too slow to be worth it.
    static let maxLanes = 3
    /// Left free for the app itself (interface, photos, speech) beyond what MLX may use.
    static let reserve = 520 * 1024 * 1024
    /// Kept free when deciding whether another reply can start alongside the others.
    static let cushion = 320 * 1024 * 1024

    /// Used when no persona is chosen.
    static let defaultSystemPrompt = "You are a helpful assistant. Answer directly and accurately."

    /// How many replies are writing or waiting.
    var activeCount: Int { lanes.count + queue.count }

    /// Releases the model, unless a reply is still using it.
    func unload() {
        guard lanes.isEmpty, queue.isEmpty, holders.isEmpty, loading == nil else { return }
        drop()
    }

    private func drop() {
        context = nil
        loadedID = nil
        plan = nil
        directory = nil
        visionParked = false
        #if !targetEnvironment(simulator)
        GPU.clearCache()
        #endif
    }

    /// Generate a reply. With MCP tools, the model may answer with a `<tool_call>`; the tool
    /// runs via `callTool`, its output is added to the chat and generation resumes (at most
    /// two tool rounds). Only text before a tool call is ever shown.
    func respond(model: ModelChoice, directory: URL, system: String, messages: [ChatMessage], imagesDirectory: URL,
                 tools: [ToolDefinition] = [],
                 callTool: @escaping @Sendable (ToolDefinition, String) async throws -> String = { _, _ in "" },
                 update: @escaping @Sendable (String, Int) async -> Void,
                 phase: @escaping @Sendable (String) async -> Void) async throws -> InferenceResult {
        #if targetEnvironment(simulator)
        throw IslandError.message("Model inference requires a physical iPhone or iPad. The simulator is for the interface and Live Activity only.")
        #else
        try Task.checkCancellation()
        let id = UUID()
        queue.append(id)
        do {
            let result = try await generate(id, model: model, directory: directory, system: system, messages: messages, imagesDirectory: imagesDirectory,
                                            tools: tools, callTool: callTool, update: update, phase: phase)
            leave(id)
            return result
        } catch {
            leave(id)
            throw error
        }
        #endif
    }

    #if !targetEnvironment(simulator)
    /// A reply is done (or stopped): its cache goes, and the vision tower back to disk.
    private func leave(_ id: UUID) {
        queue.removeAll { $0 == id }
        holders.remove(id)
        let lane = lanes.removeValue(forKey: id)
        if lane?.photo == true, !lanes.values.contains(where: \.photo) { parkVision() }
        if lanes.isEmpty {
            // Pending GPU work must finish while the app may still use the GPU.
            if ComputeTarget.useCPU { Stream.cpu.synchronize() } else if ComputeTarget.mode == .foreground { Stream.gpu.synchronize() }
            GPU.clearCache()
            ComputeTarget.pin(.gpu) // so speech and the next reply start from the usual default
        }
    }

    /// Loads `model`, once, however many replies ask for it at the same moment. A different
    /// model waits until every reply on the current one is done.
    private func ensureLoaded(_ id: UUID, _ model: ModelChoice, directory: URL, phase: @Sendable (String) async -> Void) async throws -> ModelContext {
        var waiting = false
        while true {
            try Task.checkCancellation()
            if let loading {
                _ = try? await loading.value
                continue
            }
            if loadedID == model.id, let context {
                holders.insert(id)
                return context
            }
            if loadedID != nil, !holders.isEmpty {
                if !waiting { await phase("Next up"); waiting = true }
                try await Task.sleep(for: .milliseconds(150))
                continue
            }
            break
        }
        drop() // Only one model is ever resident.
        await phase("Loading model")
        let plan = try MemoryPlan(model: model, directory: directory)
        // Loading needs every weight in memory at once. Past what this app may use, iOS
        // would kill it partway, so say so instead.
        let needed = plan.weightBytes + MemoryHeadroom.floor + 160 * 1_048_576
        if MemoryHeadroom.bytes < needed {
            GPU.clearCache()
            let gb = { (bytes: Int) in String(format: "%.1f GB", Double(bytes) / 1_073_741_824) }
            guard needed <= MemoryHeadroom.ceiling else {
                throw IslandError.message("\(model.name) needs about \(gb(needed)) to load, more than iOS lets Loocal use on this iPhone (\(gb(MemoryHeadroom.ceiling))). Pick a smaller model.")
            }
            // A model released a moment ago (after a memory warning, say) hands its memory
            // back to iOS over a few seconds: wait for it rather than refuse.
            let deadline = Date.now.addingTimeInterval(10)
            while MemoryHeadroom.bytes < needed && Date.now < deadline {
                try await Task.sleep(for: .milliseconds(250))
            }
            if MemoryHeadroom.bytes < needed {
                throw IslandError.message("\(model.name) needs about \(gb(needed)) free to load, and iOS gives Loocal \(gb(MemoryHeadroom.bytes)) right now. Try again in a moment, or pick a smaller model.")
            }
        }
        // Runs on this actor, so it can't start before `loading` is set; it clears it itself,
        // so replies waiting on it never find a finished load still marked as running.
        let task = Task {
            defer { self.loading = nil }
            try await self.load(model, directory: directory, plan: plan)
        }
        loading = task
        try await task.value
        guard let context else { throw IslandError.message("The model could not load.") }
        holders.insert(id)
        return context
    }

    private func load(_ model: ModelChoice, directory: URL, plan: MemoryPlan) async throws {
        GPU.set(cacheLimit: 64 * 1024 * 1024)
        let configuration = ModelConfiguration(directory: directory, extraEOSTokens: model.stopTokens)
        let loaded: ModelContext
        switch model.family {
        case .qwenVL:
            let (qwen, visionBytes) = try await Self.loadQwen(configuration, directory: directory)
            loaded = qwen
            self.plan = plan
            self.plan?.visionBytes = visionBytes
            visionParked = true
        case .gemmaText:
            loaded = try await LLMModelFactory.shared.load(configuration: configuration)
            self.plan = plan
        }
        context = loaded
        loadedID = model.id
        self.directory = directory
        GPU.clearCache()
    }

    /// Loads Qwen3 VL the way MLX's own loader does, except that the vision tower is never
    /// read in: its weights stay lazy arrays over the model file until a photo needs them.
    /// The stock loader evaluated all 5.8 GB at once, which on an iPhone Air left a few
    /// hundred megabytes at the peak of loading, and iOS sometimes killed the app right there.
    private static func loadQwen(_ configuration: ModelConfiguration, directory: URL) async throws -> (ModelContext, Int) {
        let data = try ModelCompatibility.qwenConfiguration(Data(contentsOf: directory.appendingPathComponent("config.json")))
        let base = try JSONDecoder().decode(BaseConfiguration.self, from: data)
        let model = Qwen3VL(try JSONDecoder().decode(Qwen3VLConfiguration.self, from: data))
        var weights: [String: MLXArray] = [:]
        for url in try FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil) where url.pathExtension == "safetensors" {
            for (key, value) in try loadArrays(url: url) { weights[key] = value }
        }
        weights = model.sanitize(weights: weights)
        if let quantization = base.perLayerQuantization {
            quantize(model: model) { path, _ in
                weights["\(path).scales"] == nil ? nil : quantization.quantization(layer: path)?.asTuple
            }
        }
        try model.update(parameters: ModuleParameters.unflattened(weights), verify: [.all])
        let parameters = model.parameters().flattened()
        let resident = parameters.filter { !$0.0.hasPrefix("vision_tower.") }.map(\.1)
        let visionBytes = parameters.filter { $0.0.hasPrefix("vision_tower.") }.reduce(0) { $0 + $1.1.nbytes }
        weights = [:]
        eval(resident)
        let tokenizer = try await loadTokenizer(configuration: configuration, hub: defaultHubApi)
        let preprocessor = directory.appendingPathComponent("preprocessor_config.json")
        let processorURL = FileManager.default.fileExists(atPath: preprocessor.path) ? preprocessor : directory.appendingPathComponent("processor_config.json")
        let processorType = try JSONDecoder().decode(BaseProcessorConfiguration.self, from: Data(contentsOf: processorURL)).processorClass
        let processor = try await VLMProcessorTypeRegistry.shared.createModel(configuration: processorURL, processorType: processorType, tokenizer: tokenizer)
        return (ModelContext(configuration: configuration, model: model, processor: processor, tokenizer: tokenizer), visionBytes)
    }

    /// Puts the vision tower back on disk. Its weights (bf16, 0.6–0.8 GB) are swapped for
    /// lazy arrays that read the same bytes from the model file, so they cost nothing until
    /// a photo is read, and that memory is free for replies the rest of the time.
    private func parkVision() {
        guard let context, let directory else { return }
        do {
            var weights: [String: MLXArray] = [:]
            for url in try FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil) where url.pathExtension == "safetensors" {
                for (key, value) in try loadArrays(url: url) where key.contains("vision_tower") || key.contains("visual") {
                    weights[key] = value
                }
            }
            let vision = context.model.sanitize(weights: weights).filter { $0.key.hasPrefix("vision_tower.") }
            guard !vision.isEmpty else { return }
            try context.model.update(parameters: ModuleParameters.unflattened(vision), verify: [.noUnusedKeys, .shapeMismatch])
            plan?.visionBytes = vision.values.reduce(0) { $0 + $1.nbytes }
            visionParked = true
            GPU.clearCache()
        } catch {
            visionParked = false // Stays resident; photos still work.
        }
    }

    /// Whether a reply needing `need` bytes can start now, next to the ones already writing.
    private func fits(_ need: Int, photo: Bool) -> Bool {
        guard !lanes.isEmpty else { return true }
        // A photo sets the model's position offsets for the reply reading it, so a reply with a
        // photo has the model to itself.
        guard lanes.count < Self.maxLanes, !photo, !lanes.values.contains(where: \.photo), let plan else { return false }
        let promised = lanes.values.reduce(0) { $0 + ($1.prefilled ? plan.remaining(generated: $1.generated) : $1.need) }
        return MemoryHeadroom.bytes - MemoryHeadroom.floor - Self.cushion - promised >= need
    }

    /// Waits, in order, until there's memory for this reply, then claims it.
    private func admit(_ id: UUID, need: Int, photo: Bool, phase: @Sendable (String) async -> Void) async throws {
        var waiting = false
        while !(queue.first == id && fits(need, photo: photo)) {
            try Task.checkCancellation()
            if !waiting { await phase("Next up"); waiting = true }
            try await Task.sleep(for: .milliseconds(150))
        }
        queue.removeFirst()
        lanes[id] = Lane(seq: nextSeq, need: need, photo: photo)
        nextSeq += 1
        // iOS kills the app outright past its per-app limit, with no warning to catch. Keep
        // MLX's own allocations under what this process still has: near the line it frees its
        // buffer cache and waits for queued GPU work instead of piling more on top. Relaxed,
        // so a reply never fails just for touching it.
        GPU.set(memoryLimit: GPU.activeMemory + max(256 * 1024 * 1024, MemoryHeadroom.bytes - Self.reserve), relaxed: true)
    }

    /// Reads all but the last prompt token into `cache`, a step at a time, and returns that
    /// last token for the token iterator. Only the cache is evaluated, so the model never
    /// works out next-word scores for the prompt itself: at 2,048 tokens those alone were
    /// 600 MB (152k-word vocabulary), all at once, on top of everything else.
    private func prefill(_ input: LMInput, model: any LanguageModel, cache: [KVCache], step: Int, imageToken: Int?, device: Device) async throws -> LMInput {
        let flat = input.text.tokens.reshaped([-1])
        let count = flat.dim(0)
        var start = 0
        if input.image != nil || input.video != nil {
            // Qwen places an image by counting from the start of the prompt, so everything up
            // to the last image token goes in one step; the text after it continues from there.
            let ids = flat.asArray(Int32.self)
            let last = imageToken.flatMap { token in ids.lastIndex { Int($0) == token } } ?? (count - 2)
            let end = min(max(last + 1, 1), count - 1)
            let head = LMInput(text: .init(tokens: flat[0 ..< end].expandedDimensions(axis: 0)), image: input.image, video: input.video)
            try MLXGate.run {
                try Device.withDefaultDevice(device) {
                    _ = try model.prepare(head, cache: cache, windowSize: nil)
                    eval(cache)
                }
            }
            start = end
        }
        while start < count - 1 {
            try Task.checkCancellation()
            let end = min(start + step, count - 1)
            let chunk = LMInput.Text(tokens: flat[start ..< end].expandedDimensions(axis: 0))
            MLXGate.run {
                Device.withDefaultDevice(device) {
                    _ = model(chunk, cache: cache, state: nil)
                    eval(cache)
                }
            }
            start = end
            // Other replies keep writing while a long prompt is read.
            if lanes.count > 1 { await Task.yield() }
        }
        let last = flat[(count - 1) ..< count]
        return LMInput(text: .init(tokens: input.text.tokens.ndim == 2 ? last.expandedDimensions(axis: 0) : last))
    }

    /// A token iterator over `input`, its prompt read into a fresh cache.
    private func iterator(for input: LMInput, context: ModelContext, parameters: GenerateParameters, step: Int, device: Device) async throws -> TokenIterator {
        let cache = context.model.newCache(parameters: parameters)
        let imageToken = input.image == nil && input.video == nil ? nil : context.tokenizer.convertTokenToId("<|image_pad|>")
        let last = try await prefill(input, model: context.model, cache: cache, step: step, imageToken: imageToken, device: device)
        return try MLXGate.run {
            try Device.withDefaultDevice(device) {
                try TokenIterator(input: last, model: context.model, cache: cache, parameters: parameters)
            }
        }
    }

    private func generate(_ id: UUID, model: ModelChoice, directory: URL, system: String, messages: [ChatMessage], imagesDirectory: URL,
                          tools: [ToolDefinition], callTool: @escaping @Sendable (ToolDefinition, String) async throws -> String,
                          update: @escaping @Sendable (String, Int) async -> Void,
                          phase: @escaping @Sendable (String) async -> Void) async throws -> InferenceResult {
        let context = try await ensureLoaded(id, model, directory: directory, phase: phase)
        try Task.checkCancellation()
        // Started while away (a routine firing, a reply sent as the app closed): wait for the app.
        if ComputeTarget.useCPU && !ComputeTarget.backgroundCPU {
            await phase("Paused")
            while ComputeTarget.mode != .foreground { try await Task.sleep(for: .milliseconds(250)) }
        }
        await phase("Reading prompt")
        // Qwen's chat template takes tool specs natively; Gemma gets them described in text.
        let specs: [[String: any Sendable]]? = tools.isEmpty || model.family != .qwenVL ? nil : tools.map(Self.spec)
        let systemText = tools.isEmpty || model.family == .qwenVL ? system : system + "\n\n" + Self.describe(tools)
        var recent = Array(messages.suffix(7))
        while recent.count > 1 && recent.first?.role == .assistant { recent.removeFirst() }
        var toolTurns: [Chat.Message] = []
        func prepare(_ recent: [ChatMessage]) async throws -> LMInput {
            let imageID = recent.last(where: { $0.imageName != nil })?.id
            let chat: [Chat.Message] = [.system(systemText)] + recent.map { message in
                // Text-only models never receive images; the photo stays in the transcript.
                let images: [UserInput.Image] = model.vision && message.id == imageID ? message.imageName.map { [.url(imagesDirectory.appendingPathComponent($0))] } ?? [] : []
                let content = message.context.map { $0 + "\n\n" + message.text } ?? message.text
                return Chat.Message(role: message.role == .user ? .user : .assistant, content: content, images: images)
            } + toolTurns
            let user = UserInput(chat: chat, processing: .init(resize: CGSize(width: 512, height: 512)), tools: specs)
            return try await Device.withDefaultDevice(ComputeTarget.device) {
                try await context.processor.prepare(input: user)
            }
        }
        // Tight on memory (a big model, a long prompt full of tool results): read the prompt
        // in smaller steps and keep it shorter, so its peak fits.
        let tight = MemoryHeadroom.megabytes < 1400
        let inputLimit = tight ? 1280 : ModelSpec.maxInputTokens
        let step = tight ? 128 : 512
        // Past 1,024 tokens the KV cache is kept in 8 bits: half the memory per token, no
        // visible difference in the writing.
        let parameters = GenerateParameters(maxTokens: ModelSpec.maxOutputTokens, kvBits: MemoryPlan.kvBits, kvGroupSize: 64, quantizedKVStart: MemoryPlan.quantizedStart,
                                            temperature: 0.7, topP: 0.9, prefillStepSize: step)
        let eos = Set(context.configuration.extraEOSTokens.compactMap { context.tokenizer.convertTokenToId($0) })
        var lowest = MemoryHeadroom.megabytes
        var total = 0
        let start = Date()
        for round in 0..<3 {
            var input = try await prepare(recent)
            while input.text.tokens.size > inputLimit && recent.count > 1 {
                try Task.checkCancellation()
                recent.removeFirst()
                while recent.count > 1 && recent.first?.role == .assistant { recent.removeFirst() }
                input = try await prepare(recent)
            }
            guard input.text.tokens.size <= inputLimit else {
                throw IslandError.message("This prompt exceeds the 2,048-token phone context. Shorten your message or attach a smaller file.")
            }
            try Task.checkCancellation()
            let photo = input.image != nil || input.video != nil
            let need = plan?.need(prompt: input.text.tokens.size, photo: photo && visionParked) ?? 0
            if lanes[id] == nil {
                try await admit(id, need: need, photo: photo, phase: phase)
            } else {
                // A tool round reads a longer prompt: it keeps its place and claims the extra.
                lanes[id]?.need = need
                lanes[id]?.prefilled = false
            }
            await phase("Generating")
            // Prompt reading runs wherever the app is right now: GPU in the foreground, CPU if
            // the user already left.
            var onGPU = !ComputeTarget.useCPU
            ComputeTarget.pin(onGPU ? .gpu : .cpu)
            var iterator = try await iterator(for: input, context: context, parameters: parameters, step: step, device: onGPU ? .gpu : .cpu)
            lanes[id]?.prefilled = true
            var tokens: [Int] = []
            while tokens.count < ModelSpec.maxOutputTokens {
                try Task.checkCancellation()
                if onGPU && ComputeTarget.mode != .foreground {
                    // Leaving: finish what the GPU already has, then queue nothing more until
                    // we know whether the app is coming back.
                    Stream.gpu.synchronize()
                    while ComputeTarget.mode == .leaving {
                        try await Task.sleep(for: .milliseconds(40))
                    }
                    if ComputeTarget.mode == .background && !ComputeTarget.backgroundCPU {
                        // Paused: nothing runs until the app is back, then the GPU picks up
                        // exactly where it stopped.
                        await phase("Paused")
                        while ComputeTarget.mode != .foreground {
                            try await Task.sleep(for: .milliseconds(200))
                        }
                        await phase("Generating")
                    } else if ComputeTarget.mode == .background {
                        await phase("Writing in background")
                        ComputeTarget.pin(.cpu)
                        iterator = try await self.iterator(for: Self.continuing(input, with: tokens), context: context, parameters: parameters, step: step, device: .cpu)
                        onGPU = false
                    }
                } else if !onGPU && ComputeTarget.mode == .foreground {
                    onGPU = true // Back in the app: CPU results sit in shared memory, carry on on the GPU.
                    ComputeTarget.pin(.gpu)
                }
                guard let token = MLXGate.run({ Device.withDefaultDevice(onGPU ? .gpu : .cpu, { iterator.next() }) }) else { break }
                if token == context.tokenizer.eosTokenId || token == context.tokenizer.unknownTokenId || eos.contains(token) { break }
                tokens.append(token)
                lanes[id]?.generated = total + tokens.count
                if tokens.count % 8 == 1 {
                    // Check real headroom; iOS's memory warnings arrive far earlier and are ignored.
                    lowest = min(lowest, MemoryHeadroom.megabytes)
                    if MemoryHeadroom.bytes < MemoryHeadroom.floor {
                        // With several replies writing, the newest gives way so the others fit.
                        let oldest = lanes.values.map(\.seq).min()
                        if lanes.count == 1 || lanes[id]?.seq != oldest {
                            await update(ToolCall.visible(context.tokenizer.decode(tokens: tokens)), total + tokens.count)
                            throw IslandError.lowMemory(MemoryHeadroom.megabytes)
                        }
                    }
                }
                if tokens.count == 1 || tokens.count % 4 == 0 {
                    await update(ToolCall.visible(context.tokenizer.decode(tokens: tokens)), total + tokens.count)
                }
                // Take turns with the other replies, a token each.
                if lanes.count > 1 || !queue.isEmpty { await Task.yield() }
            }
            // Pending GPU work must finish while the app may still use the GPU.
            if onGPU && ComputeTarget.mode == .foreground { Stream.gpu.synchronize() }
            total += tokens.count
            let text = context.tokenizer.decode(tokens: tokens)
            if round < 2, let call = ToolCall.parse(text), let tool = tools.first(where: { $0.tool.name == call.name }) {
                await phase(WebSearch.isLocal(tool) ? "Searching the web" : "Using \(tool.tool.name)")
                let output = (try? await callTool(tool, call.argumentsJSON)) ?? "The tool could not be reached."
                toolTurns += [.assistant(text), .tool(String(output.prefix(1500)))]
                continue
            }
            let visible = ToolCall.visible(text)
            await update(visible, total)
            return InferenceResult(text: visible, tokens: total, seconds: Date().timeIntervalSince(start), headroom: min(lowest, MemoryHeadroom.megabytes))
        }
        throw IslandError.message("The model kept calling tools without answering.")
    }
    #endif

    /// The original prompt followed by the tokens written so far, to resume on another device.
    /// Images (and their placeholder tokens) are kept, so a vision reply picks up exactly.
    static func continuing(_ input: LMInput, with generated: [Int]) -> LMInput {
        let prompt = input.text.tokens.asArray(Int32.self)
        let all = prompt + generated.map(Int32.init)
        let shape = input.text.tokens.ndim == 2 ? [1, all.count] : [all.count]
        let tokens = MLXArray(all).reshaped(shape)
        let mask = input.text.mask.map { MLX.ones(shape, dtype: $0.dtype) }
        return LMInput(text: .init(tokens: tokens, mask: mask), image: input.image, video: input.video)
    }

    /// OpenAI-style function spec for the chat template; schemas are trimmed to fit the context.
    private static func spec(_ definition: ToolDefinition) -> [String: any Sendable] {
        let schema = (try? JSONSerialization.jsonObject(with: Data(definition.tool.schema.utf8))) ?? [String: Any]()
        return ["type": "function", "function": [
            "name": definition.tool.name,
            "description": String(definition.tool.description.prefix(200)),
            "parameters": sendable(schema),
        ] as [String: any Sendable]]
    }

    private static func describe(_ tools: [ToolDefinition]) -> String {
        "You can call tools. To call one, reply with only <tool_call>{\"name\": \"…\", \"arguments\": {…}}</tool_call> and wait for the result.\nTools:\n"
            + tools.map { "- \($0.tool.name): \($0.tool.description.prefix(160)) Arguments schema: \($0.tool.schema.prefix(260))" }.joined(separator: "\n")
    }

    private static func sendable(_ value: Any) -> any Sendable {
        switch value {
        case let dictionary as [String: Any]: return dictionary.mapValues { sendable($0) } as [String: any Sendable]
        case let array as [Any]: return array.map { sendable($0) } as [any Sendable]
        case let number as NSNumber where CFGetTypeID(number) == CFBooleanGetTypeID(): return number.boolValue
        case let number as NSNumber: return number.doubleValue
        case let string as String: return string
        default: return "\(value)"
        }
    }
}
