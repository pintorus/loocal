import AVFoundation
import Foundation
import Speech

/// The full-duplex audio path for conversation mode: one engine whose microphone stays open
/// while Loocal talks. Voice processing (the echo canceller FaceTime uses) runs on the input,
/// and Loocal's voice is played through the same engine, so the canceller knows exactly what
/// to subtract and only *your* voice reaches speech recognition and the voice detector.
final class DuplexAudio: @unchecked Sendable {
    let engine = AVAudioEngine()
    let player = AVAudioPlayerNode()
    /// What Loocal's voice is played at: Kokoro's native rate, system voices are resampled to it.
    let format = AVAudioFormat(standardFormatWithSampleRate: 24_000, channels: 1)!
    /// False when the device can't cancel echo (the simulator): the voice detector is then stricter while Loocal talks.
    private(set) var echoCancelled = false

    /// Called on the audio thread when you start or stop talking.
    var onSpeechStart: (@Sendable () -> Void)?
    var onSpeechEnd: (@Sendable () -> Void)?

    private let lock = NSLock()
    private var sink: SFSpeechAudioBufferRecognitionRequest?
    private var listening = true
    private var playing = false
    private var tapped = false
    private var observer: NSObjectProtocol?

    // Voice detector state, guarded by `lock`.
    private var noiseFloor: Float = -55
    private var meter: Double = 0
    private var voicedRun: Double = 0
    private var quietRun: Double = 0
    private var talking = false
    private var lastVoiced = Date.distantPast
    private var talkStart = Date.distantPast

    func start() throws {
        let input = engine.inputNode
        do {
            try input.setVoiceProcessingEnabled(true)
            // Keep other audio as loud as possible; it's our own voice we cancel, not the world.
            input.voiceProcessingOtherAudioDuckingConfiguration = .init(enableAdvancedDucking: false, duckingLevel: .min)
            echoCancelled = true
        } catch {
            echoCancelled = false
        }
        engine.attach(player)
        engine.connect(player, to: engine.mainMixerNode, format: format)
        installTap()
        engine.prepare()
        try engine.start()
        // A route change (AirPods in, a call) stops the engine; pick straight back up.
        observer = NotificationCenter.default.addObserver(forName: .AVAudioEngineConfigurationChange, object: engine, queue: .main) { [weak self] _ in
            guard let self else { return }
            self.installTap()
            try? self.engine.start()
        }
    }

    func stop() {
        if let observer { NotificationCenter.default.removeObserver(observer) }
        observer = nil
        player.stop()
        engine.stop()
        if tapped { engine.inputNode.removeTap(onBus: 0); tapped = false }
        lock.withLock { sink = nil; meter = 0; talking = false }
    }

    private func installTap() {
        let input = engine.inputNode
        if tapped { input.removeTap(onBus: 0) }
        let micFormat = input.outputFormat(forBus: 0)
        guard micFormat.sampleRate > 0, micFormat.channelCount > 0 else { return }
        input.installTap(onBus: 0, bufferSize: 512, format: micFormat) { [weak self] buffer, _ in self?.process(buffer) }
        tapped = true
    }

    /// Where mic audio goes for transcription; swapped for a fresh request at each turn.
    func feed(_ request: SFSpeechAudioBufferRecognitionRequest?) { lock.withLock { sink = request } }

    /// Muted: the mic is ignored entirely, but Loocal can still speak.
    func setListening(_ on: Bool) { lock.withLock { listening = on; if !on { meter = 0; talking = false; voicedRun = 0 } } }

    /// While Loocal talks the detector asks for a clearer voice, so leftover echo can't trigger it.
    func setPlaying(_ on: Bool) { lock.withLock { playing = on } }

    var level: Double { lock.withLock { meter } }
    var isTalking: Bool { lock.withLock { talking } }
    /// How long since the mic last heard a voice.
    var quietFor: TimeInterval { Date.now.timeIntervalSince(lock.withLock { lastVoiced }) }
    /// How long the current stretch of speech has lasted.
    var talkingFor: TimeInterval { lock.withLock { talking ? Date.now.timeIntervalSince(talkStart) : 0 } }

    // MARK: Audio thread

    private func process(_ buffer: AVAudioPCMBuffer) {
        let frames = Int(buffer.frameLength)
        guard frames > 0, let samples = buffer.floatChannelData?[0] else { return }
        var sum: Float = 0
        for i in 0..<frames { sum += samples[i] * samples[i] }
        let db = 20 * log10(max(sqrt(sum / Float(frames)), 0.000_01))
        let dt = Double(frames) / buffer.format.sampleRate

        var started = false, ended = false
        lock.lock()
        guard listening else { lock.unlock(); return }
        let request = sink
        // The floor drops fast to quiet and creeps up slowly, so steady noise (a fan, traffic) is learned, speech isn't.
        noiseFloor += (db - noiseFloor) * (db < noiseFloor ? 0.25 : Float(min(1, dt * 0.12)))
        noiseFloor = min(-30, max(-80, noiseFloor))
        let margin: Float = playing ? (echoCancelled ? 16 : 28) : 9
        let threshold = max(noiseFloor + margin, playing ? -46 : -54)
        let voiced = db > threshold
        if voiced {
            voicedRun += dt
            quietRun = 0
            lastVoiced = .now
        } else {
            quietRun += dt
            if !talking { voicedRun = max(0, voicedRun - dt * 0.5) }
        }
        // ~100 ms of voice opens a turn; ~250 ms of quiet closes it.
        if !talking && voicedRun >= 0.1 { talking = true; talkStart = .now; started = true }
        if talking && quietRun >= 0.25 { talking = false; voicedRun = 0; ended = true }
        meter = meter * 0.6 + min(1, max(0, Double(db + 50) / 40)) * 0.4
        lock.unlock()

        request?.append(buffer)
        if started { onSpeechStart?() }
        if ended { onSpeechEnd?() }
    }
}

/// Loocal's voice in conversation mode: every sentence is rendered to samples (Kokoro, or a
/// system voice written to a buffer) and played through the duplex engine, so the echo
/// canceller hears it. It can duck when you start talking, stop on the spot when you cut in,
/// and say how much of the reply you actually heard.
@MainActor
final class DuplexVoice: SpeechOutput {
    private let audio: DuplexAudio
    private let choice: VoiceChoice
    private let neuralDirectory: URL?
    private let synth = AVSpeechSynthesizer()
    private var chain: Task<Void, Never>?
    private var queued = 0
    private var generation = 0
    private var cursor = Date.distantPast
    private var segments: [(start: Date, duration: TimeInterval, text: String, samples: [Float])] = []
    var onIdle: (() -> Void)?
    var isIdle: Bool { queued == 0 }

    init(audio: DuplexAudio, choice: VoiceChoice, neuralDirectory: URL?) {
        self.audio = audio
        self.choice = choice
        self.neuralDirectory = neuralDirectory
    }

    func speak(_ sentence: String) {
        for piece in NeuralSpeech.pieces(sentence) {
            queued += 1
            let previous = chain, run = generation
            // Rendering is chained, playback is queued: sentence two renders while one plays.
            chain = Task { [weak self] in
                await previous?.value
                guard !Task.isCancelled, let self, self.generation == run else { return }
                let samples = await self.render(piece)
                guard self.generation == run else { return }
                if samples.isEmpty { self.done(run) } else { self.play(samples, text: piece, run: run) }
            }
        }
    }

    /// Warm the voice so the first sentence isn't slowed by loading it.
    func prewarm() {
        if case .neural(let voice) = choice, let directory = neuralDirectory {
            Task.detached(priority: .userInitiated) { _ = try? await KokoroEngine.shared.synthesize(".", voice: voice, directory: directory) }
        }
    }

    func duck(_ on: Bool) { audio.player.volume = on ? 0.3 : 1 }

    func stop() {
        generation += 1
        chain?.cancel()
        chain = nil
        queued = 0
        synth.stopSpeaking(at: .immediate)
        audio.player.stop()
        audio.player.volume = 1
        audio.setPlaying(false)
        segments = []
        cursor = .distantPast
    }

    /// What has come out of the speaker so far, cut at the word playing now.
    func heardSoFar(at date: Date = .now) -> String {
        var parts: [String] = []
        for segment in segments where segment.start <= date {
            let progress = date.timeIntervalSince(segment.start) / segment.duration
            if progress >= 1 { parts.append(segment.text); continue }
            let words = segment.text.split(separator: " ")
            let count = Int(Double(words.count) * progress)
            if count > 0 { parts.append(words.prefix(count).joined(separator: " ")) }
        }
        return parts.joined(separator: " ")
    }

    func level(at date: Date) -> Double {
        guard let segment = segments.last(where: { $0.start <= date }) else { return 0.05 }
        let index = Int(date.timeIntervalSince(segment.start) * 24_000)
        guard index < segment.samples.count else { return 0.05 }
        let window = segment.samples[max(0, index - 600)..<min(segment.samples.count, index + 600)]
        let rms = sqrt(window.reduce(0) { $0 + $1 * $1 } / Float(max(window.count, 1)))
        return min(1, max(0.05, Double(rms) * 4.5))
    }

    // MARK: Rendering

    private func render(_ text: String) async -> [Float] {
        if case .neural(let voice) = choice, let directory = neuralDirectory,
           let samples = try? await KokoroEngine.shared.synthesize(text, voice: voice, directory: directory), !samples.isEmpty {
            return samples
        }
        var system: AVSpeechSynthesisVoice?
        switch choice {
        case .system(let voice): system = voice
        case .neural: if case .system(let voice) = VoiceLibrary.systemFallback { system = voice }
        }
        return await renderSystem(text, voice: system)
    }

    /// A system voice, written to buffers instead of the speaker, then resampled to 24 kHz.
    private func renderSystem(_ text: String, voice: AVSpeechSynthesisVoice?) async -> [Float] {
        let utterance = AVSpeechUtterance(string: text)
        utterance.voice = voice
        let collector = BufferCollector()
        let buffers = await withCheckedContinuation { (continuation: CheckedContinuation<[AVAudioPCMBuffer], Never>) in
            collector.continuation = continuation
            synth.write(utterance) { buffer in
                guard let pcm = buffer as? AVAudioPCMBuffer else { return }
                if pcm.frameLength == 0 { collector.finish() } else { collector.add(pcm) }
            }
            // Safety net: never wait forever on a buffer that doesn't come.
            DispatchQueue.main.asyncAfter(deadline: .now() + 10) { collector.finish() }
        }
        return Self.resample(buffers, to: audio.format)
    }

    nonisolated static func resample(_ buffers: [AVAudioPCMBuffer], to format: AVAudioFormat) -> [Float] {
        guard let first = buffers.first, let converter = AVAudioConverter(from: first.format, to: format) else { return [] }
        var output: [Float] = []
        var index = 0
        while true {
            guard let chunk = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: 8192) else { break }
            var error: NSError?
            let status = converter.convert(to: chunk, error: &error) { _, inputStatus in
                guard index < buffers.count else { inputStatus.pointee = .endOfStream; return nil }
                inputStatus.pointee = .haveData
                index += 1
                return buffers[index - 1]
            }
            if chunk.frameLength > 0, let data = chunk.floatChannelData?[0] {
                output.append(contentsOf: UnsafeBufferPointer(start: data, count: Int(chunk.frameLength)))
            }
            if status == .endOfStream || status == .error || (status == .inputRanDry && chunk.frameLength == 0) { break }
        }
        return output
    }

    // MARK: Playback

    private func play(_ samples: [Float], text: String, run: Int) {
        guard let buffer = AVAudioPCMBuffer(pcmFormat: audio.format, frameCapacity: AVAudioFrameCount(samples.count)) else { done(run); return }
        buffer.frameLength = buffer.frameCapacity
        samples.withUnsafeBufferPointer { buffer.floatChannelData![0].update(from: $0.baseAddress!, count: samples.count) }
        if !audio.engine.isRunning { try? audio.engine.start() }
        if !audio.player.isPlaying { audio.player.play() }
        audio.setPlaying(true)
        let start = max(.now, cursor)
        let duration = Double(samples.count) / 24_000
        cursor = start.addingTimeInterval(duration)
        segments.append((start, duration, text, samples))
        audio.player.scheduleBuffer(buffer) { [weak self] in
            Task { @MainActor in self?.done(run) }
        }
    }

    private func done(_ run: Int) {
        guard run == generation, queued > 0 else { return }
        queued -= 1
        if queued == 0 {
            audio.setPlaying(false)
            onIdle?()
        }
    }
}

/// Gathers a system voice's buffers (copied: the synthesiser may reuse its own) until the last one.
private final class BufferCollector: @unchecked Sendable {
    private let lock = NSLock()
    private var buffers: [AVAudioPCMBuffer] = []
    var continuation: CheckedContinuation<[AVAudioPCMBuffer], Never>?

    func add(_ buffer: AVAudioPCMBuffer) {
        guard let copy = AVAudioPCMBuffer(pcmFormat: buffer.format, frameCapacity: buffer.frameLength) else { return }
        copy.frameLength = buffer.frameLength
        let frames = Int(buffer.frameLength), channels = Int(buffer.format.channelCount)
        let interleaved = buffer.format.isInterleaved
        let perBuffer = interleaved ? frames * channels : frames
        for channel in 0..<(interleaved ? 1 : channels) {
            if let source = buffer.floatChannelData, let target = copy.floatChannelData {
                target[channel].update(from: source[channel], count: perBuffer)
            } else if let source = buffer.int16ChannelData, let target = copy.int16ChannelData {
                target[channel].update(from: source[channel], count: perBuffer)
            } else if let source = buffer.int32ChannelData, let target = copy.int32ChannelData {
                target[channel].update(from: source[channel], count: perBuffer)
            }
        }
        lock.withLock { buffers.append(copy) }
    }

    func finish() {
        let (continuation, result) = lock.withLock { () -> (CheckedContinuation<[AVAudioPCMBuffer], Never>?, [AVAudioPCMBuffer]) in
            defer { self.continuation = nil }
            return (self.continuation, buffers)
        }
        continuation?.resume(returning: result)
    }
}

/// Telling your words apart from Loocal's own echo, and a real interruption from "mm-hmm".
enum TurnTaking {
    static func normalized(_ word: Substring) -> String {
        word.lowercased().folding(options: .diacriticInsensitive, locale: nil).trimmingCharacters(in: .punctuationCharacters.union(.symbols))
    }

    static func words(_ text: String) -> [String] {
        text.split(whereSeparator: \.isWhitespace).map(normalized).filter { !$0.isEmpty }
    }

    /// Sounds that say "go on", not "stop": they never cut Loocal off.
    static let backchannels: Set<String> = [
        "yeah", "yes", "yep", "ok", "okay", "mm", "mmm", "hmm", "mhm", "mm-hmm", "uh-huh", "uh", "um", "huh", "right", "sure", "cool", "nice", "wow", "ah", "oh",
        "oui", "ouais", "ok", "daccord", "dac", "euh", "bah", "ah", "oh", "voila", "cest", "ca", "si", "vale", "ja", "genau",
    ]

    /// A single one of these is enough to stop Loocal.
    static let stopWords: Set<String> = [
        "stop", "wait", "hold", "no", "nope", "hey", "sorry", "actually", "pause", "enough", "but", "listen",
        "attends", "attend", "arrete", "non", "pardon", "mais", "ecoute", "chut", "basta", "para", "espera", "halt",
    ]

    /// Your words with any leading echo of what Loocal just said stripped off.
    static func strippingEcho(_ text: String, echo: Set<String>) -> String {
        var raw = text.split(whereSeparator: \.isWhitespace)
        while let first = raw.first, echo.contains(normalized(first)) { raw.removeFirst() }
        return raw.joined(separator: " ")
    }

    /// Whether what you said while Loocal was talking is you taking the floor.
    static func isInterruption(_ text: String, echo: Set<String>, talkingFor: TimeInterval) -> Bool {
        let novel = words(text).filter { !echo.contains($0) && !backchannels.contains($0) }
        if novel.count >= 2 { return true }
        if let word = novel.first { return stopWords.contains(word) || talkingFor >= 0.6 }
        return false
    }
}
