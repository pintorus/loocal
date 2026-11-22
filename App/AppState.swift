import Foundation
import Observation
import PDFKit
import UIKit
import MLX

@MainActor @Observable
final class AppState {
    let files: LocalFiles
    /// One download per model, keyed by `ModelChoice.id`.
    let downloads: [String: ModelDownload]
    /// Open-source Kokoro voices for speech output.
    let neuralVoices: NeuralVoiceStore
    /// Errands: one-off and scheduled jobs the model carries out on device.
    let desk: ErrandDesk
    /// Every background download session, for routing iOS completion events.
    var allDownloads: [ModelDownload] { Array(downloads.values) + [neuralVoices.engine] }
    var selectedModelID: String { didSet { UserDefaults.standard.set(selectedModelID, forKey: "model") } }
    var personas: [Persona] { didSet { Persona.save(personas); BotShape.sync(personas) } }
    var personaID: UUID? { didSet { UserDefaults.standard.set(personaID?.uuidString, forKey: "persona") } }
    /// Light memory: facts you asked Loocal to keep in mind, added to every reply.
    var memories: [Memory] { didSet { Memory.save(memories) } }
    var memoryOn: Bool { didSet { UserDefaults.standard.set(memoryOn, forKey: "memory.on") } }
    /// A page a Shortcut asked to open; the root view goes there and clears it.
    var requestedTab: AppTab?
    var conversations: [Conversation]
    var selectedID: UUID
    var draft = ""
    /// Tools, connectors and attachments placed in the draft's text.
    var draftIslands: [Island] = []
    var attachmentName: String?
    /// Files attached to the next message; their text goes to the model with it.
    var attachedFiles: [AttachedFile] = []
    var activeConnectors: Set<String> { didSet { UserDefaults.standard.set(Array(activeConnectors), forKey: "connectors.active") } }
    var mcpServers: [MCPServer] { didSet { MCPServer.save(mcpServers) } }
    var connectors: [Connector] { Connector.builtIn + mcpServers.map(Connector.mcp) }
    /// Replies and errands on the go, by reply or run id: several write at once.
    private(set) var working: Set<UUID> = []
    /// Conversations with a reply on the way.
    private(set) var replying: Set<UUID> = []
    /// What each reply or errand is doing ("Reading prompt", "Generating"…), by its id.
    private(set) var phases: [UUID: String] = [:]
    /// The chat on screen is being answered: its composer waits, the rest of the app doesn't.
    var isBusy: Bool { replying.contains(selectedID) }
    /// Anything is writing, in any chat or errand.
    var isWorking: Bool { !working.isEmpty }
    /// The reply being written in the chat on screen.
    var currentReplyID: UUID? { current.messages.last(where: { $0.status == .generating && $0.role == .assistant })?.id }
    var phase: String { currentReplyID.flatMap { phases[$0] } ?? "Ready" }
    var performance = ""
    var notice: String?
    var error: String?
    @ObservationIgnored private let engine = InferenceEngine()
    @ObservationIgnored private let continued = ContinuedInference()
    @ObservationIgnored private let activity = ActivityController()
    @ObservationIgnored private var jobs: [UUID: Task<Void, Never>] = [:]
    @ObservationIgnored private var jobTokens: [UUID: Int] = [:]
    @ObservationIgnored private var stopReasons: [UUID: String] = [:]
    @ObservationIgnored private var lastSaved = Date.distantPast
    private(set) var isDemo = false
    /// The prompt box shows once the model is installed (the simulator demo pretends it is).
    var composerReady: Bool { download.isReady || isDemo }
    /// Short UIKit grace period, used only when iOS declined the continued-processing task.
    @ObservationIgnored private var graceTask: UIBackgroundTaskIdentifier = .invalid

    var model: ModelChoice { ModelChoice.with(id: selectedModelID) }
    var download: ModelDownload { downloads[model.id]! }
    var persona: Persona? { personas.first { $0.id == personaID } }
    var systemPrompt: String {
        let custom = persona?.prompt.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return custom.isEmpty ? InferenceEngine.defaultSystemPrompt : custom
    }

    var current: Conversation { conversations.first(where: { $0.id == selectedID }) ?? Conversation() }
    var canSend: Bool { download.isReady && !isBusy && (!draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || attachmentName != nil || !attachedFiles.isEmpty) }

    init() throws {
        _ = MemoryHeadroom.bytes // notes how much iOS lets Loocal use, before any model loads
        let files = try LocalFiles()
        self.files = files
        desk = ErrandDesk(directory: files.root)
        var downloads: [String: ModelDownload] = [:]
        for choice in ModelChoice.all {
            let directory = choice.folder.map { files.models.appendingPathComponent($0, isDirectory: true) } ?? files.models
            downloads[choice.id] = ModelDownload(spec: try .bundled(choice.manifest), directory: directory, sessionID: choice.sessionID, chunked: choice.parallelDownload)
        }
        self.downloads = downloads
        neuralVoices = NeuralVoiceStore(models: files.models)
        VoiceLibrary.preload()
        selectedModelID = ModelChoice.with(id: UserDefaults.standard.string(forKey: "model") ?? "").id
        let savedPersonas = Persona.load()
        personas = savedPersonas
        BotShape.sync(savedPersonas)
        personaID = UserDefaults.standard.string(forKey: "persona").flatMap(UUID.init(uuidString:))
        memories = Memory.load()
        memoryOn = UserDefaults.standard.object(forKey: "memory.on") as? Bool ?? true
        // Connectors live in the prompt as islands, one message at a time, so none start on.
        activeConnectors = []
        mcpServers = MCPServer.load()
        let first = Conversation()
        conversations = [first]
        selectedID = first.id
        do {
            if let archive = try files.load(), !archive.conversations.isEmpty {
                conversations = archive.conversations
                selectedID = conversations.contains(where: { $0.id == archive.selectedID }) ? archive.selectedID : conversations[0].id
            }
        } catch {
            let backup = files.root.appendingPathComponent("conversations-recovery-" + UUID().uuidString + ".json")
            do {
                try FileManager.default.copyItem(at: files.archive, to: backup)
                self.error = "Saved chats couldn’t be read. A recovery copy is preserved on this device. \(error.localizedDescription)"
            } catch {
                // Never overwrite unreadable history if its recovery copy cannot be made.
                throw IslandError.message("Saved chats couldn’t be recovered: \(error.localizedDescription)")
            }
        }
        if self.error == nil {
            files.sweep(keeping: Set(conversations.flatMap { $0.messages.compactMap(\.imageName) }))
        }
    }

    /// Conversations worth listing in History (blank drafts are hidden).
    var history: [Conversation] { conversations.filter { !$0.messages.isEmpty && !$0.isTemporary } }
    var isTemporary: Bool { current.isTemporary }

    /// Flip a fresh, empty chat between normal and temporary.
    func toggleTemporary() {
        guard !isBusy, let index = conversations.firstIndex(where: { $0.id == selectedID }), conversations[index].messages.isEmpty else { return }
        conversations[index].temporary = conversations[index].isTemporary ? nil : true
    }

    /// Temporary chats vanish as soon as you move on from them.
    private func discardTemporary(keeping id: UUID? = nil) {
        for conversation in conversations where conversation.isTemporary && conversation.id != id && !conversation.messages.isEmpty && !replying.contains(conversation.id) {
            for name in conversation.messages.compactMap(\.imageName) { try? FileManager.default.removeItem(at: files.images.appendingPathComponent(name)) }
            conversations.removeAll { $0.id == conversation.id }
        }
    }

    func newConversation() {
        discardTemporary()
        if let index = conversations.firstIndex(where: { $0.messages.isEmpty }) {
            conversations[index].temporary = nil
            selectedID = conversations[index].id // Reuse an untouched chat rather than stacking empty ones.
        } else {
            let conversation = Conversation()
            conversations.insert(conversation, at: 0)
            selectedID = conversation.id
        }
        draft = ""
        discardAttachment()
        performance = ""
        save()
    }

    func togglePin(_ conversation: Conversation) {
        guard let index = conversations.firstIndex(where: { $0.id == conversation.id }) else { return }
        conversations[index].pinned = conversations[index].isPinned ? nil : true
        save()
    }

    func select(_ conversation: Conversation) {
        discardTemporary(keeping: conversation.id)
        selectedID = conversation.id
        draft = ""
        discardAttachment()
        performance = ""
        save()
    }

    #if DEBUG
    /// Device benchmark (`-bench` launch argument): times loading, prompt reading and
    /// writing on the GPU, then on the CPU as if the app were in the background. Printed
    /// to the console with a BENCH prefix.
    func benchmark() async {
        guard download.isReady, let directory = try? download.loadingDirectory() else { print("BENCH no model ready"); return }
        let model = model
        print("BENCH model \(model.name) free=\(MemoryHeadroom.megabytes)MB")
        for cpu in ComputeTarget.backgroundCPU ? [false, true] : [false] {
            ComputeTarget.mode = cpu ? .background : .foreground
            let start = Date()
            let marks = BenchMarks()
            let messages = [ChatMessage(role: .user, text: "In three short sentences, why is the sea salty?")]
            let run = Task {
                try await engine.respond(model: model, directory: directory, system: InferenceEngine.defaultSystemPrompt, messages: messages, imagesDirectory: files.images, update: { _, count in
                    await marks.token(count)
                }, phase: { phase in await marks.phase(phase) })
            }
            // The CPU pass only needs enough tokens to measure its speed.
            let watchdog = Task { try? await Task.sleep(for: .seconds(cpu ? 120 : 240)); run.cancel() }
            let result = await run.result
            watchdog.cancel()
            let summary = await marks.summary(since: start)
            switch result {
            case .success(let reply): print("BENCH \(cpu ? "CPU" : "GPU") ok \(summary) tokens=\(reply.tokens) headroom=\(reply.headroom)MB")
            case .failure(let error): print("BENCH \(cpu ? "CPU" : "GPU") failed \(summary) error=\(error.localizedDescription)")
            }
        }
        ComputeTarget.mode = .foreground
        print("BENCH idle free=\(MemoryHeadroom.megabytes)MB (model loaded)")
        // A long prompt (about 1,500 tokens): the prompt-reading peak.
        let filler = String(repeating: "The tide rises and falls twice a day as the moon pulls on the ocean, and fishermen plan around it. ", count: 70)
        await benchPass("LONG", model: model, directory: directory, prompts: [filler + "\n\nSum this up in one sentence."])
        await engine.unload()
        for wait in [0, 1, 3, 6] {
            try? await Task.sleep(for: .seconds(wait))
            print("BENCH unloaded +\(wait)s free=\(MemoryHeadroom.megabytes)MB mlxActive=\(GPU.activeMemory / 1_048_576)MB mlxCache=\(GPU.cacheMemory / 1_048_576)MB")
        }
        // Replies at the same time, sharing the loaded weights.
        await benchPass("PAIR", model: model, directory: directory, prompts: ["Name five rivers in Europe and one fact about each.", "Give me a four-line poem about autumn."])
        await benchPass("TRIO", model: model, directory: directory, prompts: ["List three uses for baking soda.", "Explain what a prime number is.", "Suggest a name for a grey cat, and why."])
        if model.vision {
            // A photo: the vision tower is read from disk for it, then put back.
            let name = "bench-red.jpg"
            let red = UIGraphicsImageRenderer(size: CGSize(width: 256, height: 256)).image { context in
                UIColor.red.setFill(); context.fill(CGRect(x: 0, y: 0, width: 256, height: 256))
            }
            try? red.jpegData(compressionQuality: 0.9)?.write(to: files.images.appendingPathComponent(name))
            let floor = BenchFloor()
            let sampler = Task { while !Task.isCancelled { await floor.sample(); try? await Task.sleep(for: .milliseconds(50)) } }
            let reply = try? await engine.respond(model: model, directory: directory, system: InferenceEngine.defaultSystemPrompt, messages: [ChatMessage(role: .user, text: "What single colour fills this image? One word.", imageName: name)], imagesDirectory: files.images, update: { _, _ in }, phase: { _ in })
            sampler.cancel()
            print("BENCH PHOTO reply=\(reply?.text.prefix(40) ?? "failed") minFree=\(await floor.lowest)MB")
            try? await Task.sleep(for: .seconds(1))
            print("BENCH after photo free=\(MemoryHeadroom.megabytes)MB")
            try? FileManager.default.removeItem(at: files.images.appendingPathComponent(name))
        }
        await engine.unload()
        print("BENCH done")
    }

    /// Runs the prompts at once and prints wall time, total tokens and the lowest free memory.
    private func benchPass(_ label: String, model: ModelChoice, directory: URL, prompts: [String]) async {
        let start = Date()
        let floor = BenchFloor()
        let sampler = Task { while !Task.isCancelled { await floor.sample(); try? await Task.sleep(for: .milliseconds(50)) } }
        let engine = engine, images = files.images
        let results = await withTaskGroup(of: Result<InferenceResult, Error>.self) { group in
            for prompt in prompts {
                group.addTask {
                    do { return .success(try await engine.respond(model: model, directory: directory, system: InferenceEngine.defaultSystemPrompt, messages: [ChatMessage(role: .user, text: prompt)], imagesDirectory: images, update: { _, _ in }, phase: { _ in })) }
                    catch { return .failure(error) }
                }
            }
            var all: [Result<InferenceResult, Error>] = []
            for await result in group { all.append(result) }
            return all
        }
        sampler.cancel()
        let seconds = Date().timeIntervalSince(start)
        let tokens = results.reduce(0) { $0 + ((try? $1.get().tokens) ?? 0) }
        let failures = results.compactMap { if case .failure(let error) = $0 { return error.localizedDescription } else { return nil } }
        print("BENCH \(label) n=\(prompts.count) wall=\(String(format: "%.1f", seconds))s tokens=\(tokens) aggregate=\(String(format: "%.2f", Double(tokens) / max(seconds, 0.001)))tok/s minFree=\(await floor.lowest)MB failures=\(failures)")
    }
    #endif

    #if DEBUG && targetEnvironment(simulator)
    /// Simulator-only sample transcript for UI review (`-uiDemo` launch argument). Never saved.
    func loadDemo() {
        let day: TimeInterval = 86_400
        let thinking = Conversation(createdAt: .now, messages: [
            ChatMessage(role: .user, text: "What’s a good way to start journaling again after a long break?"),
            ChatMessage(role: .assistant, text: "Start smaller than feels useful. **Two lines a night** is enough: one thing that happened, one thing you noticed about it.\n\nIf a blank page stalls you, keep a single prompt for a week — *what took more energy than expected today?* — and let the habit come before the depth."),
            Self.demoAsk,
            ChatMessage(role: .assistant, text: "", status: .generating),
        ])
        let sum = ToolUse(kind: "calc", icon: "function", running: "Calculating", done: "Calculated", detail: "18% of 64.50 = 11.61", status: .done,
                          widget: .calculation(expression: "18% of 64.50", result: "11.61"))
        conversations = [
            thinking,
            Conversation(createdAt: .now.addingTimeInterval(-1_800), messages: [ChatMessage(role: .user, text: "What’s an 18% tip on 64.50?"), ChatMessage(role: .assistant, text: "An 18% tip on 64.50 is **11.61**, so 76.11 in all.", tools: [sum])]),
            Conversation(createdAt: .now.addingTimeInterval(-3_600), messages: [ChatMessage(role: .user, text: "Explain what’s happening in this photo.", imageName: demoPhoto()), ChatMessage(role: .assistant, text: "A heron standing in shallow water at dusk, mid-strike.")]),
            Conversation(createdAt: .now.addingTimeInterval(-day), messages: [ChatMessage(role: .user, text: "Draft a short, warm note to my landlord"), ChatMessage(role: .assistant, text: "Hi Sam — thanks for sorting the boiler so quickly.")]),
            Conversation(createdAt: .now.addingTimeInterval(-4 * day), messages: [ChatMessage(role: .user, text: "Help me think through an idea: a tiny weekly newsletter"), ChatMessage(role: .assistant, text: "Begin with who it’s for, then the one thing they’d miss.")]),
            Conversation(createdAt: .now.addingTimeInterval(-20 * day), messages: [ChatMessage(role: .user, text: "Compare two ways to brew cold coffee"), ChatMessage(role: .assistant, text: "Immersion is forgiving; drip is brighter.")]),
        ]
        selectedID = thinking.id
        isDemo = true
        let brief = Errand(title: "Morning brief", instructions: ErrandIdea.all[0].instructions, cadence: .daily, hour: 7, minute: 30, weekday: 2, sources: [Connector.calendar.id, Connector.reminders.id], lastRun: .now)
        let week = Errand(title: "Plan my week", instructions: ErrandIdea.all[1].instructions, cadence: .weekly, hour: 8, minute: 0, weekday: 2, sources: [Connector.calendar.id], lastRun: .now)
        desk.loadSamples([brief, week], runs: [
            ErrandRun(errandID: brief.id, title: "Morning brief", instructions: brief.instructions, sources: brief.sources, startedAt: .now.addingTimeInterval(-5_400),
                      text: "**Tuesday, gently.**\n\nTwo things on the calendar: *design review at 10:00* and *dinner with Léa at 19:30*.\n\nDue today: renew the bike insurance. Start there, it takes ten minutes.", status: .done),
            ErrandRun(title: "Sort my reminders", instructions: ErrandIdea.all[2].instructions, sources: [Connector.reminders.id], startedAt: .now.addingTimeInterval(-day - 2_000),
                      text: "**Reminders, sorted.**\n\n**Today** · call the dentist, renew insurance\n**This week** · return the parcel, book train\n**Someday** · learn to fix the bike\n\nDrop: “check that thing”, it has no next step.", status: .done),
            ErrandRun(errandID: week.id, title: "Plan my week", instructions: week.instructions, sources: week.sources, startedAt: .now.addingTimeInterval(-3 * day),
                      text: "**Your week.**\n\nMonday is light. Wednesday is tight: three meetings back to back, so prepare the review notes on Tuesday.", status: .done),
        ])
        streamDemo(conversationID: thinking.id, replyID: thinking.messages[3].id)
        if ProcessInfo.processInfo.arguments.contains("-uiDemoAttach") {
            attachmentName = demoPhoto(named: "demo-attach.jpg")
            attachedFiles = [AttachedFile(name: "trip-notes.md", text: "Sunday: brunch, then the canal walk.")]
        }
        if personas.isEmpty {
            personas = [
                Persona(name: "Editor", icon: "pencil.line", prompt: "You are a sharp, kind editor. Tighten prose, keep the author's voice, and explain each change in one line."),
                Persona(name: "Coach", icon: "flame", prompt: "You are a direct coach. Ask one clarifying question, then give a concrete next step."),
            ]
        }
    }

    /// A portrait gradient standing in for a photo, to check how attached images sit.
    private func demoPhoto(named name: String = "demo-photo.jpg") -> String {
        let size = CGSize(width: 900, height: 1200)
        let image = UIGraphicsImageRenderer(size: size).image { context in
            let colors = [UIColor(red: 0.95, green: 0.62, blue: 0.42, alpha: 1).cgColor, UIColor(red: 0.28, green: 0.32, blue: 0.55, alpha: 1).cgColor]
            let gradient = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: colors as CFArray, locations: [0, 1])!
            context.cgContext.drawLinearGradient(gradient, start: .zero, end: CGPoint(x: 0, y: size.height), options: [])
        }
        try? image.jpegData(compressionQuality: 0.8)?.write(to: files.images.appendingPathComponent(name))
        return name
    }

    /// A prompt with islands in it: Maps where the place matters, Calendar where the time does.
    private static let demoAsk: ChatMessage = {
        let maps = Island.connector(.maps), calendar = Island.connector(.calendar)
        var ask = ChatMessage(role: .user, text: "Find a quiet café \(maps.marker) for Sunday morning that doesn’t clash with \(calendar.marker)")
        ask.islands = [maps, calendar]
        return ask
    }()

    /// Loops a fake streamed reply so the handwriting effect can be seen without a model.
    private func streamDemo(conversationID: UUID, replyID: UUID) {
        let reply = "Two quiet spots close by, both open from 8:00 on Sunday:\n\n**Café Lomi** is the calmer one, with big tables at the back. **Ten Belles** is nearer but fills up after 10. You’re free until **11:30**, so either works if you go early."
        let pins = [
            ToolWidget.Pin(name: "Café Lomi", detail: "650 m", latitude: 48.8899, longitude: 2.3517, url: URL(string: "https://maps.apple.com/?q=Cafe%20Lomi")),
            ToolWidget.Pin(name: "Ten Belles", detail: "400 m", latitude: 48.8722, longitude: 2.3631, url: URL(string: "https://maps.apple.com/?q=Ten%20Belles")),
            ToolWidget.Pin(name: "Le Peloton", detail: "1.2 km", latitude: 48.8556, longitude: 2.3562, url: URL(string: "https://maps.apple.com/?q=Le%20Peloton")),
        ]
        let here = ToolWidget.Pin(name: "You", detail: "", latitude: 48.8760, longitude: 2.3590)
        Task { @MainActor [weak self] in
            while let self {
                self.setDemoReply(conversationID, replyID, text: "", status: .generating)
                self.setDemoTools(conversationID, replyID, [])
                self.phases[replyID] = "Loading model"
                try? await Task.sleep(for: .seconds(1.8))
                self.phases[replyID] = "Thinking"
                try? await Task.sleep(for: .seconds(1.2))
                var calendar = Connector.calendar.use
                self.setDemoTools(conversationID, replyID, [calendar])
                try? await Task.sleep(for: .seconds(1.2))
                calendar.status = .done
                var maps = ToolUse(kind: "places", icon: "mappin.and.ellipse", running: "Looking around you", done: "Searched Apple Maps", detail: "quiet café")
                self.setDemoTools(conversationID, replyID, [calendar, maps])
                try? await Task.sleep(for: .seconds(2))
                maps.status = .done
                maps.detail = "3 places · nearest 400 m"
                maps.links = pins.map { ToolUse.Link(title: "\($0.name) · \($0.detail)", url: $0.url!) }
                maps.widget = .map(title: "quiet café", pins: pins, here: here)
                self.setDemoTools(conversationID, replyID, [calendar, maps])
                try? await Task.sleep(for: .seconds(0.8))
                self.phases[replyID] = "Generating"
                var text = ""
                var rest = Substring(reply)
                while !rest.isEmpty {
                    let chunk = rest.prefix(Int.random(in: 2...7))
                    rest = rest.dropFirst(chunk.count)
                    text += chunk
                    self.setDemoReply(conversationID, replyID, text: text, status: .generating)
                    try? await Task.sleep(for: .milliseconds(Int.random(in: 60...110)))
                }
                self.setDemoReply(conversationID, replyID, text: text, status: .complete)
                try? await Task.sleep(for: .seconds(5))
            }
        }
    }

    /// A scripted errand run, so the Errands pages can be reviewed without a model.
    private func demoErrand(_ run: ErrandRun) {
        let replies = [
            "**Done.**\n\nHere’s what I found, laid out so you can act on it in a minute.\n\n- The most urgent item is first.\n- Everything else can wait until tomorrow.",
            "**Sorted.**\n\nThree things matter today. The rest is noise, so I left it out.",
            "**All set.**\n\nShort version: you’re free after 16:00, and nothing clashes this week.",
        ]
        let reply = replies[abs(run.id.hashValue) % replies.count]
        working.insert(run.id)
        desk.begin(run.id)
        let gathered = errandContexts[run.id]
        Task { @MainActor [weak self] in
            guard let self else { return }
            _ = await gathered?.value
            self.desk.setStage(run.id, "Thinking")
            try? await Task.sleep(for: .seconds(1))
            self.desk.setStage(run.id, "Writing")
            var text = "", rest = Substring(reply), count = 0
            while !rest.isEmpty {
                guard self.desk.runs.first(where: { $0.id == run.id })?.status == .running else { break }
                let chunk = rest.prefix(Int.random(in: 2...6))
                rest = rest.dropFirst(chunk.count)
                text += chunk
                count += 1
                self.desk.update(run.id, text: text, tokens: count * 4)
                try? await Task.sleep(for: .milliseconds(Int.random(in: 60...110)))
            }
            if self.desk.runs.first(where: { $0.id == run.id })?.status == .running { self.desk.finish(run.id, status: .done) }
            self.errandContexts[run.id] = nil
            self.working.remove(run.id)
            self.pumpErrands()
        }
    }

    private func setDemoReply(_ conversationID: UUID, _ replyID: UUID, text: String, status: ChatMessage.Status) {
        guard let ci = conversations.firstIndex(where: { $0.id == conversationID }), let mi = conversations[ci].messages.firstIndex(where: { $0.id == replyID }) else { return }
        conversations[ci].messages[mi].text = text
        conversations[ci].messages[mi].status = status
    }

    private func setDemoTools(_ conversationID: UUID, _ replyID: UUID, _ tools: [ToolUse]) {
        guard let ci = conversations.firstIndex(where: { $0.id == conversationID }), let mi = conversations[ci].messages.firstIndex(where: { $0.id == replyID }) else { return }
        conversations[ci].messages[mi].tools = tools.isEmpty ? nil : tools
    }
    #endif

    // MARK: Message actions

    /// Put a sent prompt back in the composer and rewind the chat to just before it.
    func edit(_ message: ChatMessage) {
        guard !isBusy, let ci = conversations.firstIndex(where: { $0.id == selectedID }),
              let mi = conversations[ci].messages.firstIndex(where: { $0.id == message.id }) else { return }
        // Tools come back as islands; the photo and files don't come back, so neither do theirs.
        let kept = (message.islands ?? []).filter { $0.kind == .connector }
        var text = message.text
        for island in message.islands ?? [] where island.kind != .connector { text = text.replacingOccurrences(of: island.marker, with: "") }
        draftIslands = kept
        draft = text.replacingOccurrences(of: " {2,}", with: " ", options: .regularExpression).trimmingCharacters(in: .whitespaces)
        activeConnectors = Set(kept.map(\.ref))
        for name in conversations[ci].messages[mi...].compactMap(\.imageName) {
            try? FileManager.default.removeItem(at: files.images.appendingPathComponent(name))
        }
        conversations[ci].messages.removeSubrange(mi...)
        performance = ""
        save()
    }

    /// Replace the last reply with a fresh one.
    func regenerate() {
        guard !isBusy, download.isReady, let ci = conversations.firstIndex(where: { $0.id == selectedID }),
              conversations[ci].messages.last?.role == .assistant else { return }
        conversations[ci].messages.removeLast()
        guard conversations[ci].messages.last?.role == .user else { save(); return }
        beginReply(in: ci)
    }

    func canRegenerate(_ message: ChatMessage) -> Bool {
        !isBusy && download.isReady && current.messages.last?.id == message.id && message.role == .assistant
    }

    // MARK: Attachments and connectors

    /// Read a picked file as text (PDFs via PDFKit), trimmed to fit the phone context.
    func attachFile(_ url: URL) {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        var text: String?
        if url.pathExtension.lowercased() == "pdf" {
            text = PDFDocument(url: url)?.string
        } else if let data = try? Data(contentsOf: url) {
            text = String(data: data, encoding: .utf8) ?? String(data: data, encoding: .isoLatin1)
            if let candidate = text, candidate.contains("\u{0}") { text = nil } // binary
        }
        guard let body = text?.trimmingCharacters(in: .whitespacesAndNewlines), !body.isEmpty else {
            error = "“\(url.lastPathComponent)” has no readable text. Text, Markdown, code, CSV, JSON and PDF files work."
            return
        }
        let limit = 4_000
        let clipped = body.count > limit ? String(body.prefix(limit)) + "\n[…file truncated to fit the phone context]" : body
        attachedFiles.append(AttachedFile(name: url.lastPathComponent, text: clipped))
    }

    func toggleConnector(_ connector: Connector) async {
        if activeConnectors.contains(connector.id) { activeConnectors.remove(connector.id); return }
        guard await ConnectorHub.requestAccess(for: connector.id) else {
            error = "Allow Loocal to access \(connector.name) in Settings to use it."
            return
        }
        activeConnectors.insert(connector.id)
    }

    /// Connect to an MCP server, list its tools and keep it (token in the Keychain).
    func addMCPServer(_ address: String, token: String) async {
        let trimmed = address.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let url = URL(string: trimmed.contains("://") ? trimmed : "https://" + trimmed), url.scheme == "https" || url.host() == "localhost" else {
            error = "Enter the server’s https URL."
            return
        }
        do {
            let found = try await MCPClient(url: url, token: token.isEmpty ? nil : token).discover()
            let server = MCPServer(url: url, name: found.name, tools: found.tools)
            Keychain.set(token, for: server.id.uuidString)
            mcpServers.append(server)
            activeConnectors.insert(server.connectorID)
            notice = "Connected to \(found.name) · \(found.tools.count) tools."
        } catch {
            self.error = "Couldn’t connect to the MCP server. \(error.localizedDescription)"
        }
    }

    func removeMCPServer(_ server: MCPServer) {
        Keychain.set(nil, for: server.id.uuidString)
        mcpServers.removeAll { $0.id == server.id }
        activeConnectors.remove(server.connectorID)
    }

    // MARK: Voice mode

    /// A reply from the selected local model, for voice mode when Apple Intelligence is off.
    func localReply(_ messages: [ChatMessage], system: String, update: @escaping @Sendable (String) async -> Void) async throws -> String {
        guard download.isReady else { throw IslandError.message("Download a model first, or turn on Apple Intelligence.") }
        guard !isBusy else { throw IslandError.message("Loocal is already answering in the chat.") }
        var system = system
        let question = messages.last(where: { $0.role == .user })?.text ?? ""
        let context = await voiceContext(for: question)
        if !context.isEmpty { system += "\n\n" + context }
        let result = try await engine.respond(model: model, directory: try download.loadingDirectory(), system: system, messages: messages, imagesDirectory: files.images, update: { text, _ in await update(text) }, phase: { _ in })
        return result.text
    }

    /// What the app around the model can do, so it never claims it can't look things up.
    static let capabilities = "You run inside Loocal, on the user's iPhone. Loocal itself can: search the web and read links, find places near the user and travel times with Apple Maps (using their location), do exact maths and date counts, translate, read the user's calendar and reminders and add to them, run their Shortcuts, and remember things. When Loocal has done one of these for this message, its results are above: use them. Never say you have no location, internet or calendar access. Anything about the user's own life (their events, reminders, plans) must come from results above: never make it up. If they ask about their calendar and no calendar is above, say Loocal couldn't read it. If a request needs one of these and no result is above, ask them to say it plainly, for example “find restaurants near me”."

    /// Everything Loocal works out before a spoken answer, whichever model gives it:
    /// actions done, tool results, memory and web results.
    func voiceContext(for question: String) async -> String {
        var parts: [String] = []
        if let action = ToolAction.detect(question) {
            var list = memories
            let (_, failure) = await ActionRunner.perform(action, memories: &list)
            if action.kind == .remember || action.kind == .forget { memories = list }
            parts.append(action.note(ok: failure == nil, error: failure))
        }
        let found = await ToolBelt.run(for: question) { _ in }
        if !found.isEmpty { parts.append(found) }
        let (asked, refusal) = await ConnectorHub.resolve(SourceIntent.wanted(question), ask: false)
        if !asked.isEmpty { parts.append(await ConnectorHub.context(for: asked, prompt: question)) }
        if !refusal.isEmpty { parts.append(refusal) }
        if (activeConnectors.contains(Connector.web.id) && WebSearch.wantsSearch(question)) || WebSearch.explicitlyAsked(question) {
            let query = WebSearch.searchQuery(from: question)
            parts.append((await Self.call(WebSearch.definition, arguments: WebSearch.arguments(for: query))).output + "\nAnswer in a few spoken sentences.")
        }
        if memoryOn, !memories.isEmpty { parts.append(Memory.prompt(memories)) }
        parts.append(Self.capabilities)
        return parts.joined(separator: "\n\n")
    }

    /// Voice turns are kept in the current chat, like typed ones.
    func recordVoiceTurn(user: String, reply: String) {
        guard !isBusy, !isDemo, let index = conversations.firstIndex(where: { $0.id == selectedID }) else { return }
        conversations[index].append(ChatMessage(role: .user, text: user))
        conversations[index].messages.append(ChatMessage(role: .assistant, text: reply))
        save()
    }

    func endVoice() {
        if !isWorking { Task { await engine.unload() } }
        Task { await KokoroEngine.shared.unload() }
    }

    func savePersona(_ persona: Persona) {
        if let index = personas.firstIndex(where: { $0.id == persona.id }) { personas[index] = persona }
        else if personas.count < Persona.limit { personas.append(persona) }
    }

    func deletePersona(_ persona: Persona) {
        personas.removeAll { $0.id == persona.id }
        if personaID == persona.id { personaID = nil }
    }

    func delete(_ conversation: Conversation) {
        if replying.contains(conversation.id) {
            for message in conversation.messages where message.status == .generating { stopJob(message.id, reason: "") }
            replying.remove(conversation.id)
        }
        for name in conversation.messages.compactMap(\.imageName) {
            try? FileManager.default.removeItem(at: files.images.appendingPathComponent(name))
        }
        conversations.removeAll { $0.id == conversation.id }
        if conversations.isEmpty { conversations = [Conversation()] }
        if !conversations.contains(where: { $0.id == selectedID }) { selectedID = conversations[0].id }
        save()
    }

    func send() {
        guard canSend, let index = conversations.firstIndex(where: { $0.id == selectedID }) else { return }
        let prompt = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard prompt.count <= 6000 else { error = "Shorten your prompt to 6,000 characters or fewer for the phone context."; return }
        let fallback = attachedFiles.isEmpty ? "Describe this image." : "Summarize the attached file."
        let context = attachedFiles.isEmpty ? nil : attachedFiles.map { "Attached file “\($0.name)”:\n\($0.text)" }.joined(separator: "\n\n")
        let islands = IslandText.present(in: prompt, draftIslands)
        let hasAttachment = attachmentName != nil || !attachedFiles.isEmpty
        // Only islands, no words: the attachment's usual question follows them.
        let text = IslandText.stripped(prompt).isEmpty && hasAttachment ? (prompt.isEmpty ? fallback : prompt + " " + fallback) : prompt
        var message = ChatMessage(role: .user, text: text, imageName: attachmentName,
                                  files: attachedFiles.isEmpty ? nil : attachedFiles.map(\.name), context: context)
        message.islands = islands.isEmpty ? nil : islands
        attachedFiles = []
        draftIslands = []
        activeConnectors = []
        conversations[index].append(message)
        draft = ""
        attachmentName = nil
        beginReply(in: index)
    }

    /// Answer the conversation's last message (a new prompt, or a regenerated one). Other
    /// chats and errands can be writing at the same time; the model takes turns between them.
    private func beginReply(in index: Int) {
        let history = conversations[index].messages
        let replyID = UUID()
        let conversationID = conversations[index].id
        conversations[index].messages.append(ChatMessage(id: replyID, role: .assistant, text: "", status: .generating))
        performance = ""
        error = nil
        let model = model, system = systemPrompt
        let directory: URL
        do { directory = try download.loadingDirectory() } catch {
            conversations[index].messages.removeLast()
            self.error = "The model files couldn’t be prepared: \(error.localizedDescription)"
            return
        }
        replying.insert(conversationID)
        save()
        notice = nil
        launch(replyID, model: model, phase: "Requesting background access", expire: "iOS ended the background task. Your partial reply is saved.") { [weak self] in
            self?.run(model: model, directory: directory, system: system, history: history, conversationID: conversationID, replyID: replyID)
        }
    }

    // MARK: Jobs

    /// Starts a job (a reply or an errand). The first one asks iOS for background time and
    /// puts up the Live Activity; the ones that join it share both.
    private func launch(_ id: UUID, model: ModelChoice, phase: String, expire: String, _ body: @escaping () -> Void) {
        unloadTask?.cancel()
        let first = working.isEmpty
        working.insert(id)
        phases[id] = phase
        jobTokens[id] = 0
        activity.modelName = model.name
        continued.modelName = model.name
        let start = { [weak self] in
            guard let self, self.working.contains(id) else { return } // stopped before it began
            if !self.continued.hasGrant && !self.activity.isRunning { self.activity.start() }
            body()
        }
        if first {
            continued.begin(onLaunch: start, onExpire: { [weak self] in self?.stopAll(reason: expire) })
        } else {
            continued.join(start)
        }
    }

    /// A job is over. When it was the last one, background time and the Live Activity end.
    private func endJob(_ id: UUID, success: Bool, finalPhase: String) {
        let total = jobTokens.values.reduce(0, +)
        jobs[id] = nil
        working.remove(id)
        phases[id] = nil
        jobTokens[id] = nil
        stopReasons[id] = nil
        if working.isEmpty {
            continued.finish(success: success)
            activity.finish(phase: finalPhase, count: total)
            endGrace()
        } else {
            reportProgress(force: true)
        }
        save()
        // Freed room goes to the oldest waiting errand; the model is released once all is quiet.
        pumpErrands()
        if working.isEmpty { releaseModelSoon() }
    }

    /// One line for the island and iOS's progress, however many jobs are writing.
    private func reportProgress(force: Bool = false) {
        let count = jobTokens.values.reduce(0, +)
        let phase = working.count > 1 ? "Writing \(working.count) at once" : (working.first.flatMap { phases[$0] } ?? "Generating")
        continued.update(phase: ComputeTarget.useCPU && working.count == 1 ? "Writing in background" : phase, count: count)
        Task { await activity.update(phase: phase, count: count, force: force) }
    }

    private func run(model: ModelChoice, directory: URL, system: String, history: [ChatMessage], conversationID: UUID, replyID: UUID) {
        let prompt = history.last(where: { $0.role == .user })
        // Tools spot what's asked from the words alone; the model reads islands by name.
        let question = IslandText.stripped(prompt?.text ?? "")
        let placed = Set((prompt?.islands ?? []).filter { $0.kind == .connector }.map(\.ref))
        // Asking for a search in so many words turns it on for this reply.
        let active = WebSearch.explicitlyAsked(question) ? placed.union([Connector.web.id]) : placed
        let history = history.map { message -> ChatMessage in
            guard let islands = message.islands, !islands.isEmpty else { return message }
            var spoken = message
            spoken.text = IslandText.spoken(message.text, islands)
            return spoken
        }
        let tools = toolDefinitions(for: active)
        jobs[replyID] = Task {
            var success = false
            do {
                // "Check my calendar" reads the calendar even without an island; a source that
                // isn't allowed is named to the model so it says so instead of inventing.
                let (asked, refusal) = await ConnectorHub.resolve(SourceIntent.wanted(question).subtracting(active), ask: true)
                let active = active.union(asked)
                // Built-in connectors are read right before the reply and join the system prompt.
                var system = system
                if !refusal.isEmpty { system += "\n\n" + refusal }
                let read = Connector.builtIn.filter { active.contains($0.id) && $0.id != Connector.web.id && $0.id != Connector.maps.id }
                if !read.isEmpty {
                    await setPhase(replyID, "Checking your sources")
                    let boxes = read.map(\.use)
                    for box in boxes { addTool(box, conversationID, replyID) }
                    let context = await ConnectorHub.context(for: active, prompt: question)
                    for box in boxes { finishTool(box.id, conversationID, replyID, failed: box.kind == Connector.wikipedia.id && !context.contains("Wikipedia —")) }
                    if !context.isEmpty { system += "\n\n" + context }
                }
                // Something to do, not just answer: done first, then the reply confirms it.
                if let action = ToolAction.detect(question) {
                    await setPhase(replyID, action.box.running)
                    system += "\n\n" + (await act(action, conversationID: conversationID, replyID: replyID))
                }
                // Maths, dates, places, a linked page, translation: worked out for the model.
                let found = await ToolBelt.run(for: question, force: active) { box in self.upsertTool(box, conversationID, replyID) }
                if !found.isEmpty { system += "\n\n" + found }
                system += "\n\n" + Self.capabilities
                if memoryOn, !memories.isEmpty { system += "\n\n" + Memory.prompt(memories) }
                if active.contains(Connector.web.id) {
                    system += "\n\n" + WebSearch.guidance
                    if WebSearch.wantsSearch(question) {
                        await setPhase(replyID, "Searching the web")
                        let query = WebSearch.searchQuery(from: question)
                        system += "\n\n" + (await useTool(WebSearch.definition, arguments: WebSearch.arguments(for: query), conversationID: conversationID, replyID: replyID))
                    }
                }
                // A typed reply doesn't need the natural voice engine (~330 MB) resident.
                await KokoroEngine.shared.unload()
                let result = try await engine.respond(model: model, directory: directory, system: system, messages: history, imagesDirectory: files.images, tools: tools, callTool: { definition, arguments in
                    await self.useTool(definition, arguments: arguments, conversationID: conversationID, replyID: replyID)
                }, update: { text, count in
                    await self.received(text, count: count, conversationID: conversationID, replyID: replyID)
                }, phase: { phase in await self.setPhase(replyID, phase) })
                try Task.checkCancellation()
                if conversationID == selectedID {
                    performance = "\(result.tokens) tokens · \(String(format: "%.1f", Double(result.tokens) / max(result.seconds, 0.001))) tok/s · \(result.headroom) MB free"
                }
                markReply(conversationID, replyID, status: .complete)
                success = true
            } catch {
                markReply(conversationID, replyID, status: .interrupted)
                if Task.isCancelled {
                    let reason = stopReasons[replyID] ?? "Generation stopped. Your partial reply is saved."
                    if !reason.isEmpty { notice = reason }
                }
                else if case IslandError.lowMemory = error { notice = error.localizedDescription }
                else { self.error = error.localizedDescription }
            }
            replying.remove(conversationID)
            endJob(replyID, success: success, finalPhase: success ? "Reply ready" : "Stopped")
        }
    }

    private func received(_ text: String, count: Int, conversationID: UUID, replyID: UUID) async {
        if let ci = conversations.firstIndex(where: { $0.id == conversationID }), let mi = conversations[ci].messages.firstIndex(where: { $0.id == replyID }) {
            conversations[ci].messages[mi].text = text
        }
        jobTokens[replyID] = count
        reportProgress()
        if Date().timeIntervalSince(lastSaved) > 1 { save(); lastSaved = .now }
    }

    private func setPhase(_ id: UUID, _ value: String) async {
        guard working.contains(id) else { return }
        phases[id] = value
        reportProgress(force: true)
    }

    private func markReply(_ conversationID: UUID, _ replyID: UUID, status: ChatMessage.Status) {
        guard let ci = conversations.firstIndex(where: { $0.id == conversationID }), let mi = conversations[ci].messages.firstIndex(where: { $0.id == replyID }) else { return }
        conversations[ci].messages[mi].status = status
        // A reply that stops mid-tool leaves no box spinning.
        if status != .generating, let tools = conversations[ci].messages[mi].tools {
            conversations[ci].messages[mi].tools = tools.map { var use = $0; if use.status == .running { use.status = .failed }; return use }
        }
    }

    // MARK: Tools

    /// The tools a reply can call: web search when it's on, plus each active MCP server's.
    func toolDefinitions(for active: Set<String>) -> [ToolDefinition] {
        (active.contains(Connector.web.id) ? [WebSearch.definition] : []) + mcpServers.filter { active.contains($0.connectorID) }.flatMap { server in
            server.tools.prefix(8).map { ToolDefinition(url: server.url, token: Keychain.get(server.id.uuidString), tool: $0) }
        }
    }

    /// Runs a tool for the model: web search on device, anything else on its MCP server.
    nonisolated static func call(_ definition: ToolDefinition, arguments: String) async -> (output: String, links: [ToolUse.Link]) {
        if WebSearch.isLocal(definition) {
            let query = WebSearch.query(from: arguments)
            let hits = await WebSearch.search(query)
            return (WebSearch.report(hits, for: query), hits.prefix(5).map { ToolUse.Link(title: $0.title, url: $0.url) })
        }
        do { return (try await MCPClient(url: definition.url, token: definition.token).run(definition.tool.name, argumentsJSON: arguments), []) }
        catch { return ("The tool could not be reached: \(error.localizedDescription)", []) }
    }

    /// Calls a tool and keeps its box in the reply up to date.
    private func useTool(_ definition: ToolDefinition, arguments: String, conversationID: UUID, replyID: UUID) async -> String {
        let local = WebSearch.isLocal(definition)
        let host = definition.url.host() ?? "server"
        var box = local
            ? ToolUse(kind: Connector.web.id, icon: "magnifyingglass", running: "Searching the web", done: "Searched the web", detail: WebSearch.query(from: arguments))
            : ToolUse(kind: "mcp", icon: "server.rack", running: "Using \(definition.tool.name)", done: "Used \(definition.tool.name)", detail: host)
        addTool(box, conversationID, replyID)
        let result = await Self.call(definition, arguments: arguments)
        box.links = result.links
        let failed = result.output.hasPrefix("The tool could not") || result.output.hasPrefix("Tool error") || (local && result.links.isEmpty)
        finishTool(box.id, conversationID, replyID, failed: failed, links: result.links)
        // Snippets are thin: read the top result's page too, so the answer has substance.
        if local, let top = result.links.first {
            var read = ToolUse(kind: "page", icon: "doc.text", running: "Reading the top result", done: "Read the top result", detail: top.host)
            addTool(read, conversationID, replyID)
            if let page = await PageReader.read(top.url, limit: 900) {
                read.status = .done
                read.links = [top]
                replaceTool(read, conversationID, replyID)
                return result.output + "\n\nFrom the top result (\(top.host)):\n" + page.text
            }
            read.status = .failed
            replaceTool(read, conversationID, replyID)
        }
        return result.output
    }

    /// Carries out an action from the chat and shows it as a box with Undo. Returns the
    /// note for the model.
    private func act(_ action: ToolAction, conversationID: UUID, replyID: UUID) async -> String {
        var box = action.box
        addTool(box, conversationID, replyID)
        var list = memories
        let (done, error) = await ActionRunner.perform(action, memories: &list)
        if action.kind == .remember || action.kind == .forget { memories = list }
        box.action = done
        box.detail = action.kind == .forget ? done.title : box.detail
        box.status = error == nil ? .done : .failed
        if let error { box.detail = error }
        replaceTool(box, conversationID, replyID)
        return action.note(ok: error == nil, error: error)
    }

    /// Undo from a box in a reply: removes the reminder or event, or puts memory back.
    func undoAction(_ toolID: UUID) {
        for ci in conversations.indices {
            for mi in conversations[ci].messages.indices {
                guard let ti = conversations[ci].messages[mi].tools?.firstIndex(where: { $0.id == toolID }),
                      let action = conversations[ci].messages[mi].tools?[ti].action else { continue }
                var list = memories
                if ActionRunner.undo(action, memories: &list) {
                    memories = list
                    conversations[ci].messages[mi].tools?[ti].status = .undone
                    save()
                } else {
                    error = "Couldn’t undo that. It may already be gone."
                }
                return
            }
        }
    }

    /// Adds the box, or updates it if it's already in the reply.
    private func upsertTool(_ use: ToolUse, _ conversationID: UUID, _ replyID: UUID) {
        guard let ci = conversations.firstIndex(where: { $0.id == conversationID }), let mi = conversations[ci].messages.firstIndex(where: { $0.id == replyID }) else { return }
        if let ti = conversations[ci].messages[mi].tools?.firstIndex(where: { $0.id == use.id }) {
            conversations[ci].messages[mi].tools?[ti] = use
        } else {
            conversations[ci].messages[mi].tools = (conversations[ci].messages[mi].tools ?? []) + [use]
        }
    }

    private func replaceTool(_ use: ToolUse, _ conversationID: UUID, _ replyID: UUID) {
        guard let ci = conversations.firstIndex(where: { $0.id == conversationID }), let mi = conversations[ci].messages.firstIndex(where: { $0.id == replyID }),
              let ti = conversations[ci].messages[mi].tools?.firstIndex(where: { $0.id == use.id }) else { return }
        conversations[ci].messages[mi].tools?[ti] = use
    }

    private func addTool(_ use: ToolUse, _ conversationID: UUID, _ replyID: UUID) {
        guard let ci = conversations.firstIndex(where: { $0.id == conversationID }), let mi = conversations[ci].messages.firstIndex(where: { $0.id == replyID }) else { return }
        conversations[ci].messages[mi].tools = (conversations[ci].messages[mi].tools ?? []) + [use]
    }

    private func finishTool(_ id: UUID, _ conversationID: UUID, _ replyID: UUID, failed: Bool = false, links: [ToolUse.Link] = []) {
        guard let ci = conversations.firstIndex(where: { $0.id == conversationID }), let mi = conversations[ci].messages.firstIndex(where: { $0.id == replyID }),
              let ti = conversations[ci].messages[mi].tools?.firstIndex(where: { $0.id == id }) else { return }
        conversations[ci].messages[mi].tools?[ti].status = failed ? .failed : .done
        if !links.isEmpty { conversations[ci].messages[mi].tools?[ti].links = links }
    }

    // MARK: Errands

    /// How many errands can be on the go at once.
    static let crewSize = 4
    /// Each errand gathers its sources (calendar, reminders, the web) as soon as it's sent,
    /// all at once; only the writing waits its turn for the model.
    @ObservationIgnored private var errandContexts: [UUID: Task<String, Never>] = [:]

    /// Send an errand off. It joins the crew right away and runs as soon as the model is free.
    func runErrand(title: String, instructions: String, sources: [String], errandID: UUID? = nil) {
        guard desk.active.count < Self.crewSize else {
            error = "The crew’s full: \(Self.crewSize) errands are on the go. Try again when one finishes."
            return
        }
        guard download.isReady || isDemo else { error = "Download \(model.name) first. Errands run on this iPhone."; return }
        var run = ErrandRun(errandID: errandID, title: title, instructions: instructions, sources: sources)
        run.personaID = errandPersona?.id
        run.stage = sources.allSatisfy({ $0 == Connector.web.id }) ? ErrandRun.nextUp : "Gathering"
        if let errandID { desk.markRan(errandID) }
        // A one-off "remind me…" or "add to my calendar…" is simply done, no model needed.
        if errandID == nil, let action = ToolAction.detect(instructions) {
            run.stage = action.box.running
            desk.start(run)
            Task { await self.actErrand(run.id, action) }
            return
        }
        desk.start(run)
        prepare(run)
        pumpErrands()
    }

    private func actErrand(_ id: UUID, _ action: ToolAction) async {
        var list = memories
        let (done, error) = await ActionRunner.perform(action, memories: &list)
        if action.kind == .remember || action.kind == .forget { memories = list }
        if let error {
            desk.finish(id, status: .failed, note: error)
            return
        }
        let box = done.box
        desk.update(id, text: "**\(box.done).**\n\n" + [done.title, done.when].compactMap { $0 }.joined(separator: " · "))
        desk.finish(id, status: .done)
        if UIApplication.shared.applicationState != .active, let run = desk.runs.first(where: { $0.id == id }) { ErrandDesk.announce(run) }
    }

    /// The persona errands currently go out as (nil if none, or it was deleted).
    var errandPersona: Persona? { personas.first { $0.id == desk.personaID } }

    func rerun(_ run: ErrandRun) {
        runErrand(title: run.title, instructions: run.instructions, sources: run.sources)
    }

    /// Stop one errand: a waiting one just leaves the crew, the writing one stops mid-way.
    func cancelErrand(_ run: ErrandRun) {
        guard let current = desk.runs.first(where: { $0.id == run.id }), current.isActive else { return }
        if current.status == .running {
            stopJob(run.id, reason: "Errand stopped. Its partial result is saved.")
        } else {
            errandContexts.removeValue(forKey: run.id)?.cancel()
            desk.finish(run.id, status: .stopped)
        }
    }

    /// Fetches what an errand reads, in parallel with every other errand on the go.
    private func prepare(_ run: ErrandRun) {
        let picked = Set(run.sources).subtracting([Connector.web.id]) // the web is searched while writing
        let demo = isDemo
        errandContexts[run.id] = Task { [weak self] in
            guard let self else { return "" }
            // "What have I got next month" reads the calendar even if no source was picked.
            let (asked, refusal) = demo ? ([], "") : await ConnectorHub.resolve(SourceIntent.wanted(run.instructions).subtracting(picked), ask: false)
            let active = picked.union(asked)
            guard !active.isEmpty else { return refusal }
            let names = self.connectors.filter { active.contains($0.id) }.map(\.name)
            self.desk.setStage(run.id, "Reading " + ListFormatter.localizedString(byJoining: names))
            var context = ""
            if demo {
                try? await Task.sleep(for: .seconds(Double.random(in: 0.8...2.2)))
            } else {
                context = await ConnectorHub.context(for: active, prompt: run.instructions)
                if !refusal.isEmpty { context += (context.isEmpty ? "" : "\n\n") + refusal }
            }
            if self.desk.runs.first(where: { $0.id == run.id })?.status == .queued { self.desk.setStage(run.id, ErrandRun.nextUp) }
            return context
        }
    }

    /// Starts waiting errands, oldest first, while there's a free lane. The engine still only
    /// lets each one write once there's memory for it.
    func pumpErrands() {
        while working.count < InferenceEngine.maxLanes, let run = desk.runs.last(where: { $0.status == .queued }) {
            #if DEBUG && targetEnvironment(simulator)
            if isDemo { demoErrand(run); continue }
            #endif
            guard download.isReady else { return }
            let directory: URL
            do { directory = try download.loadingDirectory() } catch {
                desk.finish(run.id, status: .failed, note: "The model files couldn’t be prepared: \(error.localizedDescription)")
                continue
            }
            desk.begin(run.id)
            let model = model
            launch(run.id, model: model, phase: "Starting errand", expire: "iOS ended the background task. The errand’s partial result is saved.") { [weak self] in
                self?.performErrand(run, model: model, directory: directory)
            }
        }
    }

    /// Runs every routine whose time has come. Called on launch, on return to the app (a
    /// tapped notification lands here) and periodically while Loocal is open.
    func runDueErrands() {
        guard download.isReady, !isDemo else { return }
        let due = desk.errands.filter(\.isDue).sorted { ($0.lastRun ?? $0.createdAt) < ($1.lastRun ?? $1.createdAt) }
        for errand in due where desk.active.count < Self.crewSize && !desk.active.contains(where: { $0.errandID == errand.id }) {
            runErrand(title: errand.title, instructions: errand.instructions, sources: errand.sources, errandID: errand.id)
        }
    }

    /// Open an errand's result as a regular chat, to keep going from it.
    func continueInChat(_ run: ErrandRun) {
        newConversation()
        guard let index = conversations.firstIndex(where: { $0.id == selectedID }) else { return }
        conversations[index].append(ChatMessage(role: .user, text: run.instructions))
        conversations[index].append(ChatMessage(role: .assistant, text: run.text))
        save()
    }

    private func performErrand(_ run: ErrandRun, model: ModelChoice, directory: URL) {
        let tools = toolDefinitions(for: Set(run.sources))
        let gathered = errandContexts[run.id]
        jobs[run.id] = Task {
            var success = false
            do {
                // A persona's voice and know-how come first; the errand rules still apply.
                let persona = personas.first { $0.id == run.personaID }?.prompt.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
                var system = (persona.isEmpty ? "" : persona + "\n\n") + ErrandDesk.systemPrompt + "\n\nIt is now " + Date.now.formatted(.dateTime.weekday(.wide).day().month(.wide).hour().minute()) + "."
                if memoryOn, !memories.isEmpty { system += "\n\n" + Memory.prompt(memories) }
                let found = await ToolBelt.run(for: run.instructions) { _ in }
                if !found.isEmpty { system += "\n\n" + found }
                if let gathered {
                    desk.setStage(run.id, "Finishing reading")
                    let context = await gathered.value
                    if !context.isEmpty { system += "\n\n" + context }
                }
                if run.sources.contains(Connector.web.id) {
                    system += "\n\n" + WebSearch.guidance
                    if WebSearch.wantsSearch(run.instructions) {
                        desk.setStage(run.id, "Searching the web")
                        let query = WebSearch.searchQuery(from: run.instructions)
                        system += "\n\n" + (await Self.call(WebSearch.definition, arguments: WebSearch.arguments(for: query))).output
                    }
                }
                desk.setStage(run.id, "Thinking")
                await KokoroEngine.shared.unload()
                _ = try await engine.respond(model: model, directory: directory, system: system, messages: [ChatMessage(role: .user, text: run.instructions)], imagesDirectory: files.images, tools: tools, callTool: { definition, arguments in
                    await Self.call(definition, arguments: arguments).output
                }, update: { text, count in
                    await self.errandReceived(text, count: count, runID: run.id)
                }, phase: { phase in
                    await self.setPhase(run.id, phase)
                    await MainActor.run { self.desk.setStage(run.id, phase == "Generating" ? "Writing" : phase) }
                })
                try Task.checkCancellation()
                desk.finish(run.id, status: .done)
                success = true
                if UIApplication.shared.applicationState != .active, let done = desk.runs.first(where: { $0.id == run.id }) { ErrandDesk.announce(done) }
            } catch {
                let cancelled = Task.isCancelled
                desk.finish(run.id, status: cancelled ? .stopped : .failed, note: cancelled ? nil : error.localizedDescription)
                if cancelled {
                    let reason = stopReasons[run.id] ?? "Errand stopped. Its partial result is saved."
                    if !reason.isEmpty { notice = reason }
                }
                else if case IslandError.lowMemory = error { notice = error.localizedDescription }
            }
            errandContexts[run.id] = nil
            endJob(run.id, success: success, finalPhase: success ? "Errand done" : "Stopped")
        }
    }

    private func errandReceived(_ text: String, count: Int, runID: UUID) async {
        desk.update(runID, text: text, tokens: count)
        jobTokens[runID] = count
        reportProgress()
    }

    /// Stop the reply in the chat on screen.
    func stop(reason: String = "Generation stopped. Your partial reply is saved.") {
        guard let id = currentReplyID, working.contains(id) else { return }
        stopJob(id, reason: reason)
    }

    /// Stop everything at once (iOS took the background time back).
    func stopAll(reason: String) {
        for id in working { stopJob(id, reason: reason) }
    }

    /// Stop one reply or errand. A running one stops at its next token, keeping what it wrote;
    /// one still waiting for iOS to say go simply never starts.
    private func stopJob(_ id: UUID, reason: String) {
        guard working.contains(id) else { return }
        stopReasons[id] = reason
        phases[id] = "Stopping"
        if let job = jobs[id] { job.cancel(); return }
        for ci in conversations.indices {
            if let mi = conversations[ci].messages.firstIndex(where: { $0.id == id }) {
                conversations[ci].messages[mi].status = .interrupted
                replying.remove(conversations[ci].id)
            }
        }
        if desk.runs.contains(where: { $0.id == id }) {
            errandContexts.removeValue(forKey: id)?.cancel()
            desk.finish(id, status: .stopped)
        }
        if !reason.isEmpty { notice = reason }
        endJob(id, success: false, finalPhase: "Stopped")
    }

    /// Leaving the app no longer stops a reply. Without background GPU, generation pauses at
    /// the next token as the app starts to leave, then moves to the CPU once it is really in
    /// the background (slower, but allowed), and back to the GPU on return.
    func willResignActive() {
        if isWorking {
            if !(continued.hasGrant && continued.grantedGPU) { ComputeTarget.mode = .leaving }
            if !continued.hasGrant && graceTask == .invalid {
                // No continued-processing grant: borrow UIKit's short background window.
                // Just long enough to drain the GPU safely; the reply then waits, paused, and
                // carries on when you come back. It's no longer stopped when this runs out.
                graceTask = UIApplication.shared.beginBackgroundTask(withName: "Loocal reply") { [weak self] in
                    self?.endGrace()
                }
            }
        }
        save()
    }

    func didEnterBackground() {
        if !(continued.hasGrant && continued.grantedGPU) { ComputeTarget.mode = .background }
        // Out of sight, the model's memory goes back to iOS straight away.
        if !isWorking { unloadTask?.cancel(); Task { await engine.unload() } }
        save()
    }

    func didBecomeActive() {
        ComputeTarget.mode = .foreground
        activity.dismissFinished()
        endGrace()
        runDueErrands()
    }

    private func endGrace() {
        guard graceTask != .invalid else { return }
        UIApplication.shared.endBackgroundTask(graceTask)
        graceTask = .invalid
    }


    /// iOS sends memory warnings long before an app with a multi-gigabyte model is in real
    /// danger, so a reply keeps going; the engine watches actual headroom and stops itself
    /// cleanly if it gets genuinely low. When idle, the model is released.
    /// The model stays loaded a little after a reply, so a follow-up starts at once instead
    /// of loading 3 GB again. Any new reply cancels the countdown.
    @ObservationIgnored private var unloadTask: Task<Void, Never>?

    private func releaseModelSoon() {
        unloadTask?.cancel()
        unloadTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(90))
            guard let self, !Task.isCancelled, !self.isWorking else { return }
            await self.engine.unload()
        }
    }

    func memoryWarning() {
        unloadTask?.cancel()
        if !isWorking { Task { await engine.unload() } }
    }

    func discardAttachment() {
        if let name = attachmentName { try? FileManager.default.removeItem(at: files.images.appendingPathComponent(name)) }
        attachmentName = nil
    }

    func attachPhoto(_ data: Data) async {
        guard model.vision else { error = "\(model.name) reads words only. Pick a vision model to share photos."; return }
        let conversationID = selectedID
        do {
            let result = try await Task.detached(priority: .userInitiated) { () throws -> Data in
                guard let source = CGImageSourceCreateWithData(data as CFData, nil), let thumbnail = CGImageSourceCreateThumbnailAtIndex(source, 0, [kCGImageSourceCreateThumbnailFromImageAlways: true, kCGImageSourceThumbnailMaxPixelSize: 1024, kCGImageSourceCreateThumbnailWithTransform: true] as CFDictionary), let jpeg = UIImage(cgImage: thumbnail).jpegData(compressionQuality: 0.85) else { throw IslandError.message("That photo couldn’t be opened. Try another image.") }
                return jpeg
            }.value
            guard selectedID == conversationID, !isBusy else { return }
            // Temporary chats keep photos in their own folder, wiped when the chat ends and at launch.
            let name = (isTemporary ? LocalFiles.temporaryImages + "/" : "") + UUID().uuidString + ".jpg"
            if isTemporary { try FileManager.default.createDirectory(at: files.images.appendingPathComponent(LocalFiles.temporaryImages), withIntermediateDirectories: true) }
            try result.write(to: files.images.appendingPathComponent(name), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            discardAttachment()
            attachmentName = name
        } catch { self.error = error.localizedDescription }
    }

    func save() {
        guard !isDemo else { return }
        let kept = conversations.filter { !$0.isTemporary }
        let selected = kept.contains { $0.id == selectedID } ? selectedID : (kept.first?.id ?? selectedID)
        do { try files.save(ChatArchive(conversations: kept.isEmpty ? [Conversation()] : kept, selectedID: selected)) }
        catch { self.error = "Couldn’t save your chat: \(error.localizedDescription)" }
    }
}

#if DEBUG
/// Lowest free memory seen during a benchmark pass.
actor BenchFloor {
    private(set) var lowest = Int.max
    func sample() { lowest = min(lowest, MemoryHeadroom.megabytes) }
}

/// Timestamps for the benchmark.
actor BenchMarks {
    private var phases: [(String, Date)] = []
    private var first: Date?
    private var last: (Int, Date)?
    func phase(_ name: String) { phases.append((name, .now)); print("BENCH phase \(name)") }
    func token(_ count: Int) {
        if first == nil { first = .now }
        last = (count, .now)
        if count % 32 == 0 { print("BENCH token \(count)") }
    }
    func summary(since start: Date) -> String {
        let generating = phases.first { $0.0 == "Generating" || $0.0 == "Writing in background" }?.1
        let load = phases.first { $0.0 == "Reading prompt" }?.1.timeIntervalSince(start) ?? -1
        let prefill = first.map { $0.timeIntervalSince(generating ?? start) } ?? -1
        var speed = -1.0
        if let first, let last, last.0 > 1 { speed = Double(last.0 - 1) / max(last.1.timeIntervalSince(first), 0.001) }
        return String(format: "load=%.1fs firstToken=%.1fs speed=%.2ftok/s", load, prefill, speed)
    }
}
#endif
