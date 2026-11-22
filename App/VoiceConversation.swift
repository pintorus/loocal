import AVFoundation
import Foundation
import FoundationModels
import Observation
import Speech

enum ConversationPhase: Equatable { case listening, thinking, speaking }

struct ConversationLine: Identifiable, Equatable {
    let id = UUID()
    let fromUser: Bool
    var text: String
}

/// What the conversation-mode visuals read from: the scripted mock or the live pipeline.
@MainActor
protocol ConversationSource: AnyObject {
    var phase: ConversationPhase { get }
    var lines: [ConversationLine] { get }
    var muted: Bool { get set }
    func level(at date: Date) -> Double
    func start()
    func stop()
}

extension ConversationSource {
    var current: ConversationLine? { lines.last }
    var previous: ConversationLine? { lines.count > 1 ? lines[lines.count - 2] : nil }

    /// Per-band levels for bar visualisations, offset so bars move independently.
    func bands(_ count: Int, at date: Date) -> [Double] {
        let base = level(at: date)
        let t = date.timeIntervalSinceReferenceDate
        return (0..<count).map { i in
            let shimmer = 0.6 + 0.4 * sin(t * (9 + Double(i) * 1.3) + Double(i) * 1.7)
            let shape = 1 - abs(Double(i) - Double(count - 1) / 2) / Double(count)
            return min(1, max(0.05, base * shimmer * (0.55 + shape)))
        }
    }
}

/// Live voice conversation, fully on device and full duplex. Two threads run side by side:
/// one never stops listening (echo-cancelled mic → on-device transcription and a voice
/// detector), the other thinks and talks. Cut in whenever you like: Loocal ducks the moment
/// you make a sound and stops as soon as your words are clear, keeping only what you heard.
/// To answer sooner it starts writing its reply in your pauses and commits when your turn ends,
/// and it speaks the opening clause while the rest is still being written.
@MainActor @Observable
final class VoiceConversation: ConversationSource {
    private(set) var phase: ConversationPhase = .listening
    private(set) var lines: [ConversationLine] = []
    var muted = false {
        didSet {
            guard muted != oldValue, running else { return }
            audio.setListening(!muted)
            if muted {
                if !committed { cancelReply() }
                endRecognition()
                if ducked { voice?.duck(false); ducked = false }
            } else {
                beginRecognition(carry: phase != .speaking, guardEcho: phase == .speaking)
            }
        }
    }

    @ObservationIgnored private weak var store: AppState?
    @ObservationIgnored private let audio = DuplexAudio()
    @ObservationIgnored private let recognizer = SFSpeechRecognizer(locale: .current) ?? SFSpeechRecognizer(locale: Locale(identifier: "en-US"))
    @ObservationIgnored private var voice: DuplexVoice?
    @ObservationIgnored private var running = false
    @ObservationIgnored private var clock: Task<Void, Never>?

    // Listening thread.
    @ObservationIgnored private var request: SFSpeechAudioBufferRecognitionRequest?
    @ObservationIgnored private var recognition: SFSpeechRecognitionTask?
    @ObservationIgnored private var recognitionID = 0
    /// What the current recognition task has heard.
    @ObservationIgnored private var transcript = ""
    /// Your turn's words from an earlier recognition task (one ended mid-turn).
    @ObservationIgnored private var carried = ""
    /// Strip a leading echo of Loocal's words from the transcript (it started while Loocal talked).
    @ObservationIgnored private var echoGuard = false
    /// Words Loocal has said in this reply: what leftover echo would sound like.
    @ObservationIgnored private var echo: Set<String> = []
    @ObservationIgnored private var lastWord = Date.distantPast
    @ObservationIgnored private var saidCount = 0
    @ObservationIgnored private var ducked = false
    @ObservationIgnored private var userLine: UUID?

    // Talking thread.
    @ObservationIgnored private var replying: Task<Void, Never>?
    @ObservationIgnored private var replyID = 0
    /// What the reply in progress answers; nil for an announcement.
    @ObservationIgnored private var replyFor: String?
    /// A speculative reply is written but not shown or spoken until your turn really ends.
    @ObservationIgnored private var committed = false
    @ObservationIgnored private var draft = ""
    @ObservationIgnored private var replyDone = false
    @ObservationIgnored private var spoken = 0
    @ObservationIgnored private var botLine: UUID?
    @ObservationIgnored private var history: [ChatMessage] = []
    @ObservationIgnored private var nextSession: LanguageModelSession?

    /// Errand mode: what you say goes to this instead of the chat model, and its answer
    /// is spoken back. Nothing is added to the chat history.
    @ObservationIgnored var respond: ((String) async -> String)?

    init(store: AppState) {
        self.store = store
    }

    // MARK: Lifecycle

    func start() {
        guard !running else { return }
        running = true
        Task {
            guard await authorize() else {
                say("Loocal needs the microphone and speech recognition. Allow both in Settings.")
                return
            }
            guard let recognizer, recognizer.isAvailable else {
                say("Speech recognition isn’t available for this language right now.")
                return
            }
            // Speech never leaves the phone: without on-device recognition, Loocal doesn't listen.
            guard recognizer.supportsOnDeviceRecognition else {
                say("On-device speech recognition isn’t available for this language, and Loocal never sends your voice to a server.")
                return
            }
            do {
                let session = AVAudioSession.sharedInstance()
                try session.setCategory(.playAndRecord, mode: .voiceChat, options: [.defaultToSpeaker, .allowBluetoothHFP])
                try? session.setPreferredIOBufferDuration(0.01)
                try session.setActive(true)
                audio.onSpeechStart = { [weak self] in Task { @MainActor in self?.speechStarted() } }
                try audio.start()
            } catch {
                say("The microphone couldn’t start: \(error.localizedDescription)")
                return
            }
            guard running else { audio.stop(); return }
            let voice = DuplexVoice(audio: audio, choice: VoiceLibrary.current(store?.neuralVoices), neuralDirectory: store?.neuralVoices.directory)
            voice.onIdle = { [weak self] in self?.finishedSpeaking() }
            voice.prewarm()
            self.voice = voice
            prepareSession()
            setUserLine("")
            beginRecognition(carry: false)
            clock = Task { [weak self] in
                while !Task.isCancelled {
                    try? await Task.sleep(for: .milliseconds(40))
                    self?.tick()
                }
            }
        }
    }

    func stop() {
        running = false
        clock?.cancel()
        cancelReply()
        replying?.cancel()
        voice?.stop()
        endRecognition()
        audio.stop()
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        store?.endVoice()
    }

    private func authorize() async -> Bool {
        let speech = await withCheckedContinuation { continuation in
            SFSpeechRecognizer.requestAuthorization { continuation.resume(returning: $0) }
        }
        let mic = await AVAudioApplication.requestRecordPermission()
        return speech == .authorized && mic
    }

    private func say(_ text: String) {
        lines.append(ConversationLine(fromUser: false, text: text))
    }

    // MARK: Listening thread

    /// Your words in this turn so far.
    private var userText: String {
        [carried, fresh].filter { !$0.isEmpty }.joined(separator: " ")
    }

    /// The current recognition task's words, minus any echo it caught at the start.
    private var fresh: String {
        echoGuard ? TurnTaking.strippingEcho(transcript, echo: echo) : transcript
    }

    private func beginRecognition(carry: Bool, guardEcho: Bool = false) {
        let keep = carry ? userText : ""
        endRecognition()
        carried = keep
        transcript = ""
        echoGuard = guardEcho
        guard running, !muted, let recognizer, recognizer.isAvailable else { return }
        let request = SFSpeechAudioBufferRecognitionRequest()
        request.shouldReportPartialResults = true
        request.addsPunctuation = true
        request.requiresOnDeviceRecognition = true
        request.taskHint = .dictation
        self.request = request
        audio.feed(request)
        let id = recognitionID
        recognition = recognizer.recognitionTask(with: request) { [weak self] result, error in
            let text = result?.bestTranscription.formattedString
            let isFinal = result?.isFinal ?? false
            let failed = error != nil
            Task { @MainActor in self?.heard(text, final: isFinal, failed: failed, id: id) }
        }
    }

    private func endRecognition() {
        recognitionID += 1
        audio.feed(nil)
        request?.endAudio()
        recognition?.cancel()
        request = nil
        recognition = nil
    }

    private func heard(_ text: String?, final: Bool, failed: Bool, id: Int) {
        guard running, id == recognitionID else { return }
        if let text, text != transcript {
            transcript = text
            if !fresh.isEmpty { lastWord = .now }
        }
        switch phase {
        case .listening:
            setUserLine(userText)
            // You kept talking: the reply written in your pause no longer fits.
            if let answering = replyFor, !committed, answering != userText { cancelReply() }
        case .thinking:
            if TurnTaking.words(userText).count > saidCount { keepTalking() }
        case .speaking:
            if TurnTaking.isInterruption(fresh, echo: echo, talkingFor: audio.talkingFor) { interrupt() }
        }
        guard final || failed else { return }
        // A recognition task ends after a final result or a long silence; start the next one at once.
        if final, phase == .listening, !userText.isEmpty { endTurn() }
        let speaking = phase == .speaking, guarded = speaking || echoGuard
        guard failed && text == nil else { beginRecognition(carry: !speaking, guardEcho: guarded); return }
        // The recognizer gave up (no speech for a while): retry shortly, unless something else already has.
        endRecognition()
        Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(150))
            guard let self, self.running, self.recognition == nil else { return }
            self.beginRecognition(carry: self.phase != .speaking, guardEcho: self.phase == .speaking || guarded)
        }
    }

    /// The voice detector heard you. While Loocal talks, it lowers its voice straight away.
    private func speechStarted() {
        guard running, !muted, phase == .speaking, !ducked else { return }
        voice?.duck(true)
        ducked = true
    }

    /// Runs every 40 ms: ends your turn on a pause, starts a reply early on a shorter one.
    private func tick() {
        guard running, !muted else { return }
        if ducked, phase == .speaking, audio.quietFor > 0.7 {
            voice?.duck(false)  // it was a cough, not a word
            ducked = false
        }
        guard phase == .listening else { return }
        let said = userText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !said.isEmpty else { return }
        let quiet = min(Date.now.timeIntervalSince(lastWord), audio.quietFor)
        if quiet >= 0.3, replyFor != said, canSpeculate(said) { startReply(to: said, commit: false) }
        // A finished sentence ends the turn sooner than a trailing one.
        let complete = said.last.map { ".?!…".contains($0) } ?? false
        if quiet >= (complete ? 0.5 : 0.8) { endTurn() }
    }

    private func endTurn() {
        guard phase == .listening else { return }
        let said = userText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !said.isEmpty else { return }
        saidCount = TurnTaking.words(said).count
        phase = .thinking
        if replyFor == said, replying != nil { commit() } else { startReply(to: said, commit: true) }
    }

    /// You carried on before Loocal said anything: it's still your turn.
    private func keepTalking() {
        dropBotLine()
        cancelReply()
        phase = .listening
        setUserLine(userText)
    }

    /// You cut in while Loocal was talking: stop on the spot and remember only what you heard.
    private func interrupt() {
        guard let voice else { return }
        let heard = voice.heardSoFar()
        voice.stop()
        ducked = false
        let answering = replyFor
        if heard.isEmpty { dropBotLine() } else { removeBotLineIfEmpty(keeping: heard + "…") }
        if let answering { record(answering, heard.isEmpty ? "…" : heard + "…") }
        cancelReply()
        phase = .listening
        userLine = nil
        setUserLine(userText)
        lastWord = .now
    }

    // MARK: Talking thread

    private var instructions: String {
        (store?.systemPrompt ?? InferenceEngine.defaultSystemPrompt)
            + "\n\nThis is a live spoken conversation. Reply in one to three short, natural sentences, the first one short. No lists, headings or markdown. Never make up the user's events, reminders or plans: only say what Loocal's tools found."
            + " The user can cut you off: a reply of yours ending in “…” is where they did. Don't repeat it; answer what they said next."
    }

    /// Apple's model gets the recent turns in its instructions, and a session is made and
    /// prewarmed ahead of time so the first token comes quickly.
    private func prepareSession() {
        guard respond == nil, SystemLanguageModel.default.isAvailable else { return }
        var text = instructions
        if !history.isEmpty {
            text += "\n\nThe conversation so far:\n" + history.suffix(10).map { ($0.role == .user ? "User: " : "You: ") + $0.text }.joined(separator: "\n")
        }
        let session = LanguageModelSession(instructions: text)
        session.prewarm()
        nextSession = session
    }

    /// No speculating when the words could set off an action (a reminder, an errand).
    private func canSpeculate(_ said: String) -> Bool {
        respond == nil && ToolAction.detect(said) == nil
    }

    private func startReply(to said: String, commit: Bool) {
        cancelReply()
        let previous = replying
        replyID += 1
        let id = replyID
        replyFor = said
        replying = Task { [weak self] in
            await previous?.value  // the model writes one reply at a time
            guard let self, !Task.isCancelled, self.replyID == id else { return }
            let final = await self.generate(said, id: id)
            guard !Task.isCancelled, self.replyID == id else { return }
            self.draft = Self.plain(final)
            self.replyDone = true
            self.flush()
        }
        if commit { self.commit() }
    }

    private func commit() {
        committed = true
        flush()
    }

    private func cancelReply() {
        replying?.cancel()
        replyID += 1
        replyFor = nil
        committed = false
        draft = ""
        replyDone = false
        spoken = 0
        botLine = nil
    }

    private func generate(_ said: String, id: Int) async -> String {
        if let respond { return await respond(said) }
        let update: (String) -> Void = { [weak self] partial in
            guard let self, self.replyID == id else { return }
            self.draft = Self.plain(partial)
            self.flush()
        }
        do {
            if SystemLanguageModel.default.isAvailable {
                let session = nextSession ?? LanguageModelSession(instructions: instructions)
                nextSession = nil
                // Apple's model gets the same tools as the chat: actions, maths, places,
                // translation, the web and memory, worked out first and handed over with the question.
                let context = await store?.voiceContext(for: said) ?? ""
                guard !Task.isCancelled else { return "" }
                let prompt = context.isEmpty ? said : "Use this, worked out by Loocal's tools for this question:\n\(context)\n\nThe user said: \(said)"
                var final = ""
                for try await snapshot in session.streamResponse(to: prompt) {
                    final = snapshot.content
                    update(final)
                }
                return final
            } else if let store {
                return try await store.localReply(history + [ChatMessage(role: .user, text: said)], system: instructions) { partial in
                    await MainActor.run { update(partial) }
                }
            }
            return ""
        } catch is CancellationError {
            return ""
        } catch let error as LanguageModelSession.GenerationError {
            if case .guardrailViolation = error { return "I’d rather not answer that one." }
            return "Sorry, I lost my thought. Try again?"
        } catch {
            return Task.isCancelled ? "" : "Sorry, I couldn’t answer: \(error.localizedDescription)"
        }
    }

    /// Show and speak whatever of the reply is ready, once it's committed.
    private func flush() {
        guard committed else { return }
        if !draft.isEmpty || replyDone {
            if let index = lines.firstIndex(where: { $0.id == botLine }) {
                lines[index].text = draft
            } else {
                let line = ConversationLine(fromUser: false, text: draft)
                lines.append(line)
                botLine = line.id
                trim()
            }
        }
        speakPending(final: replyDone)
        if replyDone, voice?.isIdle ?? true, spoken >= draft.count { finishedSpeaking() }
    }

    /// Speak each sentence as soon as it's complete; the very first clause goes alone, so
    /// the voice starts while the model is still writing.
    private func speakPending(final: Bool) {
        let characters = Array(draft)
        var end = spoken
        if final {
            end = characters.count
        } else {
            for i in stride(from: characters.count - 2, through: spoken, by: -1) where ".!?…\n".contains(characters[i]) && characters[i + 1].isWhitespace {
                end = i + 1
                break
            }
            if end == spoken, spoken == 0, characters.count > 32 {
                for i in stride(from: characters.count - 2, through: 18, by: -1) where ",;:—".contains(characters[i]) && characters[i + 1].isWhitespace {
                    end = i + 1
                    break
                }
            }
        }
        guard end > spoken else { return }
        let sentence = String(characters[spoken..<end]).trimmingCharacters(in: .whitespacesAndNewlines)
        spoken = end
        guard !sentence.isEmpty, let voice else { return }
        if phase != .speaking {
            phase = .speaking
            echo = []
            // From here on the mic may catch a little of Loocal; a fresh transcript keeps that out of your next turn.
            beginRecognition(carry: false, guardEcho: true)
        }
        echo.formUnion(TurnTaking.words(sentence))
        voice.speak(sentence)
    }

    /// The reply is written and fully played: back to you.
    private func finishedSpeaking() {
        guard running, committed, replyDone, phase != .listening else { return }
        if let answering = replyFor, !draft.isEmpty { record(answering, draft) }
        removeBotLineIfEmpty(keeping: nil)
        cancelReply()
        ducked = false
        voice?.duck(false)
        phase = .listening
        // If you're already talking, keep those words; otherwise start clean, free of echo.
        if fresh.isEmpty { beginRecognition(carry: false) }
        userLine = nil
        setUserLine(userText)
    }

    private func record(_ said: String, _ reply: String) {
        history.append(ChatMessage(role: .user, text: said))
        history.append(ChatMessage(role: .assistant, text: reply))
        if history.count > 16 { history.removeFirst(history.count - 16) }
        if respond == nil { store?.recordVoiceTurn(user: said, reply: reply) }
        prepareSession()
    }

    /// Speak up unprompted, between your turns: errand mode uses it to read results back.
    func announce(_ text: String) {
        guard running, phase == .listening, userText.isEmpty, !audio.isTalking else { return } // never talk over you
        cancelReply()
        if let index = lines.firstIndex(where: { $0.id == userLine }), lines[index].text.isEmpty { lines.remove(at: index) }
        userLine = nil
        phase = .thinking
        committed = true
        draft = Self.plain(text)
        replyDone = true
        flush()
    }

    // MARK: Lines

    private func setUserLine(_ text: String) {
        if let index = lines.firstIndex(where: { $0.id == userLine }) {
            if lines[index].text != text { lines[index].text = text }
        } else {
            let line = ConversationLine(fromUser: true, text: text)
            lines.append(line)
            userLine = line.id
            trim()
        }
    }

    private func removeBotLineIfEmpty(keeping text: String?) {
        guard let index = lines.firstIndex(where: { $0.id == botLine }) else { return }
        if let text { lines[index].text = text } else if lines[index].text.isEmpty { lines.remove(at: index) }
    }

    private func dropBotLine() {
        if let index = lines.firstIndex(where: { $0.id == botLine }) { lines.remove(at: index) }
        botLine = nil
    }

    // MARK: Visual level

    func level(at date: Date) -> Double {
        switch phase {
        case .thinking: return 0.06
        case .listening: return muted ? 0.04 : max(0.04, audio.level)
        case .speaking: return voice?.level(at: date) ?? 0.05
        }
    }

    private func trim() { if lines.count > 8 { lines.removeFirst(lines.count - 8) } }

    /// Speech-friendly text: no markdown symbols.
    static func plain(_ text: String) -> String {
        text.replacingOccurrences(of: "**", with: "").replacingOccurrences(of: "`", with: "").replacingOccurrences(of: "#", with: "")
    }
}
