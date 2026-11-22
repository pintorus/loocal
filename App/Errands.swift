import Foundation
import Observation
import SwiftUI
import UserNotifications

// MARK: - Model

/// How often an errand comes back. `now` is a one-off run that never repeats.
enum Cadence: String, Codable, CaseIterable, Identifiable, Sendable {
    case now, daily, weekdays, weekly
    var id: String { rawValue }
    var label: String { switch self { case .now: "Now"; case .daily: "Every day"; case .weekdays: "Weekdays"; case .weekly: "Every week" } }
    var short: String { switch self { case .now: "Now"; case .daily: "Daily"; case .weekdays: "Weekdays"; case .weekly: "Weekly" } }
    var symbol: String { switch self { case .now: "bolt"; case .daily: "sun.max"; case .weekdays: "briefcase"; case .weekly: "calendar" } }
}

/// A routine: instructions Loocal carries out on device at a set time.
struct Errand: Codable, Identifiable, Hashable, Sendable {
    var id = UUID()
    var title: String
    var instructions: String
    var cadence: Cadence
    var hour: Int
    var minute: Int
    /// Calendar weekday (1 = Sunday), for weekly errands.
    var weekday: Int
    /// Connector ids read before each run.
    var sources: [String]
    var paused = false
    var createdAt = Date.now
    var lastRun: Date?

    private var weekdays: [Int?] {
        switch cadence {
        case .now: []
        case .daily: [nil]
        case .weekdays: [2, 3, 4, 5, 6]
        case .weekly: [weekday]
        }
    }

    func components(weekday: Int?) -> DateComponents {
        var c = DateComponents()
        c.hour = hour
        c.minute = minute
        c.weekday = weekday
        return c
    }

    /// Every trigger a repeating notification needs (weekdays need five).
    var triggers: [DateComponents] { weekdays.map { components(weekday: $0) } }

    func next(after date: Date) -> Date? {
        weekdays.compactMap { Calendar.current.nextDate(after: date, matching: components(weekday: $0), matchingPolicy: .nextTime) }.min()
    }

    var nextRun: Date? { paused ? nil : next(after: max(lastRun ?? createdAt, .now.addingTimeInterval(-1))) }
    /// A scheduled time has passed since the last run (or since it was made).
    var isDue: Bool { !paused && (next(after: lastRun ?? createdAt).map { $0 <= .now } ?? false) }

    var time: String {
        Calendar.current.date(from: components(weekday: nil)).map { $0.formatted(.dateTime.hour().minute()) } ?? ""
    }

    var schedule: String {
        switch cadence {
        case .now: "Once"
        case .weekly: "\(Calendar.current.weekdaySymbols[(weekday - 1 + 7) % 7]) · \(time)"
        default: "\(cadence.short) · \(time)"
        }
    }

    /// Spoken (and typed) openers that say nothing about the errand itself.
    private static let fillers = ["and", "also", "then", "so", "ok", "okay", "please", "can you", "could you", "would you", "i need you to", "i want you to", "i'd like you to", "et", "puis", "peux-tu", "tu peux", "pourrais-tu", "s'il te plaît"]

    /// Small words a title shouldn't trail off on.
    private static let dangling: Set<String> = ["to", "a", "an", "the", "for", "of", "with", "and", "or", "in", "on", "at", "my", "de", "à", "pour", "le", "la", "les", "un", "une", "et", "avec", "mon", "ma"]

    static func title(for instructions: String) -> String {
        var words = stripped(instructions).split(whereSeparator: \.isWhitespace).prefix(6).map(String.init)
        while words.count > 1, let last = words.last, dangling.contains(last.lowercased()) { words.removeLast() }
        let clean = words.joined(separator: " ").trimmingCharacters(in: .punctuationCharacters)
        return clean.isEmpty ? "Errand" : clean.prefix(1).uppercased() + clean.dropFirst()
    }

    /// The request without spoken openers like "and" or "can you".
    static func stripped(_ instructions: String) -> String {
        var text = instructions.trimmingCharacters(in: .whitespacesAndNewlines)
        var trimmed = true
        while trimmed {
            trimmed = false
            for filler in fillers where text.lowercased().hasPrefix(filler + " ") || text.lowercased().hasPrefix(filler + ",") {
                text = String(text.dropFirst(filler.count + 1)).trimmingCharacters(in: .whitespacesAndNewlines.union(.punctuationCharacters))
                trimmed = true
            }
        }
        return text
    }
}

/// One time Loocal carried out an errand, and what came back.
struct ErrandRun: Codable, Identifiable, Hashable, Sendable {
    enum Status: String, Codable, Sendable { case queued, running, done, stopped, failed }
    static let nextUp = "Next up"
    var id = UUID()
    var errandID: UUID?
    var title: String
    var instructions: String
    var sources: [String]
    var startedAt = Date.now
    var text = ""
    var status: Status = .queued
    var note: String?
    /// What it's doing right now ("Reading Calendar", "Next up"), while on the go.
    var stage: String?
    /// Tokens written so far, for the crew card's progress line.
    var tokens: Int?
    /// Opened since it finished; unseen results stay with the crew on the errand page.
    var seen: Bool?
    /// The persona that ran it, which also decides its bot's shape.
    var personaID: UUID?

    var isActive: Bool { status == .queued || status == .running }
}

/// A ready-made errand for the suggestion row.
struct ErrandIdea: Hashable {
    let title: String
    let detail: String
    let instructions: String
    let sources: [String]
    let cadence: Cadence
    let hour: Int
    let minute: Int

    static let all = [
        ErrandIdea(title: "Morning brief", detail: "every day at 7:30", instructions: "Give me a calm morning brief: what’s on my calendar today, which reminders are due, and the one thing worth doing first.", sources: [Connector.calendar.id, Connector.reminders.id], cadence: .daily, hour: 7, minute: 30),
        ErrandIdea(title: "Plan my week", detail: "Mondays at 8:00", instructions: "Look at my week ahead and lay it out day by day. Flag clashes, tight days and anything I should prepare for.", sources: [Connector.calendar.id], cadence: .weekly, hour: 8, minute: 0),
        ErrandIdea(title: "Sort my reminders", detail: "right now", instructions: "Sort my open reminders into Today, This week and Someday, and suggest which ones to drop.", sources: [Connector.reminders.id], cadence: .now, hour: 9, minute: 0),
        ErrandIdea(title: "Tidy the clipboard", detail: "right now", instructions: "Turn what I last copied into a clean, well-structured note with a short title.", sources: [Connector.clipboard.id], cadence: .now, hour: 9, minute: 0),
    ]
}

// MARK: - Store

/// Errands, their results, and the composer draft. Kept on this iPhone only.
@MainActor @Observable
final class ErrandDesk {
    var errands: [Errand] { didSet { save(); reschedule() } }
    var runs: [ErrandRun]
    var draft = ""
    var cadence: Cadence = .now
    var time: Date = Calendar.current.date(bySettingHour: 8, minute: 0, second: 0, of: .now) ?? .now
    var sources: Set<String> = []
    /// The persona errands go out as; separate from the chat's.
    var personaID: UUID? { didSet { UserDefaults.standard.set(personaID?.uuidString, forKey: "errands.persona") } }
    @ObservationIgnored private let url: URL
    @ObservationIgnored private var lastSaved = Date.distantPast
    @ObservationIgnored private var muted = false

    static let systemPrompt = """
    You are Loocal, running an errand for the user on their iPhone, fully offline. Do the whole task in one go \
    without asking questions; if a general detail is missing, make a sensible assumption and say so in one line. \
    Never invent the user's own events, reminders, plans or facts: use only what's given below, and if it isn't there, \
    say Loocal couldn't read it. \
    Reply with the finished result only: a short first line as a title, then the result, concise and easy to scan.
    """

    init(directory: URL) {
        url = directory.appendingPathComponent("errands.json")
        personaID = UserDefaults.standard.string(forKey: "errands.persona").flatMap(UUID.init(uuidString:))
        struct Archive: Codable { var errands: [Errand]; var runs: [ErrandRun] }
        let archive = (try? Data(contentsOf: url)).flatMap { try? JSONDecoder().decode(Archive.self, from: $0) }
        errands = archive?.errands ?? []
        // A run that was cut off by the app quitting keeps its partial text.
        runs = (archive?.runs ?? []).map { run in
            var run = run
            if run.isActive { run.status = .stopped; run.stage = nil }
            return run
        }
    }

    var running: ErrandRun? { runs.first { $0.status == .running } }
    var active: [ErrandRun] { runs.filter(\.isActive) }
    /// What sits with the bot: errands on the go (oldest first), then today's results you
    /// haven't opened yet (newest first). Four at most, so the whole crew is always in view.
    var crew: [ErrandRun] {
        let going = runs.filter(\.isActive).reversed()
        let back = runs.filter { !$0.isActive && $0.seen != true && $0.startedAt > .now.addingTimeInterval(-12 * 3_600) }
        return Array((Array(going) + back).prefix(AppState.crewSize))
    }
    func runs(for errand: Errand) -> [ErrandRun] { runs.filter { $0.errandID == errand.id } }

    // MARK: Runs

    func start(_ run: ErrandRun) {
        runs.insert(run, at: 0)
        if runs.count > 200 { runs.removeLast(runs.count - 200) }
        save()
    }

    func update(_ id: UUID, text: String, tokens: Int? = nil) {
        guard let index = runs.firstIndex(where: { $0.id == id }) else { return }
        runs[index].text = text
        if let tokens { runs[index].tokens = tokens }
        if Date().timeIntervalSince(lastSaved) > 1 { save() }
    }

    func finish(_ id: UUID, status: ErrandRun.Status, note: String? = nil) {
        guard let index = runs.firstIndex(where: { $0.id == id }) else { return }
        runs[index].status = status
        runs[index].note = note
        runs[index].stage = nil
        save()
    }

    func setStage(_ id: UUID, _ stage: String?) {
        guard let index = runs.firstIndex(where: { $0.id == id }), runs[index].isActive else { return }
        runs[index].stage = stage
    }

    func begin(_ id: UUID) {
        guard let index = runs.firstIndex(where: { $0.id == id }) else { return }
        runs[index].status = .running
        runs[index].stage = "Thinking"
        runs[index].tokens = 0
        save()
    }

    func markSeen(_ id: UUID) {
        guard let index = runs.firstIndex(where: { $0.id == id }), !runs[index].isActive, runs[index].seen != true else { return }
        runs[index].seen = true
        save()
    }

    func interruptRunning() {
        for index in runs.indices where runs[index].status == .running { runs[index].status = .stopped; runs[index].stage = nil }
        save()
    }

    func delete(_ run: ErrandRun) {
        runs.removeAll { $0.id == run.id }
        save()
    }

    // MARK: Routines

    func add(_ errand: Errand) {
        // Scheduling the same errand again replaces it rather than doubling up.
        errands.removeAll { $0.instructions == errand.instructions && $0.cadence == errand.cadence }
        errands.insert(errand, at: 0)
        Self.requestNotifications()
    }

    func markRan(_ id: UUID) {
        guard let index = errands.firstIndex(where: { $0.id == id }) else { return }
        errands[index].lastRun = .now
    }

    func togglePause(_ errand: Errand) {
        guard let index = errands.firstIndex(where: { $0.id == errand.id }) else { return }
        errands[index].paused.toggle()
        // Resuming starts the clock again, so a paused morning doesn't fire on unpause.
        if !errands[index].paused { errands[index].lastRun = .now }
    }

    func delete(_ errand: Errand) { errands.removeAll { $0.id == errand.id } }

    // MARK: Persistence and notifications

    func save() {
        guard !muted else { return }
        struct Archive: Codable { var errands: [Errand]; var runs: [ErrandRun] }
        lastSaved = .now
        do {
            let data = try JSONEncoder().encode(Archive(errands: errands, runs: runs))
            try data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        } catch {}
    }

    /// Simulator demo: sample data that's never written to disk.
    func loadSamples(_ errands: [Errand], runs: [ErrandRun]) {
        muted = true
        self.errands = errands
        self.runs = runs
    }

    /// iOS can't wake a multi-gigabyte model on a timer, so each scheduled time posts a
    /// notification; opening Loocal from it (or being in it) runs the errand on device.
    private func reschedule() {
        guard !muted else { return }
        let center = UNUserNotificationCenter.current()
        let errands = errands
        center.getPendingNotificationRequests { pending in
            center.removePendingNotificationRequests(withIdentifiers: pending.map(\.identifier).filter { $0.hasPrefix("errand-") })
            for errand in errands where !errand.paused {
                for (i, trigger) in errand.triggers.enumerated() {
                    let content = UNMutableNotificationContent()
                    content.title = errand.title
                    content.body = "Ready to run. Open Loocal and it’s done on this iPhone."
                    content.sound = .default
                    center.add(UNNotificationRequest(identifier: "errand-\(errand.id)-\(i)", content: content, trigger: UNCalendarNotificationTrigger(dateMatching: trigger, repeats: true)))
                }
            }
        }
    }

    static func requestNotifications() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in }
    }

    /// Lets you know a result is in when Loocal finished it in the background.
    static func announce(_ run: ErrandRun) {
        let content = UNMutableNotificationContent()
        content.title = run.title
        content.body = String(MessageView.rendered(run.text).characters).split(separator: "\n").dropFirst().first.map(String.init) ?? "Your errand is done."
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: "errand-done-\(run.id)", content: content, trigger: nil))
    }
}

// MARK: - New errand

/// The errand page: the bot in the middle, the crew of errands on the go beneath it, and
/// the composer. Sending hands a ticket up to the bot, which catches it.
struct ErrandView: View {
    @Bindable var store: AppState
    let openResults: () -> Void
    let openRoutines: () -> Void
    let continueInChat: (ErrandRun) -> Void
    var openPersonas: () -> Void = {}
    @FocusState private var focused: Bool
    @State private var reaction = 0
    @State private var reactionKind: BotReaction = .hop
    /// Left alone for a while, the bot dozes off; any touch wakes it.
    @State private var dozing = false
    @State private var lastTouch = Date.now
    // The persona arc: hold the bot, slide to a bot, let go.
    @State private var picking = false
    @State private var pressing = false
    @State private var moved = false
    @State private var openedThisPress = false
    @State private var hovered: Int?
    @State private var hold: DispatchWorkItem?
    @State private var voiceOpen = false
    @Namespace private var voiceSource
    /// A short-lived face and line after a poke or a handoff.
    @State private var burst: (mood: BotMood, line: (String, String))?
    @State private var burstTask: Task<Void, Never>?
    @State private var ticket: Ticket?
    @State private var botFrame: CGRect = .zero
    @State private var composerFrame: CGRect = .zero
    @State private var opened: ErrandRun?

    private struct Ticket: Equatable {
        var title: String
        var symbol: String
        var launched = false
    }

    private var desk: ErrandDesk { store.desk }
    private var crew: [ErrandRun] { desk.crew }
    private var onTheGo: [ErrandRun] { crew.filter(\.isActive) }

    var body: some View {
        page
        // A result coming in gets a little hop.
        .onChange(of: crew.filter { $0.status == .done }.count) { old, new in
            if new > old { react(.jump); flash(.proud, ("Back with", "results."), for: 2.2) }
        }
        .onChange(of: crew.filter { $0.status == .failed }.count) { old, new in
            if new > old { react(.shake) }
        }
        .onChange(of: focused) { _, _ in wake() }
        .onChange(of: picking) { _, _ in wake() }
        .task { await watchIdle() }
        .sensoryFeedback(.impact(weight: .light, intensity: 0.6), trigger: reaction)
    }

    /// Nodding off after 45 s with nothing going on.
    private func watchIdle() async {
        while !Task.isCancelled {
            try? await Task.sleep(for: .seconds(5))
            let idle = isIdle()
            if idle != dozing { withAnimation(.smooth(duration: 0.6)) { dozing = idle } }
        }
    }

    private var page: some View {
        VStack(spacing: 0) {
            ModelSelector(options: ModelChoice.all, disabled: store.isWorking, selection: $store.selectedModelID, dimmed: !store.download.isReady)
                .frame(maxWidth: .infinity)
                .padding(.top, 6).padding(.bottom, 8)
                .dismissesKeyboardOnTap()
                .zIndex(1)
            stage
                .frame(maxHeight: .infinity)
                .dismissesKeyboardOnTap()
            ZStack {
                if !crew.isEmpty {
                    CrewStrip(runs: crew, open: { opened = $0 }, dismiss: { run in withAnimation(.spring(response: 0.4, dampingFraction: 0.85)) { desk.markSeen(run.id) } }, stop: store.cancelErrand)
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                } else if store.composerReady && desk.draft.isEmpty && !focused {
                    ErrandIdeas(choose: useIdea)
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                }
            }
            .animation(.spring(response: 0.45, dampingFraction: 0.85), value: crew.isEmpty)
            composer
                .onGeometryChange(for: CGRect.self) { $0.frame(in: .named("errandPage")) } action: { composerFrame = $0 }
        }
        .coordinateSpace(.named("errandPage"))
        .overlay { picker }
        .overlay { flight }
        .fullScreenCover(isPresented: $voiceOpen) {
            ErrandVoiceView(store: store, source: Self.voiceSource(store), shape: shape)
                .navigationTransition(.zoom(sourceID: "errandVoice", in: voiceSource))
        }
        .animation(.spring(response: 0.4, dampingFraction: 0.85), value: desk.draft.isEmpty)
        .animation(.spring(response: 0.45, dampingFraction: 0.85), value: focused)
        .background(Ink.paper)
        .sheet(item: $opened) { run in
            ErrandRunSheet(store: store, id: run.id, continueInChat: { opened = nil; continueInChat($0) }, again: { opened = nil; store.rerun($0) })
        }
    }

    // MARK: The bot and what it says

    private var shape: BotShape { BotShape(personaID: store.errandPersona?.id) }

    private var stage: some View {
        VStack(spacing: 18) {
            Spacer(minLength: 8)
            PebbleBot(mood: mood, shape: shape, size: focused ? 96 : 128, gaze: gaze, reaction: reaction, tint: BotTint.color(for: store.errandPersona?.id), reactionKind: reactionKind)
                // A new persona swaps the body with a little twirl.
                .id(store.errandPersona?.id)
                .transition(.asymmetric(
                    insertion: .scale(scale: 0.4).combined(with: .opacity).combined(with: .modifier(active: Twirl(angle: -40), identity: Twirl(angle: 0))),
                    removal: .scale(scale: 0.4).combined(with: .opacity)))
                .scaleEffect(pressing && !picking ? 0.94 : 1)
                .animation(.spring(response: 0.3, dampingFraction: 0.6), value: pressing)
                .contentShape(.rect)
                .gesture(botGesture)
                .onGeometryChange(for: CGRect.self) { $0.frame(in: .named("errandPage")) } action: { botFrame = $0 }
                .accessibilityElement()
                .accessibilityLabel(store.errandPersona.map { "Errand bot, \($0.name)" } ?? "Errand bot")
                .accessibilityHint("Hold to choose who runs your errands")
                .accessibilityAddTraits(.isButton)
                .accessibilityIdentifier("errandBot")
                .accessibilityActions {
                    ForEach(Array(pickItems.enumerated()), id: \.offset) { _, item in
                        Button(label(item)) { choose(item) }
                    }
                }
            ZStack {
                if let line {
                    Text("\(line.0) \(Text(line.1).font(.serif(focused ? 30 : 36, italic: true)))")
                        .font(.serif(focused ? 30 : 36)).tracking(-0.4)
                        .foregroundStyle(Ink.primary)
                        .multilineTextAlignment(.center)
                        .id(line.0 + line.1)
                        .transition(.blurReplace.combined(with: .scale(0.96)))
                }
            }
            .frame(minHeight: 44)
            .padding(.horizontal, 32)
            .opacity(picking ? 0.15 : 1)
            .animation(.spring(response: 0.45, dampingFraction: 0.85), value: line.map { $0.0 + $0.1 })
            Spacer(minLength: 8)
        }
        .frame(maxWidth: .infinity)
        .animation(.spring(response: 0.5, dampingFraction: 0.78), value: store.errandPersona?.id)
    }

    private struct Twirl: ViewModifier {
        let angle: Double
        func body(content: Content) -> some View { content.rotationEffect(.degrees(angle)) }
    }

    private var mood: BotMood {
        if picking { return hovered == nil ? .curious : .excited }
        if let burst { return burst.mood }
        if !store.composerReady || dozing { return .sleepy }
        if focused { return desk.draft.isEmpty ? .attentive : .curious }
        if !desk.draft.isEmpty { return .excited }
        if onTheGo.contains(where: { $0.status == .running }) { return .working }
        if !onTheGo.isEmpty { return .curious }
        if crew.contains(where: { $0.status == .failed }) { return .dizzy }
        if crew.contains(where: { $0.status == .done }) { return .happy }
        return .neutral
    }

    /// While you type it reads along the line; a ticket in flight it watches come in.
    private var gaze: CGPoint? {
        // Picking, it looks up at whichever bot your finger is on.
        if picking {
            guard let hovered else { return CGPoint(x: 0, y: -0.9) }
            let a = angle(hovered)
            return CGPoint(x: cos(a) * 0.9, y: sin(a) * 0.9)
        }
        if ticket != nil { return CGPoint(x: 0, y: 0.9) }
        if focused {
            let last = desk.draft.split(separator: "\n", omittingEmptySubsequences: false).last.map(\.count) ?? 0
            return CGPoint(x: -0.6 + Double(last % 32) / 32 * 1.2, y: 0.85)
        }
        if !crew.isEmpty { return CGPoint(x: 0, y: 0.6) }
        return nil
    }

    private static let counts = ["", "one", "two", "three", "four", "five", "six"]

    /// What the bot says. Nothing at rest: the bot speaks for itself.
    private var line: (String, String)? {
        if picking, let hovered { return pickLine(pickItems[hovered]) }
        if picking { return ("Who’s", "going?") }
        if let burst { return burst.line }
        if !store.composerReady { return ("I need a model", "first.") }
        if focused && desk.draft.isEmpty { return ("Mm-hm,", "go on.") }
        if !desk.draft.isEmpty { return desk.cadence == .now ? ("Say the", "word.") : ("I’ll set an", "alarm.") }
        if onTheGo.count == 1 { return ("On", "it.") }
        if onTheGo.count > 1 { return ("Juggling", "\(Self.counts[min(onTheGo.count, 6)]).") }
        if crew.contains(where: { $0.status == .failed }) { return ("That one", "tripped me.") }
        if crew.contains(where: { $0.status == .done }) { return ("Back with", "results.") }
        return nil
    }

    // MARK: Persona arc

    private enum PickItem: Hashable { case loocal, persona(Persona), add }

    private var pickItems: [PickItem] {
        // Whoever runs errands now is already the big bot, so the arc only offers the others.
        ([.loocal] + store.personas.map { .persona($0) } + (store.personas.count < Persona.limit ? [.add] : [])).filter { !isCurrent($0) }
    }

    /// Items sit on an arc over the bot, fanned evenly; more items, wider fan.
    private func angle(_ i: Int) -> Double {
        let n = pickItems.count
        let span = min(150, 42 * Double(n - 1))
        let degrees = n == 1 ? -90 : -90 - span / 2 + span * Double(i) / Double(n - 1)
        return degrees * .pi / 180
    }

    private var radius: CGFloat { botFrame.width / 2 + 78 }

    private func spot(_ i: Int) -> CGPoint {
        CGPoint(x: botFrame.midX + radius * cos(angle(i)), y: botFrame.midY + radius * sin(angle(i)))
    }

    private func hit(_ point: CGPoint) -> Int? {
        func distance(_ i: Int) -> CGFloat { hypot(spot(i).x - point.x, spot(i).y - point.y) }
        guard let near = pickItems.indices.min(by: { distance($0) < distance($1) }), distance(near) < 44 else { return nil }
        return near
    }

    private func isCurrent(_ item: PickItem) -> Bool {
        switch item {
        case .loocal: store.errandPersona == nil
        case .persona(let persona): store.desk.personaID == persona.id
        case .add: false
        }
    }

    private func label(_ item: PickItem) -> String {
        switch item {
        case .loocal: "Loocal"
        case .persona(let persona): persona.name
        case .add: "New persona"
        }
    }

    private func pickLine(_ item: PickItem) -> (String, String) {
        switch item {
        case .loocal: ("Just", "me.")
        case .persona(let persona): ("Send", persona.name + "?")
        case .add: ("Someone", "new?")
        }
    }

    private var botGesture: some Gesture {
        DragGesture(minimumDistance: 0, coordinateSpace: .named("errandPage"))
            .onChanged { value in
                if !pressing {
                    pressing = true
                    moved = false
                    openedThisPress = false
                    if !picking {
                        let item = DispatchWorkItem { if pressing && !picking { openPicker() } }
                        hold = item
                        DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(280), execute: item)
                    }
                }
                if hypot(value.translation.width, value.translation.height) > 10 { moved = true }
                // Sliding up opens the arc straight away.
                if !picking && value.translation.height < -24 { hold?.cancel(); openPicker() }
                if picking {
                    let now = hit(value.location)
                    if now != hovered { withAnimation(.spring(response: 0.3, dampingFraction: 0.7)) { hovered = now } }
                }
            }
            .onEnded { value in
                hold?.cancel()
                pressing = false
                if picking {
                    if let index = hit(value.location) { choose(pickItems[index]) }
                    else if !(openedThisPress && !moved) { closePicker() } // a still hold leaves it open for a tap
                    hovered = nil
                } else if !moved {
                    poke()
                }
            }
    }

    private func openPicker() {
        openedThisPress = true
        focused = false
        withAnimation(.spring(response: 0.45, dampingFraction: 0.78)) { picking = true }
    }

    private func closePicker() {
        withAnimation(.spring(response: 0.4, dampingFraction: 0.85)) { picking = false; hovered = nil }
    }

    private func choose(_ item: PickItem) {
        closePicker()
        switch item {
        case .add:
            openPersonas()
            return
        case .loocal:
            desk.personaID = nil
        case .persona(let persona):
            desk.personaID = persona.id
        }
        react(.spin)
        flash(.love, item == .loocal ? ("Back to", "me.") : (label(item), "here."))
    }

    /// The fan of bots over the main one, with a faint curved track behind them.
    private var picker: some View {
        let items = pickItems
        return ZStack {
            // Tapping anywhere else folds the arc away.
            Color.black.opacity(0.001)
                .ignoresSafeArea()
                .onTapGesture { closePicker() }
                .allowsHitTesting(picking && !pressing)
            // A curved grey band, the model selector's fill, with round ends: the bots ride it.
            Path { path in
                path.addArc(center: CGPoint(x: botFrame.midX, y: botFrame.midY), radius: radius,
                            startAngle: .radians(angle(0)), endAngle: .radians(angle(items.count - 1) + 0.0001), clockwise: false)
            }
            .trim(from: 0, to: picking ? 1 : 0)
            .stroke(Ink.fill, style: StrokeStyle(lineWidth: 78, lineCap: .round))
            .opacity(picking ? 1 : 0)
            .animation(.spring(response: 0.45, dampingFraction: 0.85), value: picking)
            .allowsHitTesting(false)
            ForEach(Array(items.enumerated()), id: \.element) { i, item in
                pickBot(item, lit: hovered == i)
                    .scaleEffect(picking ? (hovered == i ? 1.28 : (hovered == nil ? 1 : 0.88)) : 0.3)
                    .opacity(picking ? (hovered == nil || hovered == i ? 1 : 0.5) : 0)
                    .blur(radius: picking ? 0 : 4)
                    .position(picking ? spot(i) : CGPoint(x: botFrame.midX, y: botFrame.midY))
                    .animation(.spring(response: 0.42, dampingFraction: 0.7).delay(picking ? Double(i) * 0.035 : 0), value: picking)
                    .animation(.spring(response: 0.3, dampingFraction: 0.62), value: hovered)
                    .onTapGesture { choose(item) }
                    .allowsHitTesting(picking && !pressing)
            }
        }
        .sensoryFeedback(.selection, trigger: hovered) { _, now in now != nil }
        .sensoryFeedback(.impact(weight: .light), trigger: picking) { _, now in now }
        .accessibilityHidden(true)
    }

    private func pickBot(_ item: PickItem, lit: Bool) -> some View {
        VStack(spacing: 6) {
            ZStack {
                switch item {
                case .loocal:
                    PebbleBot(mood: lit ? .excited : .neutral, shape: .pebble, size: 52, lively: picking)
                case .persona(let persona):
                    PebbleBot(mood: lit ? .excited : .neutral, shape: BotShape(personaID: persona.id), size: 52, lively: picking, tint: BotTint.color(for: persona.id))
                        .overlay(alignment: .bottomTrailing) {
                            Image(systemName: persona.icon)
                                .font(.system(size: 9, weight: .semibold))
                                .foregroundStyle(Ink.primary)
                                .frame(width: 20, height: 20)
                                .background(Ink.paper, in: .circle)
                                .overlay(Circle().stroke(Ink.hairline))
                                .offset(x: 2, y: -4)
                        }
                case .add:
                    Image(systemName: "plus")
                        .font(.system(size: 16, weight: .medium))
                        .foregroundStyle(Ink.secondary)
                        .frame(width: 44, height: 44)
                        .overlay(Circle().stroke(Ink.tertiary, style: StrokeStyle(lineWidth: 1.2, dash: [3, 4])))
                        .frame(width: 52, height: 52)
                }
            }
        }
        .overlay(alignment: .top) {
            Text(label(item))
                .font(.geist(12, .medium)).foregroundStyle(Ink.primary)
                .fixedSize()
                .padding(.horizontal, 9).frame(height: 24)
                .background(Ink.paper, in: .capsule)
                .overlay(Capsule().stroke(Ink.hairline))
                .offset(y: -30)
                .opacity(lit ? 1 : 0)
                .scaleEffect(lit ? 1 : 0.7, anchor: .bottom)
        }
    }

    private func poke() {
        if dozing { wake(); react(.shake); flash(.surprised, ("Huh?", "I’m up.")); return }
        wake()
        let pokes: [(BotMood, BotReaction, (String, String))] = [
            (.happy, .hop, ("Hi", "there.")),
            (.wink, .nod, ("At your", "service.")),
            (.love, .wiggle, ("Hey", "you.")),
            (.surprised, .hop, ("Oh!", "Hello.")),
            (.excited, .spin, ("Point me", "somewhere.")),
            (.annoyed, .shake, ("That", "tickles.")),
        ]
        let pick = pokes[(reaction + 1) % pokes.count]
        react(pick.1)
        flash(pick.0, pick.2)
    }

    /// Plays one of the bot's moves.
    private func react(_ kind: BotReaction) {
        reactionKind = kind
        reaction += 1
    }

    private func isIdle() -> Bool {
        let quiet: Bool = Date.now.timeIntervalSince(lastTouch) > 45
        let calm: Bool = onTheGo.isEmpty && !focused && !picking
        return quiet && calm && burstIsEmpty
    }

    private var burstIsEmpty: Bool { if case .none = burst { return true } else { return false } }

    private func wake() {
        lastTouch = .now
        if dozing { withAnimation(.smooth(duration: 0.4)) { dozing = false } }
    }

    private func flash(_ mood: BotMood, _ line: (String, String), for seconds: Double = 1.6) {
        burstTask?.cancel()
        withAnimation(.spring(response: 0.4, dampingFraction: 0.8)) { burst = (mood, line) }
        burstTask = Task {
            try? await Task.sleep(for: .seconds(seconds))
            guard !Task.isCancelled else { return }
            withAnimation(.spring(response: 0.5, dampingFraction: 0.85)) { burst = nil }
        }
    }

    // MARK: Handing an errand over

    /// The ticket you wrote, flying from the composer into the bot.
    @ViewBuilder private var flight: some View {
        if let ticket {
            HStack(spacing: 6) {
                Image(systemName: ticket.symbol).font(.system(size: 12, weight: .medium))
                Text(ticket.title).font(.geist(14, .medium)).lineLimit(1)
            }
            .foregroundStyle(Ink.primary)
            .padding(.horizontal, 14).frame(height: 36)
            .background(Ink.paper, in: .capsule)
            .overlay(Capsule().stroke(Ink.hairline))
            .scaleEffect(ticket.launched ? 0.2 : 1)
            .opacity(ticket.launched ? 0 : 1)
            .animation(.easeIn(duration: 0.4), value: ticket.launched)
            .position(x: ticket.launched ? botFrame.midX : composerFrame.midX,
                      y: ticket.launched ? botFrame.midY + botFrame.height * 0.08 : composerFrame.minY + 30)
            .animation(.spring(response: 0.46, dampingFraction: 0.8), value: ticket.launched)
            .allowsHitTesting(false)
            .accessibilityHidden(true)
        }
    }

    /// The live microphone pipeline, or a scripted one in the simulator demo.
    static func voiceSource(_ store: AppState) -> any ErrandVoiceSource {
        #if DEBUG && targetEnvironment(simulator)
        if ProcessInfo.processInfo.arguments.contains("-uiDemo") { return ErrandVoiceMock() }
        #endif
        return VoiceConversation(store: store)
    }

    private func useIdea(_ idea: ErrandIdea) {
        withAnimation(.spring(response: 0.4, dampingFraction: 0.85)) {
            desk.draft = idea.instructions
            desk.cadence = idea.cadence
            desk.sources = Set(idea.sources)
            desk.time = Calendar.current.date(bySettingHour: idea.hour, minute: idea.minute, second: 0, of: .now) ?? desk.time
        }
        // Ask for each source's permission now; a declined one is dropped from the errand.
        Task {
            for id in idea.sources where !(await ConnectorHub.requestAccess(for: id)) { desk.sources.remove(id) }
        }
    }

    private var canSend: Bool { !desk.draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && ticket == nil }

    private func send() {
        let instructions = desk.draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !instructions.isEmpty, ticket == nil else { return }
        let cadence = desk.cadence, sources = Array(desk.sources), time = desk.time
        if cadence == .now && desk.active.count >= AppState.crewSize {
            store.error = "The crew’s full: \(AppState.crewSize) errands are on the go. Send this one when one finishes."
            return
        }
        focused = false
        let title = ErrandIdea.all.first { $0.instructions == instructions }?.title ?? Errand.title(for: instructions)
        ticket = Ticket(title: title, symbol: cadence.symbol)
        withAnimation(.spring(response: 0.4, dampingFraction: 0.85)) {
            desk.draft = ""
            desk.cadence = .now
            desk.sources = []
        }
        Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(40))
            ticket?.launched = true
            try? await Task.sleep(for: .milliseconds(400))
            wake()
            react(.jump)
            withAnimation(.spring(response: 0.45, dampingFraction: 0.82)) {
                if cadence == .now {
                    store.runErrand(title: title, instructions: instructions, sources: sources)
                } else {
                    schedule(title: title, instructions: instructions, sources: sources, cadence: cadence, time: time)
                }
            }
            flash(.excited, cadence == .now ? ("On my", "way.") : ("Alarm", "set."))
            try? await Task.sleep(for: .milliseconds(150))
            ticket = nil
        }
    }

    private func schedule(title: String, instructions: String, sources: [String], cadence: Cadence, time: Date) {
        let parts = Calendar.current.dateComponents([.hour, .minute], from: time)
        // Weekly errands repeat on today's weekday; the "Plan my week" idea keeps Mondays.
        let planner = ErrandIdea.all.contains { $0.instructions == instructions && $0.cadence == .weekly }
        let weekday = planner ? 2 : Calendar.current.component(.weekday, from: .now)
        desk.add(Errand(title: title, instructions: instructions, cadence: cadence, hour: parts.hour ?? 8, minute: parts.minute ?? 0, weekday: weekday, sources: sources))
        store.notice = "Scheduled · \(desk.errands[0].schedule). It’s in Routines."
    }

    // MARK: Composer

    private var composer: some View {
        ZStack {
            if store.composerReady {
                input.transition(.blurReplace.combined(with: .scale(0.98)))
            } else {
                DownloadComposer(download: store.download, model: store.model)
                    .transition(.blurReplace.combined(with: .scale(0.98)))
            }
        }
        .animation(.spring(response: 0.45, dampingFraction: 0.85), value: store.composerReady)
        .padding(6)
        .background(Ink.fill, in: .rect(cornerRadius: 25))
        .padding(.horizontal, 12).padding(.bottom, 8)
    }

    private var input: some View {
        @Bindable var desk = store.desk
        return VStack(alignment: .leading, spacing: 8) {
            TextField("Describe an errand", text: $desk.draft, axis: .vertical)
                .font(.geist(16)).foregroundStyle(Ink.primary)
                .lineLimit(1...6).padding(.vertical, 9).padding(.horizontal, 10)
                .focused($focused)
                .accessibilityIdentifier("errandPrompt")
            HStack(spacing: 6) {
                SourcesMenu(store: store)
                CadenceMenu(cadence: $desk.cadence)
                if desk.cadence != .now {
                    DatePicker("Time", selection: $desk.time, displayedComponents: .hourAndMinute)
                        .labelsHidden()
                        .datePickerStyle(.compact)
                        .scaleEffect(0.9)
                        .transition(.scale(scale: 0.8, anchor: .leading).combined(with: .opacity))
                }
                Spacer(minLength: 0)
                Button { focused = false; voiceOpen = true } label: {
                    AudioLinesIcon(size: 19)
                        .foregroundStyle(Ink.primary)
                        .frame(width: 34, height: 38)
                        .contentShape(.rect)
                }
                .buttonStyle(PressStyle(scale: 0.85))
                .matchedTransitionSource(id: "errandVoice", in: voiceSource)
                .accessibilityLabel("Voice errands")
                .accessibilityIdentifier("errandVoice")
                Button(action: send) {
                    Image(systemName: desk.cadence == .now ? "arrow.up" : "plus")
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(BotTint.color(for: store.errandPersona?.id) == nil ? Ink.paper : .white)
                        .contentTransition(.symbolEffect(.replace))
                        .frame(width: 38, height: 38)
                        // Tinted like the errand bot, so you can see who you're sending.
                        .background((BotTint.color(for: store.errandPersona?.id) ?? Ink.primary).opacity(canSend ? 1 : 0.25), in: .circle)
                        .animation(.smooth(duration: 0.35), value: store.errandPersona?.id)
                }
                .buttonStyle(PressStyle(scale: 0.88))
                .disabled(!canSend)
                .accessibilityLabel(desk.cadence == .now ? "Hand over errand" : "Schedule errand")
                .accessibilityIdentifier("errandSend")
            }
            .animation(.spring(response: 0.35, dampingFraction: 0.8), value: desk.cadence)
        }
    }
}

// MARK: - The crew

/// Errands on the go, side by side: each one gets its own little bot.
private struct CrewStrip: View {
    let runs: [ErrandRun]
    let open: (ErrandRun) -> Void
    let dismiss: (ErrandRun) -> Void
    let stop: (ErrandRun) -> Void

    var body: some View {
        LazyVGrid(columns: [GridItem(.flexible(), spacing: 8), GridItem(.flexible(), spacing: 8)], spacing: 8) {
            ForEach(runs) { run in
                Button { open(run) } label: { CrewCard(run: run) }
                    .buttonStyle(PressStyle(scale: 0.97))
                    .contextMenu {
                        if run.isActive {
                            Button("Stop", systemImage: "stop", role: .destructive) { stop(run) }
                        } else {
                            Button("Dismiss", systemImage: "checkmark") { dismiss(run) }
                        }
                    }
                    .transition(.asymmetric(
                        insertion: .scale(scale: 0.5, anchor: .top).combined(with: .opacity).combined(with: .offset(y: -40)),
                        removal: .scale(scale: 0.85).combined(with: .opacity)))
            }
        }
        .padding(.horizontal, 12)
        .padding(.bottom, 10)
        .animation(.spring(response: 0.5, dampingFraction: 0.8), value: runs.map(\.id))
    }
}

private struct CrewCard: View {
    let run: ErrandRun

    private var status: String {
        switch run.status {
        case .queued: run.stage ?? ErrandRun.nextUp
        case .running: run.text.isEmpty ? (run.stage ?? "Thinking") : "Writing"
        case .done: "Done · tap to read"
        case .failed: "Tripped · tap to see"
        case .stopped: "Stopped"
        }
    }

    var body: some View {
        HStack(spacing: 10) {
            // Waiting bots peek toward the one that's working.
            PebbleBot(mood: run.mood, shape: BotShape(personaID: run.personaID), size: 32, gaze: run.status == .queued ? CGPoint(x: -0.8, y: 0.1) : nil, lively: run.isActive, tint: BotTint.color(for: run.personaID))
            VStack(alignment: .leading, spacing: 2) {
                Text(run.title).font(.geist(14, .medium)).foregroundStyle(Ink.primary).lineLimit(1)
                Text(status).font(.geist(12)).foregroundStyle(Ink.secondary).lineLimit(1)
                    .shimmer(run.status == .running && run.text.isEmpty)
                    .contentTransition(.opacity)
                    .animation(.smooth, value: status)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.leading, 10).padding(.trailing, 12)
        .frame(height: 60)
        .background(alignment: .bottom) {
            if run.status == .running {
                GeometryReader { proxy in
                    Capsule().fill(Ink.hairline)
                        .overlay(alignment: .leading) {
                            Capsule().fill(Ink.primary)
                                .frame(width: max(4, proxy.size.width * min(Double(run.tokens ?? 0) / Double(ModelSpec.maxOutputTokens) * 2.5, 1)))
                                .animation(.smooth(duration: 0.4), value: run.tokens)
                        }
                }
                .frame(height: 2)
                .padding(.horizontal, 18).padding(.bottom, 5)
                .transition(.opacity)
            }
        }
        .background(run.status == .done ? Ink.fill : Ink.paper, in: .rect(cornerRadius: 20))
        .overlay(RoundedRectangle(cornerRadius: 20).stroke(Ink.hairline))
        .contentShape(.rect(cornerRadius: 20))
        .animation(.spring(response: 0.4, dampingFraction: 0.85), value: run.status)
        .accessibilityElement(children: .combine)
    }
}

private struct ErrandIdeas: View {
    let choose: (ErrandIdea) -> Void
    @State private var appeared = false

    var body: some View {
        ScrollView(.horizontal) {
            HStack(spacing: 8) {
                ForEach(Array(ErrandIdea.all.enumerated()), id: \.element.title) { i, idea in
                    Button { choose(idea) } label: {
                        VStack(alignment: .leading, spacing: 3) {
                            HStack(spacing: 5) {
                                Image(systemName: idea.cadence.symbol).font(.system(size: 11, weight: .medium))
                                Text(idea.title).font(.geist(14, .medium))
                            }
                            .foregroundStyle(Ink.primary)
                            Text(idea.detail).font(.geist(13)).foregroundStyle(Ink.secondary)
                        }
                        .padding(.horizontal, 14).padding(.vertical, 11)
                        .overlay(RoundedRectangle(cornerRadius: 16).stroke(Ink.hairline))
                        .contentShape(.rect(cornerRadius: 16))
                    }
                    .buttonStyle(PressStyle())
                    .rise(appeared, delay: 0.15 + Double(i) * 0.07, distance: 10)
                }
            }.padding(.horizontal, 16)
        }
        .scrollIndicators(.hidden)
        .padding(.bottom, 10)
        .onAppear { appeared = true }
    }
}

/// Which connected sources the errand reads before it runs.
private struct SourcesMenu: View {
    @Bindable var store: AppState

    var body: some View {
        let desk = store.desk
        let chosen = store.connectors.filter { desk.sources.contains($0.id) }
        Menu {
            ForEach(store.connectors) { connector in
                Button { toggle(connector) } label: {
                    Label(connector.name, systemImage: desk.sources.contains(connector.id) ? "checkmark" : connector.icon)
                }
            }
        } label: {
            HStack(spacing: 5) {
                if chosen.isEmpty {
                    Image(systemName: "square.stack.3d.up").font(.system(size: 13))
                    Text("Sources").font(.geist(13, .medium))
                } else {
                    ForEach(chosen.prefix(3)) { Image(systemName: $0.icon).font(.system(size: 13)) }
                    if chosen.count > 3 { Text("+\(chosen.count - 3)").font(.geist(12, .medium)) }
                }
            }
            .foregroundStyle(chosen.isEmpty ? Ink.secondary : Ink.primary)
            .padding(.horizontal, 11).frame(height: 32)
            .background(Ink.paper, in: .capsule)
            .contentShape(.capsule)
        }
        .menuActionDismissBehavior(.disabled)
        .accessibilityLabel("Sources")
        .accessibilityIdentifier("errandSources")
    }

    private func toggle(_ connector: Connector) {
        let desk = store.desk
        if desk.sources.contains(connector.id) { desk.sources.remove(connector.id); return }
        Task {
            guard await ConnectorHub.requestAccess(for: connector.id) else {
                store.error = "Allow Loocal to access \(connector.name) in Settings to use it."
                return
            }
            desk.sources.insert(connector.id)
        }
    }
}

private struct CadenceMenu: View {
    @Binding var cadence: Cadence

    var body: some View {
        Menu {
            Picker("Repeat", selection: $cadence) {
                ForEach(Cadence.allCases) { Label($0.label, systemImage: $0.symbol).tag($0) }
            }
        } label: {
            HStack(spacing: 5) {
                Image(systemName: cadence.symbol).font(.system(size: 12))
                Text(cadence.short).font(.geist(13, .medium))
            }
            .foregroundStyle(Ink.primary)
            .padding(.horizontal, 11).frame(height: 32)
            .background(Ink.paper, in: .capsule)
            .contentShape(.capsule)
            .contentTransition(.opacity)
        }
        .accessibilityLabel("Repeat: \(cadence.label)")
        .accessibilityIdentifier("errandCadence")
    }
}

struct ErrandHeader: View {
    let run: ErrandRun
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 6) {
                ErrandStatus(run: run, size: 22)
                Text(run.status == .running ? "On it" : run.status == .queued ? (run.stage ?? ErrandRun.nextUp) : run.startedAt.formatted(.dateTime.weekday(.wide).hour().minute()))
                    .font(.geist(12, .medium)).foregroundStyle(Ink.secondary)
                    .contentTransition(.opacity)
            }
            Text(run.title).font(.serif(30)).tracking(-0.3).foregroundStyle(Ink.primary)
            Text(run.instructions).font(.geist(14)).foregroundStyle(Ink.secondary).lineLimit(3)
        }
    }
}

struct ErrandBody: View {
    let run: ErrandRun
    var phase = ""

    private var shown: String { StreamingText.stable(run.text, live: run.status == .running) }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if shown.isEmpty && run.isActive {
                HStack(spacing: 10) {
                    SpiralLoader(size: 18)
                    Text(run.stage ?? "Working")
                        .font(.geist(15)).foregroundStyle(Ink.secondary)
                        .contentTransition(.opacity).animation(.smooth, value: run.stage)
                }
                .transition(.opacity)
            } else if !shown.isEmpty {
                HandwrittenText(text: MessageView.rendered(shown), live: run.status == .running)
                    .font(.geist(16)).foregroundStyle(Ink.primary).lineSpacing(5)
                    .textSelection(.enabled)
                if run.status == .running { SpiralLoader(size: 14, color: Ink.secondary).transition(.opacity) }
            }
            if let note = run.note ?? (run.status == .stopped ? "Stopped · partial result saved" : nil) {
                Text(note).font(.geist(12)).foregroundStyle(Ink.tertiary)
            }
        }
        .animation(.smooth(duration: 0.3), value: shown.isEmpty)
    }
}

/// A run's state as a tiny bot face.
struct ErrandStatus: View {
    let run: ErrandRun
    var size: CGFloat = 22
    var body: some View {
        PebbleBot(mood: run.mood, shape: BotShape(personaID: run.personaID), size: size, lively: run.isActive, tint: BotTint.color(for: run.personaID))
    }
}

private struct ErrandActions: View {
    let run: ErrandRun
    let again: () -> Void
    let chat: () -> Void
    var close: (() -> Void)?

    var body: some View {
        let plain = String(MessageView.rendered(run.text).characters)
        HStack(spacing: 8) {
            pill("Continue in chat", "bubble", chat)
            pill("Again", "arrow.clockwise", again)
            Button { UIPasteboard.general.setItems([["public.utf8-plain-text": plain]], options: [.localOnly: true]) } label: {
                Image(systemName: "doc.on.doc").font(.system(size: 13)).foregroundStyle(Ink.primary)
                    .frame(width: 32, height: 32).overlay(Circle().stroke(Ink.hairline))
            }
            .buttonStyle(PressStyle()).accessibilityLabel("Copy")
            Spacer(minLength: 0)
            if let close {
                Button(action: close) {
                    Image(systemName: "xmark").font(.system(size: 12, weight: .medium)).foregroundStyle(Ink.secondary)
                        .frame(width: 32, height: 32)
                }
                .buttonStyle(PressStyle()).accessibilityLabel("Close")
            }
        }
    }

    private func pill(_ title: String, _ icon: String, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Label(title, systemImage: icon).font(.geist(13, .medium)).foregroundStyle(Ink.primary)
                .padding(.horizontal, 12).frame(height: 32).overlay(Capsule().stroke(Ink.hairline))
        }
        .buttonStyle(PressStyle())
    }
}

// MARK: - Results

struct ErrandResultsView: View {
    @Bindable var store: AppState
    let continueInChat: (ErrandRun) -> Void
    /// Running one again shows it live on the New errand page.
    var openErrand: () -> Void = {}
    @State private var appeared = false
    @State private var opened: ErrandRun?

    private var groups: [(String, [ErrandRun])] {
        let calendar = Calendar.current
        let buckets = Dictionary(grouping: store.desk.runs) { r -> Int in
            if calendar.isDateInToday(r.startedAt) { return 0 }
            if calendar.isDateInYesterday(r.startedAt) { return 1 }
            if let days = calendar.dateComponents([.day], from: r.startedAt, to: .now).day, days < 7 { return 2 }
            return 3
        }
        let names = ["Today", "Yesterday", "Previous 7 days", "Earlier"]
        return buckets.keys.sorted().map { (names[$0], buckets[$0]!.sorted { $0.startedAt > $1.startedAt }) }
    }

    var body: some View {
        VStack(spacing: 0) {
            if store.desk.runs.isEmpty {
                Spacer()
                VStack(spacing: 10) {
                    PebbleBot(mood: .sleepy, size: 64).padding(.bottom, 6)
                    Text("No errands run yet.").font(.serif(28, italic: true)).foregroundStyle(Ink.primary)
                    Text("What they bring back lands here, on this iPhone.").font(.geist(14)).foregroundStyle(Ink.secondary)
                }
                .rise(appeared, delay: 0.08)
                Spacer()
            } else {
                List {
                    ForEach(Array(groups.enumerated()), id: \.element.0) { g, group in
                        Section {
                            ForEach(Array(group.1.enumerated()), id: \.element.id) { i, run in
                                row(run).rise(appeared, delay: min(0.04 * Double(g * 2 + i), 0.3), distance: 8)
                            }
                        } header: {
                            Text(group.0).font(.geist(13, .medium)).foregroundStyle(Ink.secondary).textCase(nil)
                        }
                    }
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
                .contentMargins(.top, 8, for: .scrollContent)
                .contentMargins(.bottom, 24, for: .scrollContent)
                .animation(.spring(response: 0.4, dampingFraction: 0.85), value: store.desk.runs.map(\.id))
            }
        }
        .background(Ink.paper)
        .onAppear { appeared = true }
        .sheet(item: $opened) { run in
            ErrandRunSheet(store: store, id: run.id, continueInChat: { opened = nil; continueInChat($0) }, again: { opened = nil; store.rerun($0); openErrand() })
        }
    }

    private func row(_ run: ErrandRun) -> some View {
        Button { opened = run } label: {
            HStack(alignment: .top, spacing: 12) {
                ErrandStatus(run: run, size: 26).padding(.top, -2)
                VStack(alignment: .leading, spacing: 4) {
                    HStack(alignment: .firstTextBaseline) {
                        Text(run.title).font(.geist(16)).foregroundStyle(Ink.primary).lineLimit(1)
                        if run.errandID != nil {
                            Image(systemName: "repeat").font(.system(size: 10, weight: .medium)).foregroundStyle(Ink.tertiary)
                        }
                        Spacer(minLength: 12)
                        Text(run.startedAt, format: Calendar.current.isDateInToday(run.startedAt) ? .dateTime.hour().minute() : .dateTime.month(.abbreviated).day())
                            .font(.geist(12).monospacedDigit()).foregroundStyle(Ink.tertiary)
                    }
                    Text(preview(run)).font(.geist(14)).foregroundStyle(Ink.secondary).lineLimit(2)
                }
            }
            .padding(.vertical, 6)
            .contentShape(.rect)
        }
        .buttonStyle(PressStyle(scale: 0.98))
        .listRowBackground(Ink.paper)
        .listRowSeparatorTint(Ink.hairline)
        .listRowInsets(EdgeInsets(top: 4, leading: 20, bottom: 4, trailing: 20))
        .swipeActions {
            if !run.isActive {
                Button("Delete", systemImage: "trash", role: .destructive) { withAnimation(.smooth) { store.desk.delete(run) } }.tint(.red)
            }
        }
    }

    private func preview(_ run: ErrandRun) -> String {
        if run.isActive && run.text.isEmpty { return run.stage ?? "Working on it…" }
        if run.status == .failed { return run.note ?? "Couldn’t finish." }
        let plain = String(MessageView.rendered(run.text).characters)
        let lines = plain.split(separator: "\n", omittingEmptySubsequences: true).map { $0.trimmingCharacters(in: .whitespaces) }
        // Each line reads as its own sentence once they're run together.
        return (lines.count > 1 ? lines.dropFirst() : lines[...])
            .map { $0.last.map { ".!?:…".contains($0) } == true ? $0 : $0 + "." }
            .joined(separator: " ")
    }
}

struct ErrandRunSheet: View {
    @Bindable var store: AppState
    let id: UUID
    let continueInChat: (ErrandRun) -> Void
    let again: (ErrandRun) -> Void

    var body: some View {
        if let run = store.desk.runs.first(where: { $0.id == id }) {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    ErrandHeader(run: run)
                    ErrandBody(run: run, phase: store.phases[run.id] ?? "")
                    if run.isActive {
                        Button { store.cancelErrand(run) } label: {
                            Label("Stop", systemImage: "stop.fill").font(.geist(13, .medium)).foregroundStyle(Ink.primary)
                                .padding(.horizontal, 12).frame(height: 32).overlay(Capsule().stroke(Ink.hairline))
                        }
                        .buttonStyle(PressStyle())
                        .transition(.opacity)
                    } else {
                        ErrandActions(run: run, again: { again(run) }, chat: { continueInChat(run) })
                            .transition(.opacity.combined(with: .offset(y: 6)))
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 20).padding(.top, 28).padding(.bottom, 24)
                .animation(.spring(response: 0.4, dampingFraction: 0.85), value: run.status)
            }
            .background(Ink.paper.ignoresSafeArea())
            .presentationDetents([.medium, .large])
            .presentationDragIndicator(.visible)
            .presentationCornerRadius(28)
            // Opening a result (or watching it finish) takes it off the crew.
            .onAppear { store.desk.markSeen(id) }
            .onChange(of: run.status) { _, _ in store.desk.markSeen(id) }
        }
    }
}

// MARK: - Routines

struct RoutinesView: View {
    @Bindable var store: AppState
    let newErrand: () -> Void
    @State private var appeared = false

    var body: some View {
        VStack(spacing: 0) {
            if store.desk.errands.isEmpty {
                Spacer()
                VStack(spacing: 10) {
                    PebbleBot(mood: .waiting, size: 64).padding(.bottom, 6)
                    Text("Nothing on repeat.").font(.serif(28, italic: true)).foregroundStyle(Ink.primary)
                    Text("Schedule an errand and it’s ready when you are.").font(.geist(14)).foregroundStyle(Ink.secondary)
                    Button(action: newErrand) {
                        Text("New errand").font(.geist(13, .medium)).foregroundStyle(Ink.primary)
                            .padding(.horizontal, 14).frame(height: 32).overlay(Capsule().stroke(Ink.hairline))
                    }
                    .buttonStyle(PressStyle()).padding(.top, 6)
                }
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)
                .rise(appeared, delay: 0.08)
                Spacer()
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: 12) {
                        SectionLabel(text: "On repeat").padding(.leading, 4)
                        ForEach(Array(store.desk.errands.enumerated()), id: \.element.id) { i, errand in
                            RoutineCard(store: store, errand: errand)
                                .rise(appeared, delay: min(0.05 * Double(i), 0.3), distance: 8)
                                .transition(.scale(scale: 0.95).combined(with: .opacity))
                        }
                        Text("At each time Loocal sends a notification. Open it, or be in the app, and the errand runs on this iPhone.")
                            .font(.geist(13)).foregroundStyle(Ink.tertiary).padding(.horizontal, 4).padding(.top, 4)
                    }
                    .padding(.horizontal, 16).padding(.top, 12).padding(.bottom, 32)
                    .animation(.spring(response: 0.4, dampingFraction: 0.85), value: store.desk.errands)
                }
                .scrollIndicators(.hidden)
            }
        }
        .background(Ink.paper)
        .onAppear { appeared = true }
    }
}

private struct RoutineCard: View {
    @Bindable var store: AppState
    let errand: Errand

    private var last: ErrandRun? { store.desk.runs(for: errand).first }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .top, spacing: 12) {
                Image(systemName: errand.cadence.symbol).font(.system(size: 15)).foregroundStyle(Ink.primary)
                    .frame(width: 40, height: 40).background(Ink.fill, in: .circle)
                VStack(alignment: .leading, spacing: 3) {
                    Text(errand.title).font(.geist(16, .medium)).foregroundStyle(Ink.primary).lineLimit(1)
                    Text(errand.schedule).font(.geist(13)).foregroundStyle(Ink.secondary)
                }
                Spacer(minLength: 8)
                Menu {
                    Button("Run now", systemImage: "bolt") { store.runErrand(title: errand.title, instructions: errand.instructions, sources: errand.sources, errandID: errand.id) }
                        .disabled(store.desk.active.count >= AppState.crewSize)
                    Button(errand.paused ? "Resume" : "Pause", systemImage: errand.paused ? "play" : "pause") { withAnimation(.smooth) { store.desk.togglePause(errand) } }
                    Button("Delete", systemImage: "trash", role: .destructive) { withAnimation(.smooth) { store.desk.delete(errand) } }
                } label: {
                    Image(systemName: "ellipsis").font(.system(size: 15, weight: .medium)).foregroundStyle(Ink.secondary)
                        .frame(width: 36, height: 36).contentShape(.rect)
                }
                .accessibilityLabel("Errand options")
            }
            Text(errand.instructions).font(.geist(14)).foregroundStyle(Ink.secondary).lineLimit(2)
            HStack(spacing: 8) {
                ForEach(store.connectors.filter { errand.sources.contains($0.id) }) { source in
                    Image(systemName: source.icon).font(.system(size: 11)).foregroundStyle(Ink.secondary)
                        .accessibilityLabel(source.name)
                }
                Spacer(minLength: 0)
                Text(status).font(.geist(12).monospacedDigit()).foregroundStyle(Ink.tertiary).contentTransition(.opacity)
            }
        }
        .padding(14)
        .overlay(RoundedRectangle(cornerRadius: 22).stroke(Ink.hairline))
        .opacity(errand.paused ? 0.55 : 1)
        .contentShape(.rect(cornerRadius: 22))
    }

    private var status: String {
        if errand.paused { return "Paused" }
        if last?.status == .running { return "Running now" }
        guard let next = errand.nextRun else { return "" }
        let day = Calendar.current.isDateInToday(next) ? "today" : Calendar.current.isDateInTomorrow(next) ? "tomorrow" : next.formatted(.dateTime.weekday(.abbreviated))
        return "Next \(day) \(next.formatted(.dateTime.hour().minute()))"
    }
}
