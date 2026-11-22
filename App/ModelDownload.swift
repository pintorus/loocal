import CryptoKit
import Foundation
import Observation

/// How a model's files are split into transfers.
///
/// Legacy (unchunked) downloads fetch each file as one stream over one connection; that's
/// what the 8B has always used, and its in-flight transfers must keep matching after an
/// update. Chunked downloads split large files into byte ranges fetched in parallel over
/// several connections, then stitch and hash-verify them. Hugging Face's CDN honours
/// `Range` across its redirect (206 Partial Content).
struct DownloadPlan: Sendable {
    static let chunkSize: Int64 = 128 * 1024 * 1024
    static let parallelConnections = 6

    let chunked: Bool

    func ranges(for file: ModelSpec.File) -> [ClosedRange<Int64>] {
        guard chunked, file.size > Self.chunkSize else { return [0...max(file.size - 1, 0)] }
        return stride(from: Int64(0), to: file.size, by: Int(Self.chunkSize)).map { $0...(min($0 + Self.chunkSize, file.size) - 1) }
    }

    func isSplit(_ file: ModelSpec.File) -> Bool { ranges(for: file).count > 1 }

    /// Task description: the file name for whole-file transfers, `name#index` for parts.
    static func task(_ name: String, part: Int?) -> String { part.map { "\(name)#\($0)" } ?? name }

    static func parse(_ description: String) -> (name: String, part: Int?) {
        guard let hash = description.lastIndex(of: "#"), let part = Int(description[description.index(after: hash)...]) else { return (description, nil) }
        return (String(description[..<hash]), part)
    }

    static func partURL(_ directory: URL, _ name: String, _ part: Int) -> URL {
        directory.appendingPathComponent(".parts", isDirectory: true).appendingPathComponent("\(name).\(part)")
    }
}

/// The background URLSession owns transfers even while the app is suspended.
/// Hashing, stitching and moving files happen on its serial delegate queue, never the UI thread.
final class DownloadDelegate: NSObject, URLSessionDownloadDelegate, @unchecked Sendable {
    let spec: ModelSpec
    let directory: URL
    let plan: DownloadPlan
    let event: @Sendable (Event) -> Void
    enum Event: Sendable {
        case progress(String, Int64)
        case verified(String)
        case failure(String)
        case finishedEvents
    }

    init(spec: ModelSpec, directory: URL, plan: DownloadPlan, event: @escaping @Sendable (Event) -> Void) {
        self.spec = spec
        self.directory = directory
        self.plan = plan
        self.event = event
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData bytesWritten: Int64, totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
        guard let description = downloadTask.taskDescription else { return }
        event(.progress(description, totalBytesWritten))
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        guard let description = downloadTask.taskDescription else { return }
        let (name, part) = DownloadPlan.parse(description)
        guard let file = spec.files.first(where: { $0.name == name }) else { return }
        let status = (downloadTask.response as? HTTPURLResponse)?.statusCode
        do {
            if let part {
                let ranges = plan.ranges(for: file)
                guard ranges.indices.contains(part) else { return }
                if status == 200, Self.size(of: location) == file.size {
                    // The server ignored Range and sent the whole file: use it as-is.
                    try finish(location, file: file)
                    return
                }
                guard status == 206, Self.size(of: location) == Int64(ranges[part].count) else {
                    throw IslandError.message("A piece of \(file.name) arrived incomplete. Retry to repair it.")
                }
                let destination = DownloadPlan.partURL(directory, name, part)
                try FileManager.default.createDirectory(at: destination.deletingLastPathComponent(), withIntermediateDirectories: true)
                if FileManager.default.fileExists(atPath: destination.path) { try FileManager.default.removeItem(at: destination) }
                try FileManager.default.moveItem(at: location, to: destination)
                let parts = ranges.indices.map { DownloadPlan.partURL(directory, name, $0) }
                let complete = zip(parts, ranges).allSatisfy { Self.size(of: $0) == Int64($1.count) }
                guard complete, !FileManager.default.fileExists(atPath: directory.appendingPathComponent(name + ".verified").path) else { return }
                let stitched = directory.appendingPathComponent(name + ".assembling")
                try Self.assemble(parts, into: stitched)
                try finish(stitched, file: file)
            } else {
                guard status == 200 else { throw IslandError.message("The model server returned an error. Retry the download.") }
                try finish(location, file: file)
            }
        } catch { event(.failure(error.localizedDescription)) }
    }

    /// Verify size and hash, move into place, then write the receipt.
    private func finish(_ source: URL, file: ModelSpec.File) throws {
        do { try Self.verify(source, file: file) } catch {
            try? FileManager.default.removeItem(at: source)
            throw error
        }
        let destination = directory.appendingPathComponent(file.name)
        if FileManager.default.fileExists(atPath: destination.path) { try FileManager.default.removeItem(at: destination) }
        try FileManager.default.moveItem(at: source, to: destination)
        try FileManager.default.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: destination.path)
        // A receipt is written only after integrity validation succeeds.
        try Data(spec.revision.utf8).write(to: directory.appendingPathComponent(file.name + ".verified"), options: .atomic)
        event(.verified(file.name))
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        if let error, (error as NSError).code != NSURLErrorCancelled { event(.failure(error.localizedDescription)) }
    }

    func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) { event(.finishedEvents) }

    static func size(of url: URL) -> Int64 {
        Int64((try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? -1)
    }

    /// Concatenate parts in order, deleting each once appended so peak disk use stays
    /// near one copy of the file.
    static func assemble(_ parts: [URL], into destination: URL) throws {
        if FileManager.default.fileExists(atPath: destination.path) { try FileManager.default.removeItem(at: destination) }
        FileManager.default.createFile(atPath: destination.path, contents: nil)
        let output = try FileHandle(forWritingTo: destination)
        defer { try? output.close() }
        for part in parts {
            let input = try FileHandle(forReadingFrom: part)
            while let data = try input.read(upToCount: 8 * 1024 * 1024), !data.isEmpty { try output.write(contentsOf: data) }
            try input.close()
            try FileManager.default.removeItem(at: part)
        }
    }

    static func verify(_ url: URL, file: ModelSpec.File) throws {
        guard size(of: url) == file.size else { throw IslandError.message("Incomplete download: \(file.name). Retry to repair it.") }
        if let expected = file.sha256 {
            let handle = try FileHandle(forReadingFrom: url)
            defer { try? handle.close() }
            var hash = SHA256()
            while let data = try handle.read(upToCount: 4 * 1024 * 1024), !data.isEmpty { hash.update(data: data) }
            let actual = hash.finalize().map { String(format: "%02x", $0) }.joined()
            guard actual == expected else { throw IslandError.message("Integrity check failed: \(file.name). Retry to repair it.") }
        }
    }
}

@MainActor @Observable
final class ModelDownload {
    static let sessionID = "local.islandai.app.model-download"
    let spec: ModelSpec
    let directory: URL
    let sessionID: String
    let plan: DownloadPlan
    var isDownloading = false
    var error: String?
    var isReady: Bool { verifiedNames.count == spec.files.count }
    var progress: Double {
        let bytes = spec.files.reduce(Int64(0)) { result, file in
            guard !verifiedNames.contains(file.name) else { return result + file.size }
            let count = plan.ranges(for: file).count
            let parts: Int64 = plan.isSplit(file)
                ? (0..<count).reduce(0) { $0 + (received[DownloadPlan.task(file.name, part: $1)] ?? 0) }
                : received[file.name] ?? 0
            return result + min(parts, file.size)
        }
        return Double(bytes) / Double(max(spec.totalBytes, 1))
    }
    private var verifiedNames = Set<String>()
    private var received: [String: Int64] = [:]
    @ObservationIgnored private var session: URLSession!
    @ObservationIgnored private var delegate: DownloadDelegate!
    @ObservationIgnored var backgroundCompletion: (() -> Void)?

    init(spec: ModelSpec, directory: URL, sessionID: String = ModelDownload.sessionID, chunked: Bool = false) {
        self.spec = spec
        self.directory = directory
        self.sessionID = sessionID
        self.plan = DownloadPlan(chunked: chunked)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        refreshReceipts()
        delegate = DownloadDelegate(spec: spec, directory: directory, plan: plan) { [weak self] event in
            Task { @MainActor in self?.handle(event) }
        }
        let config = URLSessionConfiguration.background(withIdentifier: sessionID)
        config.isDiscretionary = false
        config.sessionSendsLaunchEvents = true
        config.httpMaximumConnectionsPerHost = chunked ? DownloadPlan.parallelConnections : 1
        config.allowsCellularAccess = false
        config.timeoutIntervalForResource = 7 * 24 * 3600
        let queue = OperationQueue()
        queue.maxConcurrentOperationCount = 1
        queue.qualityOfService = .utility
        session = URLSession(configuration: config, delegate: delegate, delegateQueue: queue)
        session.getAllTasks { [weak self] tasks in
            Task { @MainActor in self?.isDownloading = !tasks.isEmpty }
        }
    }

    private func refreshReceipts() {
        verifiedNames = Set(spec.files.compactMap { file in
            let url = directory.appendingPathComponent(file.name)
            let receipt = try? String(contentsOf: directory.appendingPathComponent(file.name + ".verified"), encoding: .utf8)
            return receipt == spec.revision && DownloadDelegate.size(of: url) == file.size ? file.name : nil
        })
        // Finished pieces on disk count toward progress and are never fetched again.
        for file in spec.files where plan.isSplit(file) && !verifiedNames.contains(file.name) {
            for (index, range) in plan.ranges(for: file).enumerated() where DownloadDelegate.size(of: DownloadPlan.partURL(directory, file.name, index)) == Int64(range.count) {
                received[DownloadPlan.task(file.name, part: index)] = Int64(range.count)
            }
        }
    }

    func start() {
        #if targetEnvironment(simulator)
        error = "Run Loocal on a physical iPhone to download and use the model. The simulator is for interface previews."
        return
        #else
        guard !isDownloading, !isReady else { return }
        error = nil
        received = [:]
        refreshReceipts()
        do {
            let available = try directory.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey]).volumeAvailableCapacityForImportantUsage ?? 0
            let remaining = spec.files.filter { !verifiedNames.contains($0.name) }.reduce(Int64(0)) { $0 + $1.size }
            guard available > remaining + 1_000_000_000 else { throw IslandError.message("Free at least \(ByteCountFormatter.string(fromByteCount: remaining + 1_000_000_000, countStyle: .file)) to install this model.") }
            isDownloading = true
            session.getAllTasks { [weak self] tasks in
                Task { @MainActor in
                    guard let self else { return }
                    let active = Set(tasks.compactMap(\.taskDescription))
                    // Biggest files first so the long transfers start immediately.
                    for file in self.spec.files.sorted(by: { $0.size > $1.size }) where !self.verifiedNames.contains(file.name) {
                        let url = URL(string: "https://huggingface.co/\(self.spec.repository)/resolve/\(self.spec.revision)/\(file.name)")!
                        let ranges = self.plan.ranges(for: file)
                        guard self.plan.isSplit(file) else {
                            if !active.contains(file.name) { self.schedule(URLRequest(url: url), as: file.name) }
                            continue
                        }
                        for (index, range) in ranges.enumerated() {
                            let name = DownloadPlan.task(file.name, part: index)
                            guard !active.contains(name), self.received[name] != Int64(range.count) else { continue }
                            var request = URLRequest(url: url)
                            request.setValue("bytes=\(range.lowerBound)-\(range.upperBound)", forHTTPHeaderField: "Range")
                            self.schedule(request, as: name)
                        }
                    }
                }
            }
        } catch { self.error = error.localizedDescription; isDownloading = false }
        #endif
    }

    /// A folder holding links to exactly this model's files, for loading.
    ///
    /// MLX's loader reads every `.safetensors` file under the folder it's given, including
    /// subfolders. The 8B lives at the root of the models folder, where the other models and
    /// the Kokoro voice engine have their own subfolders, so loading straight from there mixed
    /// their weights in ("Unhandled keys … bert"). Links cost nothing and move no data.
    ///
    /// Several replies can start at once, and one may be loading the model from this folder
    /// while another asks for it: an intact folder is reused, never pulled out from under it.
    func loadingDirectory() throws -> URL {
        let fileManager = FileManager.default
        let staging = directory.deletingLastPathComponent()
            .appendingPathComponent(directory.lastPathComponent == "Model" ? ".load-root" : ".load-" + directory.lastPathComponent, isDirectory: true)
        let intact = spec.files.allSatisfy { file in
            (try? fileManager.destinationOfSymbolicLink(atPath: staging.appendingPathComponent(file.name).path)) == directory.appendingPathComponent(file.name).path
        }
        if intact, let names = try? fileManager.contentsOfDirectory(atPath: staging.path), names.count == spec.files.count { return staging }
        try? fileManager.removeItem(at: staging)
        try fileManager.createDirectory(at: staging, withIntermediateDirectories: true)
        for file in spec.files {
            try fileManager.createSymbolicLink(at: staging.appendingPathComponent(file.name), withDestinationURL: directory.appendingPathComponent(file.name))
        }
        return staging
    }

    private func schedule(_ request: URLRequest, as name: String) {
        let task = session.downloadTask(with: request)
        task.taskDescription = name
        task.resume()
    }

    private func handle(_ event: DownloadDelegate.Event) {
        switch event {
        case .progress(let name, let bytes): received[name] = bytes
        case .verified(let name):
            verifiedNames.insert(name)
            received = received.filter { DownloadPlan.parse($0.key).name != name }
            if isReady { isDownloading = false; error = nil }
        case .failure(let message):
            error = message
            isDownloading = false
            // Finished pieces stay on disk; Retry fetches only what's missing.
            session.getAllTasks { tasks in tasks.forEach { $0.cancel() } }
        case .finishedEvents:
            let completion = backgroundCompletion
            backgroundCompletion = nil
            completion?()
        }
    }
}
