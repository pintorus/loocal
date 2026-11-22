import Foundation
import os

struct ModelSpec: Codable, Sendable {
    struct File: Codable, Sendable {
        let name: String
        let size: Int64
        let sha256: String?
    }
    let repository: String
    let revision: String
    let files: [File]
    var totalBytes: Int64 { files.reduce(0) { $0 + $1.size } }
    static let maxOutputTokens = 768
    static let maxInputTokens = 2048

    static func bundled(_ manifest: String = "ModelManifest") throws -> ModelSpec {
        guard let url = Bundle.main.url(forResource: manifest, withExtension: "json") else {
            throw IslandError.message("The bundled model manifest is missing.")
        }
        return try JSONDecoder().decode(ModelSpec.self, from: Data(contentsOf: url))
    }
}

/// The three models Loocal can download. Ordered small → large, which is also the
/// left-to-right order of the model carousel.
struct ModelChoice: Identifiable, Hashable, Sendable {
    enum Family: Sendable { case qwenVL, gemmaText }
    let id: String
    let name: String
    let detail: String
    let manifest: String
    /// Sub-folder of the models directory. The 8B predates multi-model support and keeps
    /// living at the root, so a download already in progress on a phone survives updates.
    let folder: String?
    let family: Family

    var vision: Bool { family == .qwenVL }
    /// Parallel byte-range downloads. Off for the 8B so a transfer already running on a
    /// phone keeps matching its original single-stream tasks after an update.
    var parallelDownload: Bool { folder != nil }
    var sessionID: String { folder == nil ? ModelDownload.sessionID : ModelDownload.sessionID + "." + id }
    var stopTokens: Set<String> { family == .gemmaText ? ["<end_of_turn>"] : ["<|im_end|>"] }

    static let small = ModelChoice(id: "qwen3-vl-4b", name: "Qwen3 VL 4B", detail: "Faster · words and vision", manifest: "ModelManifest-4B", folder: "Qwen3-VL-4B", family: .qwenVL)
    static let standard = ModelChoice(id: "qwen3-vl-8b", name: "Qwen3 VL 8B", detail: "Balanced · words and vision", manifest: "ModelManifest", folder: nil, family: .qwenVL)
    static let large = ModelChoice(id: "gemma-3-12b", name: "Gemma 3 12B", detail: "Deepest · words only", manifest: "ModelManifest-12B", folder: "Gemma-3-12B", family: .gemmaText)
    static let all = [small, standard, large]
    static func with(id: String) -> ModelChoice { all.first { $0.id == id } ?? standard }
}

enum IslandError: LocalizedError {
    case message(String)
    /// Free memory fell below the safety floor mid-reply (megabytes left).
    case lowMemory(Int)
    var errorDescription: String? {
        switch self {
        case .message(let text): return text
        case .lowMemory(let mb): return "Loocal stopped before iOS ran out of memory (\(mb) MB left). Your partial reply is saved. A smaller model or a shorter chat leaves more room."
        }
    }
}

/// Real headroom from the kernel, not iOS's early memory warnings.
enum MemoryHeadroom {
    /// Stop generating below this, rather than let iOS terminate the app mid-reply.
    static let floor = 180 * 1024 * 1024
    static var bytes: Int {
        let available = os_proc_available_memory()
        most.withLock { $0 = max($0, available) }
        return available
    }
    /// The most this app has had free since launch: about all iOS will ever let it use.
    static var ceiling: Int { most.withLock { max($0, os_proc_available_memory()) } }
    private static let most = OSAllocatedUnfairLock(initialState: 0)
    static var megabytes: Int { bytes / (1024 * 1024) }
}

struct ChatMessage: Identifiable, Codable, Sendable, Equatable {
    enum Role: String, Codable, Sendable { case user, assistant }
    enum Status: String, Codable, Sendable { case complete, generating, interrupted }
    var id = UUID()
    var role: Role
    var text: String
    var imageName: String?
    var status: Status = .complete
    /// Attached file names, shown as chips on the message.
    var files: [String]? = nil
    /// Hidden text sent to the model with this message (attached file contents).
    var context: String? = nil
    /// What the reply used on the way: sources read and tools called, shown as boxes.
    var tools: [ToolUse]? = nil
    /// Tools, connectors and attachments placed in the prompt's text as `⟦id⟧` markers.
    var islands: [Island]? = nil
    /// The prompt as a person reads it, islands by name.
    var readable: String { IslandText.titled(text, islands ?? []) }
}

/// One tool step in a reply, as the chat shows it.
struct ToolUse: Identifiable, Codable, Sendable, Equatable {
    enum Status: String, Codable, Sendable { case running, done, failed, undone }
    struct Link: Codable, Sendable, Equatable, Hashable {
        var title: String
        var url: URL
        var host: String { url.host()?.replacingOccurrences(of: "www.", with: "") ?? url.absoluteString }
    }
    var id = UUID()
    /// Connector or tool id, used to pick the wording and icon.
    var kind: String
    var icon: String
    /// "Searching the web" while running, "Searched the web" once done.
    var running: String
    var done: String
    /// The query or a short note on what was read.
    var detail: String = ""
    var status: Status = .running
    var links: [Link] = []
    /// Something it did (not just read), which can be undone.
    var action: ToolAction? = nil
    /// What the tool found, drawn in the reply: a map, a result, a translation.
    var widget: ToolWidget? = nil
    var title: String {
        switch status {
        case .running: running
        case .done: done
        case .undone: "Undone"
        case .failed: action == nil ? "Couldn’t reach " + done.lowercased() : "Couldn’t do it"
        }
    }
}

/// A small piece of interface a tool puts into the reply text.
enum ToolWidget: Codable, Sendable, Equatable {
    struct Pin: Codable, Sendable, Equatable, Hashable {
        var name: String
        var detail: String
        var latitude: Double
        var longitude: Double
        var url: URL?
    }
    /// Places found around you (or a trip's destination), and where you are.
    case map(title: String, pins: [Pin], here: Pin?)
    case calculation(expression: String, result: String)
    case translation(language: String, text: String)
}

struct Conversation: Identifiable, Codable, Sendable {
    var id = UUID()
    var createdAt = Date()
    var messages: [ChatMessage] = []
    /// Temporary chats are never written to disk or listed in History.
    var temporary: Bool? = nil
    var isTemporary: Bool { temporary == true }
    /// Pinned chats sit at the top of History.
    var pinned: Bool? = nil
    var isPinned: Bool { pinned == true }
    var title: String { messages.first(where: { $0.role == .user }).map { String($0.readable.prefix(52)) } ?? "New conversation" }

    /// Adds a message. The first one dates the chat: a blank chat can sit unused for hours
    /// (New Chat reuses it), and History should show when you actually started it.
    mutating func append(_ message: ChatMessage, at date: Date = .now) {
        if messages.isEmpty { createdAt = date }
        messages.append(message)
    }

    mutating func recoverInterruptedReply() {
        for index in messages.indices where messages[index].status == .generating {
            messages[index].status = .interrupted
        }
    }
}

struct ChatArchive: Codable {
    var conversations: [Conversation]
    var selectedID: UUID
}

struct LocalFiles {
    let root: URL
    let models: URL
    let images: URL
    let archive: URL

    init(root: URL? = nil) throws {
        self.root = try root ?? FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true).appendingPathComponent("Island", isDirectory: true)
        models = self.root.appendingPathComponent("Model", isDirectory: true)
        images = self.root.appendingPathComponent("Images", isDirectory: true)
        archive = self.root.appendingPathComponent("conversations.json")
        for directory in [self.root, models, images] {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            try FileManager.default.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: directory.path)
        }
        // Nothing Loocal stores goes to iCloud or computer backups: chats, photos and models stay on this phone.
        var excluded = self.root
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try excluded.setResourceValues(values)
    }

    /// Photos from temporary chats live apart, so they can be wiped wholesale.
    static let temporaryImages = "Temporary"

    /// Remove anything a previous run may have left behind: photos of temporary chats, photos
    /// no saved chat refers to (deleted chats, unsent attachments, an interrupted save), and
    /// scratch files. Run at launch, before anything new is written.
    func sweep(keeping referenced: Set<String>, clearScratch: Bool = true) {
        let manager = FileManager.default
        try? manager.removeItem(at: images.appendingPathComponent(Self.temporaryImages, isDirectory: true))
        for name in (try? manager.contentsOfDirectory(atPath: images.path)) ?? [] where !referenced.contains(name) {
            try? manager.removeItem(at: images.appendingPathComponent(name))
        }
        guard clearScratch else { return }
        let scratch = manager.temporaryDirectory
        for name in (try? manager.contentsOfDirectory(atPath: scratch.path)) ?? [] {
            try? manager.removeItem(at: scratch.appendingPathComponent(name))
        }
    }

    func save(_ value: ChatArchive) throws {
        try JSONEncoder().encode(value).write(to: archive, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }

    func load() throws -> ChatArchive? {
        guard FileManager.default.fileExists(atPath: archive.path) else { return nil }
        var result = try JSONDecoder().decode(ChatArchive.self, from: Data(contentsOf: archive))
        for index in result.conversations.indices { result.conversations[index].recoverInterruptedReply() }
        return result
    }
}
