import CryptoKit
import XCTest
@testable import IslandAI

final class IslandAITests: XCTestCase {
    func testInterruptedReplyRecoversWithoutLosingText() {
        var chat = Conversation(messages: [
            .init(role: .user, text: "What is this?", imageName: "photo.jpg"),
            .init(role: .assistant, text: "It looks like a", status: .generating)
        ])
        chat.recoverInterruptedReply()
        XCTAssertEqual(chat.messages[1].status, .interrupted)
        XCTAssertEqual(chat.messages[1].text, "It looks like a")
        XCTAssertEqual(chat.messages[0].imageName, "photo.jpg")
    }

    func testArchiveRoundTripPreservesSelectionAndRecoversInterruptedReply() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let files = try LocalFiles(root: root)
        let first = Conversation(messages: [.init(role: .user, text: "hello")])
        let second = Conversation(messages: [.init(role: .assistant, text: "partial", status: .generating)])
        try files.save(ChatArchive(conversations: [first, second], selectedID: second.id))
        let result = try XCTUnwrap(files.load())
        XCTAssertEqual(result.selectedID, second.id)
        XCTAssertEqual(result.conversations[0].messages, first.messages)
        XCTAssertEqual(result.conversations[1].messages[0].status, .interrupted)
    }

    func testLaunchSweepRemovesTemporaryAndOrphanedPhotosOnly() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let files = try LocalFiles(root: root)
        let temporary = files.images.appendingPathComponent(LocalFiles.temporaryImages, isDirectory: true)
        try FileManager.default.createDirectory(at: temporary, withIntermediateDirectories: true)
        for url in [files.images.appendingPathComponent("kept.jpg"), files.images.appendingPathComponent("deleted-chat.jpg"), temporary.appendingPathComponent("temp.jpg")] {
            try Data([1, 2, 3]).write(to: url)
        }
        files.sweep(keeping: ["kept.jpg"], clearScratch: false)
        let left = try FileManager.default.contentsOfDirectory(atPath: files.images.path)
        XCTAssertEqual(left, ["kept.jpg"])
        XCTAssertEqual(try root.resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup, true)
    }

    func testCheckpointOutputHeadConfigurationIsPreserved() throws {
        let rootOnly = Data(#"{"model_type":"qwen3_vl","tie_word_embeddings":false,"text_config":{}}"#.utf8)
        let normalized = try JSONSerialization.jsonObject(with: ModelCompatibility.qwenConfiguration(rootOnly)) as! [String: Any]
        XCTAssertEqual((normalized["text_config"] as? [String: Any])?["tie_word_embeddings"] as? Bool, false)
        let explicit = Data(#"{"model_type":"qwen3_vl","tie_word_embeddings":false,"text_config":{"tie_word_embeddings":true}}"#.utf8)
        let preserved = try JSONSerialization.jsonObject(with: ModelCompatibility.qwenConfiguration(explicit)) as! [String: Any]
        XCTAssertEqual((preserved["text_config"] as? [String: Any])?["tie_word_embeddings"] as? Bool, true)
        XCTAssertThrowsError(try ModelCompatibility.qwenConfiguration(Data("{}".utf8)))
    }

    func testDownloadRejectsCorruptionAndIncorrectLength() throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: url) }
        let good = Data("verified model".utf8)
        let hash = SHA256.hash(data: good).map { String(format: "%02x", $0) }.joined()
        let file = ModelSpec.File(name: "test.safetensors", size: Int64(good.count), sha256: hash)
        try good.write(to: url)
        XCTAssertNoThrow(try DownloadDelegate.verify(url, file: file))
        try Data(repeating: 0, count: good.count).write(to: url)
        XCTAssertThrowsError(try DownloadDelegate.verify(url, file: file))
        try Data().write(to: url)
        XCTAssertThrowsError(try DownloadDelegate.verify(url, file: file))
    }

    func testEveryCatalogModelHasAPinnedVerifiableManifest() throws {
        XCTAssertEqual(ModelChoice.all.map(\.id), ["qwen3-vl-4b", "qwen3-vl-8b", "gemma-3-12b"])
        XCTAssertEqual(Set(ModelChoice.all.map(\.sessionID)).count, 3, "Each model needs its own background session")
        XCTAssertEqual(ModelChoice.standard.sessionID, ModelDownload.sessionID, "The 8B keeps its original session so in-flight downloads survive updates")
        for choice in ModelChoice.all {
            let spec = try ModelSpec.bundled(choice.manifest)
            XCTAssertEqual(spec.revision.count, 40, choice.id)
            XCTAssertTrue(spec.files.contains { $0.name == "config.json" }, choice.id)
            for file in spec.files where file.name.hasSuffix(".safetensors") {
                XCTAssertEqual(file.sha256?.count, 64, "\(choice.id) \(file.name) must be hash-verified")
            }
        }
        XCTAssertFalse(ModelChoice.large.vision)
    }

    func testPersonasAreCappedAtThree() {
        let list = (0..<5).map { Persona(name: "P\($0)", icon: "leaf", prompt: "Be brief.") }
        Persona.save(list)
        XCTAssertEqual(Persona.load().count, Persona.limit)
        Persona.save([])
    }

    func testChunkPlanCoversEveryByteOnceAndStitchesBack() throws {
        let plan = DownloadPlan(chunked: true)
        let big = ModelSpec.File(name: "w.safetensors", size: DownloadPlan.chunkSize * 3 + 17, sha256: nil)
        let ranges = plan.ranges(for: big)
        XCTAssertEqual(ranges.count, 4)
        XCTAssertEqual(ranges.first?.lowerBound, 0)
        XCTAssertEqual(ranges.last?.upperBound, big.size - 1)
        XCTAssertEqual(ranges.reduce(Int64(0)) { $0 + Int64($1.count) }, big.size)
        for (a, b) in zip(ranges, ranges.dropFirst()) { XCTAssertEqual(a.upperBound + 1, b.lowerBound) }
        XCTAssertFalse(DownloadPlan(chunked: false).isSplit(big), "Legacy downloads stay single-stream")
        XCTAssertFalse(plan.isSplit(.init(name: "config.json", size: 900, sha256: nil)))
        XCTAssertEqual(DownloadPlan.parse("model.safetensors#12").part, 12)
        XCTAssertNil(DownloadPlan.parse("model.safetensors").part)

        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let payload = Data((0..<10_000).map { UInt8($0 % 251) })
        let parts = stride(from: 0, to: payload.count, by: 3_000).enumerated().map { i, start -> URL in
            let url = root.appendingPathComponent("p\(i)")
            try? payload[start..<min(start + 3_000, payload.count)].write(to: url)
            return url
        }
        let out = root.appendingPathComponent("out")
        try DownloadDelegate.assemble(parts, into: out)
        XCTAssertEqual(try Data(contentsOf: out), payload)
        XCTAssertFalse(parts.contains { FileManager.default.fileExists(atPath: $0.path) }, "Parts are removed as they are stitched")
        let hash = SHA256.hash(data: payload).map { String(format: "%02x", $0) }.joined()
        XCTAssertNoThrow(try DownloadDelegate.verify(out, file: .init(name: "out", size: Int64(payload.count), sha256: hash)))
        XCTAssertTrue(ModelChoice.small.parallelDownload && ModelChoice.large.parallelDownload)
        XCTAssertFalse(ModelChoice.standard.parallelDownload)
    }

    func testStreamingTextNeverShowsHalfWordsOrRawMarkers() {
        XCTAssertEqual(StreamingText.stable("Then nothing is lo", live: true), "Then nothing is ")
        XCTAssertEqual(StreamingText.stable("Pick it up with **tonight’s two", live: true), "Pick it up with **tonight’s** ")
        XCTAssertEqual(StreamingText.stable("Run `make te", live: true), "Run `make` ")
        XCTAssertEqual(StreamingText.stable("Hello", live: true), "")
        XCTAssertEqual(StreamingText.stable("Done **here", live: false), "Done **here")
        XCTAssertEqual(StreamingText.stable("**a** and **b** then", live: true), "**a** and **b** ")
    }

    func testToolCallsAreParsedAndHidden() {
        let text = "Let me check. <tool_call>{\"name\": \"read_wiki_structure\", \"arguments\": {\"repoName\": \"a/b\"}}</tool_call>"
        let call = ToolCall.parse(text)
        XCTAssertEqual(call?.name, "read_wiki_structure")
        XCTAssertEqual(call?.argumentsJSON, #"{"repoName":"a\/b"}"#)
        XCTAssertEqual(ToolCall.visible(text), "Let me check. ")
        XCTAssertNil(ToolCall.parse("No tools here."))
    }

    func testWikipediaSearchUsesKeyTerms() {
        XCTAssertEqual(Wikipedia.keywords("How tall is the Eiffel Tower?"), ["Eiffel", "Tower"])
        XCTAssertEqual(Wikipedia.keywords("why do cats purr"), ["cats", "purr"])
    }

    /// Regression: MLX loads every .safetensors under a folder, recursively. A model must
    /// only ever see its own files, never the voice engine's or another model's.
    @MainActor
    func testModelLoadsOnlyItsOwnWeightFiles() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString).appendingPathComponent("Model")
        try FileManager.default.createDirectory(at: root.appendingPathComponent("Kokoro"), withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root.deletingLastPathComponent()) }
        try Data("own".utf8).write(to: root.appendingPathComponent("model.safetensors"))
        try Data("{}".utf8).write(to: root.appendingPathComponent("config.json"))
        try Data("bert".utf8).write(to: root.appendingPathComponent("Kokoro/kokoro-v1_0.safetensors"))
        let spec = ModelSpec(repository: "test/model", revision: String(repeating: "0", count: 40), files: [
            .init(name: "model.safetensors", size: 3, sha256: nil), .init(name: "config.json", size: 2, sha256: nil),
        ])
        let download = ModelDownload(spec: spec, directory: root, sessionID: "test." + UUID().uuidString)
        let staging = try download.loadingDirectory()
        let found = FileManager.default.enumerator(at: staging, includingPropertiesForKeys: nil)!.compactMap { ($0 as? URL)?.lastPathComponent }.sorted()
        XCTAssertEqual(found, ["config.json", "model.safetensors"])
        XCTAssertEqual(try String(contentsOf: staging.appendingPathComponent("model.safetensors"), encoding: .utf8), "own")
        XCTAssertFalse(staging.path.hasPrefix(root.path + "/"), "The load folder must sit outside the model folder")
    }

    func testIslandsReadByNameForTheModelAndVanishForTools() {
        let maps = Island.connector(.maps), file = Island(kind: .file, ref: "f", title: "notes.md", icon: "doc", tint: .amber)
        let text = "Find a café \(maps.marker) near the place in \(file.marker)"
        XCTAssertEqual(IslandText.spoken(text, [maps, file]), "Find a café [Maps] near the place in [the attached file “notes.md”]")
        XCTAssertEqual(IslandText.titled(text, [maps, file]), "Find a café Maps near the place in notes.md")
        XCTAssertEqual(IslandText.stripped(text), "Find a café near the place in")
        XCTAssertEqual(IslandText.present(in: "Find \(maps.marker)", [maps, file]).map(\.id), [maps.id])
    }

    func testIslandsSurviveTheArchiveAndUnknownMarkersDrop() throws {
        let maps = Island.connector(.maps)
        var message = ChatMessage(role: .user, text: "Coffee \(maps.marker) ⟦gone⟧")
        message.islands = [maps]
        let decoded = try JSONDecoder().decode(ChatMessage.self, from: JSONEncoder().encode(message))
        XCTAssertEqual(decoded.islands, [maps])
        XCTAssertEqual(decoded.readable, "Coffee Maps ")
        var use = ToolUse(kind: "calc", icon: "function", running: "", done: "")
        use.widget = .map(title: "café", pins: [.init(name: "Lomi", detail: "650 m", latitude: 48.9, longitude: 2.35)], here: nil)
        XCTAssertEqual(try JSONDecoder().decode(ToolUse.self, from: JSONEncoder().encode(use)).widget, use.widget)
    }

    func testMemoryPlanSizesTheKVCacheFromTheModelConfig() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let qwen = #"{"model_type":"qwen3_vl","text_config":{"num_hidden_layers":36,"num_attention_heads":32,"num_key_value_heads":8,"head_dim":128}}"#
        try Data(qwen.utf8).write(to: root.appendingPathComponent("config.json"))
        try Data(count: 4096).write(to: root.appendingPathComponent("model.safetensors"))
        let plan = try MemoryPlan(model: .standard, directory: root)
        XCTAssertEqual(plan.weightBytes, 4096)
        XCTAssertEqual(plan.kvPerToken, 36 * 8 * 128 * 4)
        XCTAssertEqual(plan.fixedKV, 0)
        // A finished reply has nothing left to claim; a fresh one claims its whole length.
        XCTAssertEqual(plan.remaining(generated: ModelSpec.maxOutputTokens), 0)
        XCTAssertGreaterThan(plan.need(prompt: 2048, photo: false), plan.need(prompt: 256, photo: false))
        // Short contexts stay bf16; a long one switches to 8 bits, about half the bytes.
        XCTAssertEqual(plan.cache(512), plan.kvPerToken * 512)
        XCTAssertLessThan(Double(plan.cache(2816)), Double(plan.kvPerToken * 2816) * 0.55)
        XCTAssertEqual(plan.remaining(generated: 0), plan.kvPerToken * ModelSpec.maxOutputTokens)
    }

    func testMemoryPlanBoundsGemmaSlidingLayers() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let gemma = #"{"model_type":"gemma3_text","num_hidden_layers":48,"num_attention_heads":16,"num_key_value_heads":8,"head_dim":256,"sliding_window":1024,"sliding_window_pattern":6}"#
        try Data(gemma.utf8).write(to: root.appendingPathComponent("config.json"))
        let plan = try MemoryPlan(model: .large, directory: root)
        XCTAssertEqual(plan.kvPerToken, 8 * 8 * 256 * 4) // only the 8 global layers grow
        XCTAssertEqual(plan.fixedKV, 40 * 8 * 256 * 4 * 1024)
    }

    func testFirstMessageDatesTheChat() {
        let made = Date(timeIntervalSince1970: 1_000)
        var chat = Conversation(createdAt: made)
        let first = Date(timeIntervalSince1970: 50_000)
        chat.append(.init(role: .user, text: "hello"), at: first)
        XCTAssertEqual(chat.createdAt, first, "A blank chat reused hours later is dated by its first message")
        chat.append(.init(role: .assistant, text: "hi"), at: Date(timeIntervalSince1970: 90_000))
        XCTAssertEqual(chat.createdAt, first, "Later messages don't move it")
    }

    func testEchoOfLoocalsOwnWordsNeverCutsItOff() {
        let echo = Set(TurnTaking.words("The strata bakes for about forty minutes."))
        XCTAssertFalse(TurnTaking.isInterruption("about forty minutes", echo: echo, talkingFor: 1.5))
        XCTAssertEqual(TurnTaking.strippingEcho("forty minutes wait what temperature", echo: echo), "wait what temperature")
    }

    func testBackchannelsKeepLoocalTalkingButRealWordsCutIn() {
        let echo: Set<String> = ["leeks", "butter"]
        XCTAssertFalse(TurnTaking.isInterruption("yeah mm-hmm", echo: echo, talkingFor: 0.4))
        XCTAssertFalse(TurnTaking.isInterruption("Ouais", echo: echo, talkingFor: 0.3))
        XCTAssertTrue(TurnTaking.isInterruption("Wait,", echo: echo, talkingFor: 0.2), "One stop word is enough")
        XCTAssertTrue(TurnTaking.isInterruption("Attends !", echo: echo, talkingFor: 0.2))
        XCTAssertTrue(TurnTaking.isInterruption("what about cheese", echo: echo, talkingFor: 0.3))
        XCTAssertFalse(TurnTaking.isInterruption("cheese", echo: echo, talkingFor: 0.2), "A lone word needs a sustained voice")
        XCTAssertTrue(TurnTaking.isInterruption("cheese", echo: echo, talkingFor: 0.8))
    }

    func testPlainWordsAskForTheCalendarWithoutAnIsland() {
        XCTAssertTrue(SourceIntent.wanted("Check my calendar").contains(Connector.calendar.id))
        XCTAssertTrue(SourceIntent.wanted("What I have going on next month").contains(Connector.calendar.id))
        XCTAssertTrue(SourceIntent.wanted("Qu’est-ce que j’ai demain ?").contains(Connector.calendar.id))
        XCTAssertTrue(SourceIntent.wanted("Show my reminders").contains(Connector.reminders.id))
        XCTAssertTrue(SourceIntent.wanted("How tall is the Eiffel Tower?").isEmpty)
    }

    func testCalendarWindowFollowsTheWords() {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        calendar.firstWeekday = 2
        let day = { (y: Int, m: Int, d: Int) in calendar.date(from: DateComponents(year: y, month: m, day: d))! }
        let now = calendar.date(from: DateComponents(year: 2026, month: 11, day: 22, hour: 10))! // a Sunday
        let month = SourceIntent.calendarWindow(for: "What I have going on next month", now: now, calendar: calendar)
        XCTAssertEqual([month.start, month.end], [day(2026, 12, 1), day(2027, 1, 1)])
        let tomorrow = SourceIntent.calendarWindow(for: "anything tomorrow?", now: now, calendar: calendar)
        XCTAssertEqual([tomorrow.start, tomorrow.end], [day(2026, 11, 23), day(2026, 11, 24)])
        let week = SourceIntent.calendarWindow(for: "plan next week", now: now, calendar: calendar)
        XCTAssertEqual([week.start, week.end], [day(2026, 11, 23), day(2026, 11, 30)])
        XCTAssertEqual(SourceIntent.calendarWindow(for: "my next 10 days", now: now, calendar: calendar).end, day(2026, 12, 3))
        XCTAssertEqual(SourceIntent.calendarWindow(for: "Check my calendar", now: now, calendar: calendar).end, day(2026, 11, 29))
    }
}
