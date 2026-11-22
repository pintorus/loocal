import EventKit
import Observation
import SwiftUI

/// A voice source that can run in errand mode: what you say is handed to `respond`, and
/// results can be read back between turns with `announce`.
@MainActor
protocol ErrandVoiceSource: ConversationSource {
    var respond: ((String) async -> String)? { get set }
    func announce(_ text: String)
}

extension VoiceConversation: ErrandVoiceSource {}

/// Voice mode for errands: talk to the bot. Each thing you ask becomes an errand on the
/// crew, the bot says it's on it, and when a result comes back it reads it to you.
struct ErrandVoiceView: View {
    @Bindable var store: AppState
    @State private var source: any ErrandVoiceSource
    let shape: BotShape
    @State private var dispatched: Set<UUID> = []
    @State private var announced: Set<UUID> = []
    @State private var hops = 0
    @Environment(\.dismiss) private var dismiss

    init(store: AppState, source: any ErrandVoiceSource, shape: BotShape) {
        self.store = store
        self.shape = shape
        _source = State(initialValue: source)
    }

    var body: some View {
        VStack(spacing: 0) {
            status.padding(.top, 14)
            Spacer(minLength: 20)
            TimelineView(.animation) { timeline in
                let level = source.level(at: timeline.date)
                PebbleBot(mood: mood, shape: shape, size: 190, gaze: gaze, reaction: hops, tint: BotTint.color(for: store.errandPersona?.id))
                    // Listening, it leans in with your voice; speaking, it bounces with its own.
                    .scaleEffect(x: 1 + level * 0.05, y: 1 + level * (source.phase == .speaking ? -0.06 : 0.07), anchor: .bottom)
                    .offset(y: source.phase == .speaking ? -level * 10 : 0)
            }
            .frame(height: 200)
            captions
                .frame(height: 170)
                .padding(.top, 20)
            Spacer(minLength: 12)
            crew
            controls
        }
        .background(Ink.paper.ignoresSafeArea())
        .onAppear {
            source.respond = { [store] said in await Self.dispatch(said, store: store) }
            source.start()
        }
        .onDisappear { source.stop() }
        .onChange(of: store.desk.runs.filter { $0.status == .done }.map(\.id)) { _, done in readBack(done) }
        .onChange(of: store.desk.active.map(\.id)) { old, new in
            // Errands that joined while the voice screen is open are this conversation's.
            let added = Set(new).subtracting(old)
            if !added.isEmpty { dispatched.formUnion(added); hops += 1 }
        }
        .sensoryFeedback(.impact(weight: .light), trigger: source.phase)
        .statusBarHidden()
    }

    private var mood: BotMood {
        if source.muted && source.phase == .listening { return .sleepy }
        switch source.phase {
        case .listening: return source.current?.text.isEmpty == false ? .curious : .attentive
        case .thinking: return .excited
        case .speaking: return .happy
        }
    }

    private var gaze: CGPoint? {
        source.phase == .listening ? CGPoint(x: 0, y: 0.35) : nil
    }

    // MARK: Pieces

    private var status: some View {
        HStack(spacing: 8) {
            Text(statusText).font(.geist(14, .medium)).foregroundStyle(Ink.secondary)
                .shimmer(!(source.muted && source.phase == .listening))
                .contentTransition(.opacity)
        }
        .animation(.smooth(duration: 0.3), value: source.phase)
        .frame(maxWidth: .infinity)
    }

    private var statusText: String {
        if source.muted && source.phase == .listening { return "Muted" }
        switch source.phase {
        case .listening: return "Tell me an errand"
        case .thinking: return "Taking it"
        case .speaking: return "Speaking"
        }
    }

    /// Your last words in italic, the bot's answer under them, written out as they come.
    private var captions: some View {
        let you = source.lines.last(where: \.fromUser)
        let bot = source.lines.last(where: { !$0.fromUser })
        return VStack(spacing: 14) {
            if let you, !you.text.isEmpty {
                Text(you.text)
                    .font(.serif(24, italic: true)).foregroundStyle(Ink.secondary)
                    .lineLimit(3)
                    .id(you.id)
                    .transition(.opacity.combined(with: .offset(y: 10)))
            }
            if let bot, !bot.text.isEmpty, source.lines.last?.id == bot.id || source.phase != .listening || you?.text.isEmpty != false {
                HandwrittenText(text: AttributedString(bot.text), live: source.phase == .speaking)
                    .font(.serif(30)).foregroundStyle(Ink.primary)
                    .lineLimit(4)
                    .id(bot.id)
                    .transition(.opacity.combined(with: .offset(y: 10)))
            }
        }
        .multilineTextAlignment(.center)
        .padding(.horizontal, 32)
        .frame(maxHeight: .infinity, alignment: .top)
        .animation(.spring(response: 0.5, dampingFraction: 0.85), value: source.lines.map(\.id))
    }

    /// The errands this conversation sent off, as a row of little bots.
    private var crew: some View {
        let runs = store.desk.runs.filter { dispatched.contains($0.id) }.reversed()
        return HStack(spacing: 10) {
            ForEach(Array(runs.suffix(5))) { run in
                VStack(spacing: 4) {
                    PebbleBot(mood: run.mood, shape: BotShape(personaID: run.personaID), size: 34, lively: run.isActive, tint: BotTint.color(for: run.personaID))
                    Text(run.title).font(.geist(11, .medium)).foregroundStyle(Ink.secondary).lineLimit(1)
                        .frame(maxWidth: 70)
                }
                .transition(.scale(scale: 0.4, anchor: .top).combined(with: .opacity))
            }
        }
        .frame(height: 60)
        .animation(.spring(response: 0.5, dampingFraction: 0.75), value: runs.map(\.id))
        .padding(.bottom, 18)
    }

    private var controls: some View {
        HStack(spacing: 28) {
            Button { withAnimation(.spring(response: 0.3, dampingFraction: 0.7)) { source.muted.toggle() } } label: {
                Image(systemName: source.muted ? "mic.slash.fill" : "mic")
                    .font(.system(size: 20))
                    .contentTransition(.symbolEffect(.replace))
                    .foregroundStyle(source.muted ? Ink.paper : Ink.primary)
                    .frame(width: 60, height: 60)
                    .background(source.muted ? Ink.primary : Ink.fill, in: .circle)
            }
            .buttonStyle(PressStyle(scale: 0.9))
            .accessibilityLabel(source.muted ? "Unmute" : "Mute")
            Button { dismiss() } label: {
                Image(systemName: "xmark")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(Ink.paper)
                    .frame(width: 60, height: 60)
                    .background(Ink.primary, in: .circle)
            }
            .buttonStyle(PressStyle(scale: 0.9))
            .accessibilityLabel("End voice errands")
        }
        .padding(.bottom, 18)
        .sensoryFeedback(.selection, trigger: source.muted)
    }

    // MARK: Errands by voice

    /// Turns what you said into an errand and returns what the bot says back.
    static func dispatch(_ said: String, store: AppState) async -> String {
        guard store.composerReady else { return "I need a model first. Download one, then send me." }
        guard store.desk.active.count < AppState.crewSize else { return "My hands are full. Ask me again in a minute." }
        let title = Errand.title(for: said)
        store.runErrand(title: title, instructions: said, sources: Self.sources(for: said))
        let replies = ["On it.", "Got it. I’m on it.", "Leave it with me.", "Sure. Back soon."]
        return replies[abs(said.hashValue) % replies.count]
    }

    /// Spoken errands can't pick sources, so they read the ones the words point to, among
    /// those Loocal is already allowed to use. Nothing new is asked for mid-conversation.
    static func sources(for said: String) -> [String] {
        let text = said.lowercased()
        var ids: [String] = []
        let wanted = SourceIntent.wanted(said)
        for id in [Connector.calendar.id, Connector.reminders.id] where wanted.contains(id) && ConnectorHub.isAllowed(id) { ids.append(id) }
        if wanted.contains(Connector.clipboard.id) || ["copied", "copié"].contains(where: text.contains) { ids.append(Connector.clipboard.id) }
        if WebSearch.wantsSearch(said) || ["find", "web", "online", "trouve"].contains(where: text.contains) { ids.append(Connector.web.id) }
        return ids
    }

    /// Reads each finished errand from this conversation back once.
    private func readBack(_ done: [UUID]) {
        guard let id = done.first(where: { dispatched.contains($0) && !announced.contains($0) }),
              let run = store.desk.runs.first(where: { $0.id == id }) else { return }
        announced.insert(id)
        hops += 1
        let plain = String(MessageView.rendered(run.text).characters)
        let lines = plain.split(separator: "\n", omittingEmptySubsequences: true).map(String.init)
        let body = (lines.count > 1 ? lines.dropFirst() : lines[...]).joined(separator: " ")
        let firstSentence = body.split(whereSeparator: { ".!?".contains($0) }).first.map { String($0).trimmingCharacters(in: .whitespaces) + "." } ?? ""
        // Short requests are read back whole; long ones by their title.
        let request = Errand.stripped(run.instructions).trimmingCharacters(in: .punctuationCharacters)
        let what = request.split(separator: " ").count <= 10 ? request : run.title
        source.announce("Done: \(what.prefix(1).lowercased() + what.dropFirst()). \(firstSentence)")
        store.desk.markSeen(id)
    }
}

/// Scripted stand-in for the simulator demo: no microphone, no speech.
@MainActor @Observable
final class ErrandVoiceMock: ErrandVoiceSource {
    private(set) var phase: ConversationPhase = .listening
    private(set) var lines: [ConversationLine] = []
    var muted = false
    @ObservationIgnored var respond: ((String) async -> String)?
    @ObservationIgnored private var lastWord = Date.distantPast
    @ObservationIgnored private var task: Task<Void, Never>?
    @ObservationIgnored private var pending: [String] = []

    private let script = ["Find me a quiet café for Sunday morning", "And draft a thank you note to Sam"]

    func start() {
        guard task == nil else { return }
        task = Task { [weak self] in await self?.run() }
    }

    func stop() { task?.cancel(); task = nil }

    func announce(_ text: String) { pending.append(text) }

    private func run() async {
        for said in script {
            phase = .listening
            try? await Task.sleep(for: .milliseconds(900))
            await speak(said, fromUser: true, pace: 0.24)
            phase = .thinking
            try? await Task.sleep(for: .milliseconds(500))
            let reply = await respond?(said) ?? "On it."
            phase = .speaking
            await speak(reply, fromUser: false, pace: 0.2)
        }
        while !Task.isCancelled {
            phase = .listening
            try? await Task.sleep(for: .milliseconds(400))
            if !pending.isEmpty {
                phase = .speaking
                await speak(pending.removeFirst(), fromUser: false, pace: 0.18)
            }
        }
    }

    private func speak(_ text: String, fromUser: Bool, pace: Double) async {
        lines.append(ConversationLine(fromUser: fromUser, text: ""))
        for word in text.split(separator: " ") {
            guard !Task.isCancelled else { return }
            lines[lines.count - 1].text += (lines[lines.count - 1].text.isEmpty ? "" : " ") + word
            lastWord = .now
            try? await Task.sleep(for: .seconds(pace))
        }
        if lines.count > 8 { lines.removeFirst(lines.count - 8) }
    }

    func level(at date: Date) -> Double {
        if phase == .thinking || (muted && phase == .listening) { return 0.04 }
        let since = date.timeIntervalSince(lastWord)
        let t = date.timeIntervalSinceReferenceDate
        return min(1, max(0.03, exp(-since * 2.6) * (0.55 + 0.25 * sin(t * 17) + 0.2 * sin(t * 7.3))))
    }
}
