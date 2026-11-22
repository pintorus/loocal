import ActivityKit
import BackgroundTasks
import Foundation
import UIKit

@MainActor
final class ActivityController {
    private var activity: Activity<InferenceAttributes>?
    private var lastUpdate = Date.distantPast
    var modelName = ModelChoice.standard.name
    private var state = InferenceAttributes.ContentState(phase: "", detail: "", generatedPieces: 0, isRunning: true)
    private var ticker: Task<Void, Never>?
    /// The Live Activity is up (several jobs share one).
    var isRunning: Bool { activity != nil }

    init() {
        let stale = Activity<InferenceAttributes>.activities
        Task {
            for value in stale { await value.end(nil, dismissalPolicy: .immediate) }
        }
    }

    func start() {
        guard ActivityAuthorizationInfo().areActivitiesEnabled else { return }
        state = InferenceAttributes.ContentState(phase: "Loading model", detail: "On-device · \(modelName)", generatedPieces: 0, isRunning: true)
        do {
            activity = try Activity.request(attributes: InferenceAttributes(modelName: modelName, startedAt: .now), content: ActivityContent(state: state, staleDate: .now.addingTimeInterval(90)), pushType: nil)
        } catch { /* Activity permission does not prevent local inference. */ }
        // A steady beat keeps the island's spiral writing, even while nothing else changes.
        ticker?.cancel()
        ticker = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(2))
                guard let self, let activity = self.activity else { return }
                self.state.tick += 1
                await activity.update(ActivityContent(state: self.state, staleDate: .now.addingTimeInterval(90)))
            }
        }
    }

    func update(phase: String, count: Int, force: Bool = false) async {
        guard activity != nil, force || Date().timeIntervalSince(lastUpdate) >= 2 else { return }
        lastUpdate = .now
        state.phase = phase
        state.generatedPieces = count
        state.detail = count == 0 ? "On-device · \(modelName)" : "\(count) tokens"
        // The beat sends it, so the spiral's two-second glide isn't cut short; a phase change
        // (Paused, say) goes out at once.
        if force, let activity { await activity.update(ActivityContent(state: state, staleDate: .now.addingTimeInterval(90))) }
    }

    /// Ends the activity. In the app it goes away at once; from the background it shows the
    /// result briefly, then leaves by itself (and is cleared the moment the app is opened).
    func finish(phase: String, count: Int) {
        guard let current = activity else { return }
        activity = nil
        ticker?.cancel()
        ticker = nil
        let inApp = UIApplication.shared.applicationState == .active
        if !inApp { lingering.append(current) }
        Task {
            await current.end(ActivityContent(state: .init(phase: phase, detail: "Open Loocal to read your reply", generatedPieces: count, isRunning: false), staleDate: nil),
                              dismissalPolicy: inApp ? .immediate : .after(.now.addingTimeInterval(8)))
        }
    }

    private var lingering: [Activity<InferenceAttributes>] = []

    /// Opening the app removes any finished activity still on screen, including strays.
    func dismissFinished() {
        lingering.removeAll()
        let running = activity?.id
        for value in Activity<InferenceAttributes>.activities where value.id != running {
            Task { await value.end(nil, dismissalPolicy: .immediate) }
        }
    }
}

@MainActor
final class ContinuedInference {
    private let identifier = (Bundle.main.bundleIdentifier ?? "local.islandai.app") + ".inference"
    private var registered = false
    private var pendingID: String?
    private var runID: UUID?
    private var submission: Task<Void, Never>?
    private var task: BGContinuedProcessingTask?
    private var launch: (() -> Void)?
    private var expire: (() -> Void)?
    /// A continued-processing task is running, so iOS keeps the app alive in the background.
    var hasGrant: Bool { task != nil }
    /// Asked iOS and still waiting for its answer: jobs that join now start with the first.
    var isPending: Bool { launch != nil }
    /// That task includes background GPU. Otherwise background work must run on the CPU.
    private(set) var grantedGPU = false
    var modelName = ModelChoice.standard.name

    init() {
        registered = BGTaskScheduler.shared.register(forTaskWithIdentifier: identifier, using: .main) { [weak self] value in
            guard let continued = value as? BGContinuedProcessingTask else { value.setTaskCompleted(success: false); return }
            Task { @MainActor in
                guard let self, self.pendingID == continued.identifier else { continued.setTaskCompleted(success: false); return }
                self.task = continued
                continued.progress.totalUnitCount = Int64(ModelSpec.maxOutputTokens + 2)
                continued.progress.completedUnitCount = 1
                let runID = self.runID
                continued.expirationHandler = { [weak self] in
                    Task { @MainActor in
                        guard let self, self.runID == runID else { return }
                        self.expire?()
                    }
                }
                let launch = self.launch
                self.launch = nil
                launch?()
            }
        }
    }

    /// Inference starts once iOS grants a continued-processing task: with background GPU
    /// where the device supports it, otherwise CPU-only (the app then generates on the CPU
    /// while backgrounded). If iOS declines, the reply still runs in the foreground.
    func begin(onLaunch: @escaping () -> Void, onExpire: @escaping () -> Void) {
        // Without background GPU, iOS would only lend CPU time, far too slow for these models,
        // and show its own wide banner. Replies pause instead, under Loocal's own small island.
        guard registered, BGTaskScheduler.supportedResources.contains(.gpu) || ComputeTarget.backgroundCPU else { onLaunch(); return }
        // One job at a time, with an exact registered identifier. This also avoids
        // wildcard-matching failures seen in some OS scheduler implementations.
        let id = identifier
        let runID = UUID()
        self.runID = runID
        pendingID = id
        launch = onLaunch
        expire = onExpire
        let gpu = BGTaskScheduler.supportedResources.contains(.gpu)
        grantedGPU = gpu
        let request = BGContinuedProcessingTaskRequest(identifier: id, title: "Loocal · Writing", subtitle: "\(modelName) · On-device")
        if gpu { request.requiredResources = .gpu }
        request.strategy = .fail
        let fallback = { [weak self] in
            guard let self, self.runID == runID, self.task == nil, let launch = self.launch else { return }
            self.finish(success: false)
            launch() // every job that joined while iOS was deciding
        }
        // iOS sometimes neither grants nor refuses. Never leave the reply waiting on it:
        // start after a moment anyway; a late grant still keeps the app alive in the background.
        Task { [weak self] in
            try? await Task.sleep(for: .seconds(2))
            guard let self, self.runID == runID, self.task == nil, let launch = self.launch else { return }
            self.launch = nil
            launch()
        }
        if #available(iOS 27.0, *) {
            submission = Task {
                do {
                    // The current SDK requires submission away from the main thread
                    // and reports failures asynchronously, including delayed denials.
                    try await Task.detached {
                        try await BGTaskScheduler.shared.submitTaskRequest(request)
                    }.value
                } catch { fallback() }
            }
        } else {
            do { try BGTaskScheduler.shared.submit(request) }
            catch { fallback() }
        }
    }

    /// Another job alongside the first: it shares the background task. It starts at once if
    /// iOS has already answered, or together with the first job once it does.
    func join(_ onLaunch: @escaping () -> Void) {
        guard let pending = launch else { onLaunch(); return }
        launch = { pending(); onLaunch() }
    }

    func update(phase: String, count: Int) {
        task?.progress.completedUnitCount = Int64(min(count + 2, ModelSpec.maxOutputTokens + 1))
        task?.updateTitle("Loocal · \(phase)", subtitle: "\(count) / \(ModelSpec.maxOutputTokens) token budget")
    }

    func finish(success: Bool) {
        if success, let task { task.progress.completedUnitCount = task.progress.totalUnitCount }
        task?.setTaskCompleted(success: success)
        if let pendingID { BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: pendingID) }
        task = nil
        grantedGPU = false
        runID = nil
        submission?.cancel()
        submission = nil
        pendingID = nil
        launch = nil
        expire = nil
    }
}
