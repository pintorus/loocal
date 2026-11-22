import PhotosUI
import SwiftUI
import UniformTypeIdentifiers

/// The bottom bar holds two sets of three destinations. Swiping across the bar flips
/// between them: conversations on the left, errands on the right.
enum NavSpace: Int, Hashable, CaseIterable {
    case chats, errands
    var tabs: [AppTab] { self == .chats ? [.history, .chat, .settings] : [.results, .errand, .routines] }
    var home: AppTab { tabs[1] }
    var label: String { self == .chats ? "Chats" : "Errands" }
}

enum AppTab: Hashable, CaseIterable {
    case history, chat, settings
    case results, errand, routines

    var label: String {
        switch self {
        case .history: "History"; case .chat: "New chat"; case .settings: "Settings"
        case .results: "Results"; case .errand: "New errand"; case .routines: "Routines"
        }
    }
    var symbol: String {
        switch self {
        case .history: "clock"; case .chat: "bubble"; case .settings: "gearshape"
        case .results: "tray"; case .errand: "figure.walk"; case .routines: "repeat"
        }
    }
    var space: NavSpace { [.history, .chat, .settings].contains(self) ? .chats : .errands }
    /// Left-to-right position across both sets, so page slides always follow the bar.
    var order: Int { space.rawValue * 3 + (space.tabs.firstIndex(of: self) ?? 0) }
    /// Pages that scroll under the bar get a soft fade above it.
    var scrollsUnderBar: Bool { [.settings, .results, .routines].contains(self) }
}

struct RootView: View {
    @Bindable var store: AppState
    @State private var tab: AppTab = .chat
    @State private var previous: AppTab = .chat
    /// The tab last open in each set, so swiping back returns you where you were.
    @State private var remembered: [NavSpace: AppTab] = [.chats: .chat, .errands: .errand]
    @State private var keyboardVisible = false
    @State private var addingPersona = false
    /// How far the bottom bar is being swiped; the page leans with it.
    @State private var barDrag: CGFloat = 0
    /// A horizontal swipe on the page itself, moving to the next or previous tab.
    @State private var pageDrag: CGFloat = 0
    @State private var settingsPath: [SettingsPage] = []
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @AppStorage("appearance") private var appearance: Appearance = .system

    var body: some View {
        ZStack {
            switch tab {
            case .history: HistoryView(store: store, open: { store.select($0); go(.chat) }).transition(slide)
            case .chat: ChatView(store: store, openSettings: { go(.settings) }, openPersonas: { addingPersona = true; go(.settings) }).transition(slide)
            case .settings: SettingsView(store: store, appearance: $appearance, addingPersona: $addingPersona, path: $settingsPath).transition(slide)
            case .results: ErrandResultsView(store: store, continueInChat: continueInChat, openErrand: { go(.errand) }).transition(slide)
            case .errand: ErrandView(store: store, openResults: { go(.results) }, openRoutines: { go(.routines) }, continueInChat: continueInChat, openPersonas: { addingPersona = true; go(.settings) }).transition(slide)
            case .routines: RoutinesView(store: store, newErrand: { go(.errand) }).transition(slide)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        // Swiping the page moves through the tabs, left to right across both sets:
        // History, Chat, Settings, then Results, Errand, Routines.
        .gesture(pageSwipe, isEnabled: !keyboardVisible && settingsPath.isEmpty)
        // While the bar is swiped the page drifts a little the same way, so the two feel joined.
        .offset(x: reduceMotion ? 0 : barDrag * 0.1 + pageDrag)
        .opacity(1 - min(abs(barDrag) / 1400, 0.2))
        .background(Ink.paper.ignoresSafeArea())
        .safeAreaInset(edge: .bottom, spacing: 0) {
            if !keyboardVisible {
                DestinationBar(space: tab.space, selections: remembered, choose: choose, flip: flip, drag: $barDrag)
                    .padding(.top, 6)
                    .padding(.bottom, -12) // Sit low, like Soma: tucked toward the home indicator.
                    .frame(maxWidth: .infinity)
                    .background {
                        // Only pages that scroll under the bar get a fade. Chat's composer and
                        // History's search field sit directly above the bar and must stay untouched.
                        VStack(spacing: 0) {
                            if tab.scrollsUnderBar {
                                LinearGradient(colors: [Ink.paper.opacity(0), Ink.paper], startPoint: .top, endPoint: .bottom).frame(height: 18)
                            }
                            Ink.paper
                        }
                        .padding(.top, tab.scrollsUnderBar ? -18 : 0)
                        .ignoresSafeArea(edges: .bottom)
                        .allowsHitTesting(false)
                    }
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(.spring(response: 0.35, dampingFraction: 0.9), value: keyboardVisible)
        .tint(Ink.primary)
        // A temporary chat is always dark, so it's obvious at a glance you're off the record.
        .preferredColorScheme(tab == .chat && store.isTemporary ? .dark : appearance.scheme)
        .animation(.smooth(duration: 0.35), value: appearance)
        .onOpenURL { url in if url.host() != "shortcut-done" { go(.chat) } }
        .onChange(of: store.requestedTab) { _, requested in
            guard let requested else { return }
            store.requestedTab = nil
            if requested.space != tab.space { flip(to: requested.space, velocity: 0) }
            go(requested)
        }
        #if DEBUG && targetEnvironment(simulator)
        .onAppear {
            let arguments = ProcessInfo.processInfo.arguments
            if arguments.contains("-uiDemo") { store.loadDemo() }
            if arguments.contains("-renderIcons") { IconRender.write() }
            if arguments.contains("-renderMoods") { IconRender.moods() }
            if arguments.contains("-renderSleep") { IconRender.sleep() }
            // Fills the crew, to review errands running side by side.
            if arguments.contains("-crewDemo") {
                for idea in ErrandIdea.all.prefix(3) { store.runErrand(title: idea.title, instructions: idea.instructions, sources: idea.sources) }
            }
            if let i = arguments.firstIndex(of: "-tab"), i + 1 < arguments.count, let start = AppTab.allCases.first(where: { String(describing: $0) == arguments[i + 1] }) {
                tab = start; previous = start; remembered[start.space] = start
            }
        }
        #endif
        #if DEBUG
        .task { if ProcessInfo.processInfo.arguments.contains("-bench") { await store.benchmark() } }
        #endif
        // Routines whose time came while Loocal was open run without waiting for a relaunch.
        .task {
            store.runDueErrands()
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(30))
                store.runDueErrands()
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillShowNotification)) { _ in keyboardVisible = true }
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillHideNotification)) { _ in keyboardVisible = false }
        .onReceive(NotificationCenter.default.publisher(for: UIApplication.willResignActiveNotification)) { _ in store.willResignActive() }
        .onReceive(NotificationCenter.default.publisher(for: UIApplication.didEnterBackgroundNotification)) { _ in store.didEnterBackground() }
        .onReceive(NotificationCenter.default.publisher(for: UIApplication.didBecomeActiveNotification)) { _ in store.didBecomeActive() }
        .onReceive(NotificationCenter.default.publisher(for: UIApplication.didReceiveMemoryWarningNotification)) { _ in store.memoryWarning() }
        // Errors and interruptions drop in from the top instead of blocking the screen.
        .overlay(alignment: .top) { Toast(store: store) }
    }

    /// Pages drift in from the side of the tab you tapped. Flipping between sets travels a
    /// little further and softens through a blur, like the page turning with the bar.
    private var slide: AnyTransition {
        if reduceMotion { return .opacity }
        let forward = tab.order >= previous.order
        let flipping = tab.space != previous.space
        let distance: CGFloat = flipping ? 56 : 28
        return .asymmetric(
            insertion: .offset(x: forward ? distance : -distance).combined(with: .opacity).combined(with: .modifier(active: SoftBlur(radius: flipping ? 6 : 0), identity: SoftBlur(radius: 0))),
            // On a flip the old page clears out quickly, so the two never sit on top of each other.
            removal: flipping
                ? .offset(x: forward ? -distance * 0.5 : distance * 0.5).combined(with: .opacity).combined(with: .modifier(active: SoftBlur(radius: 4), identity: SoftBlur(radius: 0))).animation(.easeOut(duration: 0.14))
                : .offset(x: forward ? -distance : distance).combined(with: .opacity)
        )
    }

    private var pageSwipe: some Gesture {
        DragGesture(minimumDistance: 24)
            .onChanged { value in
                let dx = value.translation.width, dy = value.translation.height
                guard abs(dx) > abs(dy) * 1.6 else { return }
                // Follows the finger a little, with resistance where there's nowhere to go.
                let blocked = (dx > 0 && neighbour(-1) == nil) || (dx < 0 && neighbour(1) == nil)
                pageDrag = dx * (blocked ? 0.06 : 0.18)
            }
            .onEnded { value in
                let dx = value.translation.width, dy = value.translation.height
                let fling = value.predictedEndTranslation.width
                let horizontal = abs(dx) > abs(dy) * 1.6
                withAnimation(.spring(response: 0.4, dampingFraction: 0.85)) { pageDrag = 0 }
                guard horizontal, abs(dx) > 70 || abs(fling) > 260, let next = neighbour(dx < 0 ? 1 : -1) else { return }
                if next.space != tab.space {
                    remembered[next.space] = next
                    flip(to: next.space, velocity: 0)
                } else {
                    go(next)
                }
            }
    }

    /// The tab beside this one, crossing from Settings to Results and back.
    private func neighbour(_ step: Int) -> AppTab? {
        let order = AppTab.allCases.sorted { $0.order < $1.order }
        guard let index = order.firstIndex(of: tab), order.indices.contains(index + step) else { return nil }
        return order[index + step]
    }

    private func go(_ destination: AppTab, animation: Animation = .spring(response: 0.42, dampingFraction: 0.88)) {
        previous = tab
        remembered[destination.space] = destination
        withAnimation(animation) { tab = destination }
    }

    /// Lands the page with the same spring (and finger speed) as the bar above it.
    private func flip(to space: NavSpace, velocity: CGFloat) {
        guard space != tab.space else { return }
        go(remembered[space] ?? space.home, animation: reduceMotion ? .easeInOut(duration: 0.25) : FlipMotion.settle(velocity: velocity))
    }

    private func continueInChat(_ run: ErrandRun) {
        withAnimation(.spring(response: 0.42, dampingFraction: 0.88)) { store.continueInChat(run) }
        go(.chat)
    }

    private func choose(_ destination: AppTab) {
        // The middle destination starts a fresh chat when you're already looking at one.
        if destination == .chat && tab == .chat && !store.current.messages.isEmpty {
            withAnimation(.spring(response: 0.42, dampingFraction: 0.88)) { store.newConversation() }
            return
        }
        go(destination)
    }
}

/// One spring for everything that moves when the bar flips sets, so the bar and the
/// page underneath land together.
enum FlipMotion {
    static func settle(velocity: CGFloat = 0) -> Animation {
        .interpolatingSpring(duration: 0.48, bounce: 0.04, initialVelocity: velocity)
    }
}

/// Soma-style destination bar: three label-free icons in a 300 pt row, the selected one
/// sitting on a soft pill. No container chrome. Both sets of three sit side by side on one
/// track; a horizontal swipe slides the track under the finger and, released past the
/// middle (or flicked), carries on to the other set with the finger's speed.
struct DestinationBar: View {
    let space: NavSpace
    /// The tab each set is on, so the other set's pill is already in place as it slides in.
    let selections: [NavSpace: AppTab]
    let choose: (AppTab) -> Void
    var flip: (NavSpace, CGFloat) -> Void = { _, _ in }
    /// The live drag, shared with the page so it can lean along with the bar.
    @Binding var drag: CGFloat
    @State private var width: CGFloat = 348
    @State private var hint = false
    @State private var hintTask: Task<Void, Never>?
    @State private var flips = 0
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    init(space: NavSpace, selections: [NavSpace: AppTab], choose: @escaping (AppTab) -> Void, flip: @escaping (NavSpace, CGFloat) -> Void = { _, _ in }, drag: Binding<CGFloat> = .constant(0)) {
        self.space = space
        self.selections = selections
        self.choose = choose
        self.flip = flip
        _drag = drag
    }

    private var page: CGFloat { CGFloat(space.rawValue) }
    /// 0 on Chats, 1 on Errands, fractional mid-swipe.
    private var progress: CGFloat { page - drag / max(width, 1) }

    var body: some View {
        HStack(spacing: 0) {
            ForEach(NavSpace.allCases, id: \.self) { item in
                row(item)
                    .frame(width: width)
                    // The set sliding away eases back a touch, like a page turning under.
                    .opacity(1 - min(abs(progress - CGFloat(item.rawValue)), 1) * 0.65)
                    .scaleEffect(1 - min(abs(progress - CGFloat(item.rawValue)), 1) * 0.06)
            }
        }
        .offset(x: -page * width + drag)
        .frame(width: width, alignment: .leading)
        .frame(height: 54)
        // Soft edges instead of a hard clip, so icons fade as they leave.
        .mask {
            LinearGradient(stops: [.init(color: .clear, location: 0), .init(color: .black, location: 0.07), .init(color: .black, location: 0.93), .init(color: .clear, location: 1)], startPoint: .leading, endPoint: .trailing)
        }
        .frame(maxWidth: 348)
        .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { width = $0 }
        .overlay(alignment: .bottom) { dots.offset(y: 9) }
        .contentShape(.rect)
        .highPriorityGesture(swipe)
        .sensoryFeedback(.selection, trigger: selections)
        .sensoryFeedback(.impact(weight: .light, intensity: 0.7), trigger: flips)
        .accessibilityElement(children: .contain)
        .accessibilityAction(named: space == .chats ? "Show Errands" : "Show Chats") { turn(to: space == .chats ? .errands : .chats, velocity: 0) }
    }

    private func row(_ set: NavSpace) -> some View {
        HStack {
            ForEach(set.tabs, id: \.self) { item in
                let selected = item == selections[set]
                Button { choose(item) } label: {
                    TabGlyph(tab: item, selected: selected)
                        .frame(width: 72, height: 42)
                        .background {
                            if selected { Capsule().fill(Ink.fill).matchedGeometryEffect(id: "pill", in: set == .chats ? chatsPill : errandsPill) }
                        }
                        .contentShape(.capsule)
                }
                .buttonStyle(PressStyle(scale: 0.9))
                .accessibilityLabel(item.label)
                .accessibilityIdentifier("tab-" + String(describing: item))
                .accessibilityAddTraits(selected ? [.isSelected] : [])
                .accessibilityHidden(set != space)
                .allowsHitTesting(set == space)
                if item != set.tabs.last { Spacer(minLength: 0) }
            }
        }
        .padding(.horizontal, 24)
        .animation(.spring(response: 0.38, dampingFraction: 0.78), value: selections[set])
    }

    // Separate namespaces: each set's pill glides within its own row, never across rows.
    @Namespace private var chatsPill
    @Namespace private var errandsPill

    /// Two small marks for the two sets; the active one stretches as you swipe.
    private var dots: some View {
        let t = min(max(progress, 0), 1)
        return HStack(spacing: 5) {
            Capsule().fill(Ink.primary.opacity(0.35 + 0.65 * (1 - t))).frame(width: 5 + 9 * (1 - t), height: 5)
            Capsule().fill(Ink.primary.opacity(0.35 + 0.65 * t)).frame(width: 5 + 9 * t, height: 5)
        }
        .opacity(hint || drag != 0 ? 1 : 0)
        .animation(.easeOut(duration: 0.3), value: hint || drag != 0)
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }

    private var swipe: some Gesture {
        DragGesture(minimumDistance: 12)
            .onChanged { value in
                guard abs(value.translation.width) > abs(value.translation.height) || drag != 0 else { return }
                let dx = value.translation.width
                let toward = (space == .chats && dx < 0) || (space == .errands && dx > 0)
                drag = toward ? max(-width, min(width, dx)) : rubberBand(dx)
            }
            .onEnded { value in
                guard drag != 0 else { return }
                let velocity = value.velocity.width
                let target: NavSpace = space == .chats ? .errands : .chats
                let toward = (space == .chats && drag < 0) || (space == .errands && drag > 0)
                // Past halfway, or a flick in the right direction, commits the flip.
                let commits = toward && (abs(drag) > width * 0.45 || abs(velocity) > 380 && (velocity < 0) == (space == .chats))
                if commits {
                    let remaining = width - abs(drag)
                    turn(to: target, velocity: remaining > 1 ? min(abs(velocity) / remaining, 14) : 0)
                } else {
                    let v = abs(drag) > 1 ? min(abs(velocity) / abs(drag), 14) : 0
                    withAnimation(FlipMotion.settle(velocity: v)) { drag = 0 }
                }
            }
    }

    /// Resistance past the end, with nothing beyond: the further you pull, the less it gives.
    private func rubberBand(_ dx: CGFloat) -> CGFloat {
        let limit: CGFloat = 60
        return dx.sign == .minus ? -limit * (1 - 1 / (abs(dx) / limit * 0.55 + 1)) : limit * (1 - 1 / (dx / limit * 0.55 + 1))
    }

    private func turn(to target: NavSpace, velocity: CGFloat) {
        flips += 1
        let animation = reduceMotion ? Animation.easeInOut(duration: 0.25) : FlipMotion.settle(velocity: velocity)
        withAnimation(animation) {
            drag = 0
            flip(target, velocity)
        }
        hint = true
        hintTask?.cancel()
        hintTask = Task {
            try? await Task.sleep(for: .seconds(1.2))
            if !Task.isCancelled { hint = false }
        }
    }
}

private struct SoftBlur: ViewModifier {
    let radius: CGFloat
    func body(content: Content) -> some View { content.blur(radius: radius) }
}

// MARK: - Chat

struct ChatView: View {
    @Bindable var store: AppState
    let openSettings: () -> Void
    var openPersonas: () -> Void = {}
    @State private var photo: PhotosPickerItem?
    @State private var isPreparingPhoto = false
    @State private var composerFocused = false
    @State private var islandField = IslandFieldController()
    @State private var viewport: CGFloat = 600
    @Namespace private var voiceSource
    @State private var conversationOpen = false
    @State private var attachOpen = false
    @State private var photosOpen = false
    @State private var filesOpen = false
    @State private var cameraOpen = false
    @State private var personaOpen = false
    @State private var serverPrompt = false
    @State private var serverName = ""
    @State private var serverToken = ""
    @State private var selecting: ChatMessage?
    /// The message held for its actions; the rest of the chat stays as it is.
    @State private var held: UUID?
    /// When the held message's finger lifted (or the hold began), to tell that release apart from a real tap.
    @State private var heldAt = Date.distantPast

    var body: some View {
        VStack(spacing: 0) {
            ZStack {
                if store.current.messages.isEmpty {
                    EmptyChatView(temporary: store.isTemporary, toggleTemporary: store.toggleTemporary)
                        .transition(.opacity.combined(with: .scale(scale: 0.98)))
                } else {
                    transcript.id(store.selectedID)
                        .environment(\.undoAction, { store.undoAction($0) })
                        .transition(.opacity.combined(with: .offset(y: 12)))
                }
            }
            .frame(maxHeight: .infinity)
            .dismissesKeyboardOnTap() // everywhere above the composer
            .simultaneousGesture(TapGesture().onEnded { closeAttach() })
            .animation(.spring(response: 0.45, dampingFraction: 0.9), value: store.current.messages.isEmpty)
            .animation(.spring(response: 0.45, dampingFraction: 0.9), value: store.selectedID)
            // The chat runs on under the model selector too, fading into the paper at the top.
            .safeAreaInset(edge: .top, spacing: 0) {
                selectorBar.background { TopFade() }
            }
            // The chat runs on under the prompt bar, fading into the paper toward the nav.
            .safeAreaInset(edge: .bottom, spacing: 0) {
                VStack(spacing: 0) {
                    if store.current.messages.isEmpty && store.composerReady && store.draft.isEmpty && !hasAttachments {
                        suggestions.transition(.move(edge: .bottom).combined(with: .opacity))
                    }
                    if hasAttachments && store.composerReady {
                        AttachmentShelf(photo: attachedPhoto, files: store.attachedFiles, preparing: isPreparingPhoto,
                                        removePhoto: { withAnimation(.spring(response: 0.35, dampingFraction: 0.8)) { store.discardAttachment() } },
                                        removeFile: { file in withAnimation(.spring(response: 0.35, dampingFraction: 0.8)) { store.attachedFiles.removeAll { $0.id == file.id } } })
                            .transition(.move(edge: .bottom).combined(with: .opacity))
                    }
                    composer
                }
                .background { BottomFade() }
            }
        }
        .animation(.spring(response: 0.4, dampingFraction: 0.85), value: store.draft.isEmpty)
        .animation(.spring(response: 0.45, dampingFraction: 0.72), value: store.attachedFiles)
        .animation(.spring(response: 0.45, dampingFraction: 0.72), value: store.attachmentName)
        .animation(.spring(response: 0.45, dampingFraction: 0.72), value: isPreparingPhoto)
        .background(Ink.paper)
        // Only one tray at a time: opening + closes the persona capsule and vice versa.
        .onChange(of: attachOpen) { _, open in if open { composerFocused = false; withAnimation(.spring(response: 0.32, dampingFraction: 0.8)) { personaOpen = false } } }
        .onChange(of: personaOpen) { _, open in if open { composerFocused = false; closeAttach() } }
        .onAppear { IslandArt.photos = store.files.images }
        .onChange(of: store.selectedModelID) { _, _ in
            if !store.model.vision { withAnimation(.smooth) { store.discardAttachment() } }
        }
        // Whatever joins the message also lands in the text as an island, at the cursor;
        // whatever leaves it takes its island along.
        .onChange(of: store.activeConnectors) { old, new in
            for id in new.subtracting(old) { if let connector = store.connectors.first(where: { $0.id == id }) { place(.connector(connector)) } }
            for id in old.subtracting(new) { unplace(ref: id) }
        }
        .onChange(of: store.attachedFiles) { old, new in
            for file in new where !old.contains(file) { place(.file(file)) }
            for file in old where !new.contains(file) { unplace(ref: file.id.uuidString) }
        }
        .onChange(of: store.attachmentName) { old, new in
            if let old { unplace(ref: old) }
            if let new { place(.photo(new)) }
        }
        .onChange(of: photo) { _, item in
            guard let item else { return }
            withAnimation(.smooth) { isPreparingPhoto = true }
            Task {
                do {
                    if let data = try await item.loadTransferable(type: Data.self) { await store.attachPhoto(data) }
                } catch { store.error = "The photo couldn’t be imported: \(error.localizedDescription)" }
                photo = nil
                withAnimation(.smooth) { isPreparingPhoto = false }
            }
        }
        .photosPicker(isPresented: $photosOpen, selection: $photo, matching: .images, photoLibrary: .shared())
        .fileImporter(isPresented: $filesOpen, allowedContentTypes: [.text, .pdf, .json, .commaSeparatedText, .sourceCode, .xml, .html], allowsMultipleSelection: true) { result in
            if case .success(let urls) = result { withAnimation(.spring(response: 0.4, dampingFraction: 0.8)) { urls.forEach(store.attachFile) } }
        }
        .fullScreenCover(isPresented: $cameraOpen) {
            CameraPicker { data in Task { await store.attachPhoto(data) } }.ignoresSafeArea()
        }
        .fullScreenCover(isPresented: $conversationOpen) {
            ConversationView(source: Self.demoMode ? ConversationMock() : VoiceConversation(store: store))
                .navigationTransition(.zoom(sourceID: "voice", in: voiceSource))
        }
        .sheet(item: $selecting) { message in
            SelectTextSheet(text: MessageView.rendered(message.role == .user ? message.readable : message.text))
        }
        .alert("Add MCP server", isPresented: $serverPrompt) {
            TextField("https://server.example/mcp", text: $serverName)
                .textInputAutocapitalization(.never).keyboardType(.URL)
            SecureField("Token (optional)", text: $serverToken)
            Button("Connect") {
                let address = serverName, token = serverToken
                serverName = ""; serverToken = ""
                Task { await store.addMCPServer(address, token: token) }
            }
            Button("Cancel", role: .cancel) { serverName = ""; serverToken = "" }
        } message: { Text("Loocal connects over HTTPS and lets the model call this server’s tools. Its token stays in the Keychain.") }
    }

    /// The model selector, with the chat fading out under it as it scrolls up.
    private var selectorBar: some View {
        ModelSelector(options: ModelChoice.all, disabled: store.isWorking, selection: $store.selectedModelID, dimmed: !store.download.isReady)
            .frame(maxWidth: .infinity)
            .padding(.top, 6).padding(.bottom, 8)
            .overlay(alignment: .bottom) {
                if store.isTemporary {
                    HStack(spacing: 5) {
                        LoopGhostMark(progress: 1, color: Ink.secondary).frame(width: 22)
                        Text(store.current.messages.isEmpty ? "Temporary chat · nothing is saved" : "Temporary")
                            .font(.geist(12, .medium))
                            .contentTransition(.opacity)
                    }
                    .foregroundStyle(Ink.secondary)
                    .offset(y: 18)
                    .transition(.opacity.combined(with: .move(edge: .top)))
                }
            }
            .animation(.spring(response: 0.4, dampingFraction: 0.85), value: store.isTemporary)
            .animation(.smooth, value: store.current.messages.isEmpty)
            .dismissesKeyboardOnTap()
            .zIndex(1)
    }

    /// The scripted voice mock is used only in the simulator demo.
    private static var demoMode: Bool {
        #if DEBUG && targetEnvironment(simulator)
        return ProcessInfo.processInfo.arguments.contains("-uiDemo")
        #else
        return false
        #endif
    }

    /// Attachments float above the prompt box; their islands in the text point at them.
    private var hasAttachments: Bool { store.attachmentName != nil || !store.attachedFiles.isEmpty || isPreparingPhoto }

    private var attachedPhoto: UIImage? {
        store.attachmentName.flatMap { UIImage(contentsOfFile: store.files.images.appendingPathComponent($0).path) }
    }

    /// Typed words that can turn into a tool island.
    private var toolWords: [String: Connector] {
        var words: [String: Connector] = [:]
        for connector in store.connectors { words[connector.name.lowercased()] = connector }
        words["web"] = .web
        words["map"] = .maps
        return words
    }

    /// A typed tool name was tapped: it becomes that tool's island, and the tool switches on.
    private func promote(_ connector: Connector) -> Island? {
        let island = Island.connector(connector)
        store.draftIslands.append(island)
        Task {
            if !store.activeConnectors.contains(connector.id) { await store.toggleConnector(connector) }
            // Not allowed (Calendar access refused, say): the word comes back as it was.
            if !store.activeConnectors.contains(connector.id) {
                for placed in store.draftIslands where placed.id == island.id {
                    store.draft = store.draft.replacingOccurrences(of: placed.marker, with: connector.name)
                }
                store.draftIslands.removeAll { $0.id == island.id }
            }
        }
        return island
    }

    /// Puts an island into the draft, unless one for the same thing is already there.
    private func place(_ island: Island) {
        guard !IslandText.present(in: store.draft, store.draftIslands).contains(where: { $0.ref == island.ref }) else { return }
        store.draftIslands.append(island)
        // Picked from the tray or a picker: let that close first, then the island drops in.
        if !islandField.insert(island, delay: 0.32) {
            store.draft += (store.draft.isEmpty || store.draft.hasSuffix(" ") ? "" : " ") + island.marker + " "
        }
    }

    private func unplace(ref: String) {
        for island in store.draftIslands where island.ref == ref {
            store.draft = store.draft.replacingOccurrences(of: island.marker + " ", with: "").replacingOccurrences(of: island.marker, with: "")
        }
        store.draftIslands.removeAll { $0.ref == ref }
    }

    /// The person deleted an island from the text: a tool switches off with it; an
    /// attachment stays attached, just no longer pointed at.
    private func islandRemoved(_ island: Island) {
        store.draftIslands.removeAll { $0.id == island.id }
        // A tool stays on while any of its islands is still in the text.
        let left = IslandText.present(in: store.draft, store.draftIslands).contains { $0.ref == island.ref }
        if island.kind == .connector && !left { store.activeConnectors.remove(island.ref) }
    }

    private func closeAttach() {
        // The release of the long press itself also lands here as a tap: keep it open then.
        if held != nil, Date().timeIntervalSince(heldAt) > 0.8 {
            withAnimation(.spring(response: 0.3, dampingFraction: 0.85)) { held = nil }
        }
        guard attachOpen else { return }
        composerFocused = false
        withAnimation(.spring(response: 0.35, dampingFraction: 0.85)) { attachOpen = false }
    }

    private func handle(_ action: AttachAction) {
        if case .toggle(let connector) = action {
            // One pick, then the tray folds away and the island drops into the text. Picking a
            // tool that's already in the prompt adds it again, wherever the cursor is.
            closeAttach()
            if store.activeConnectors.contains(connector.id) {
                let island = Island.connector(connector)
                store.draftIslands.append(island)
                if !islandField.insert(island, delay: 0.32) { store.draft += " " + island.marker + " " }
            } else {
                Task { await store.toggleConnector(connector) } // switching on places its island
            }
            return
        }
        closeAttach()
        DispatchQueue.main.async {
            switch action {
            case .photos: photosOpen = true
            case .files: filesOpen = true
            case .camera:
                if UIImagePickerController.isSourceTypeAvailable(.camera) { cameraOpen = true }
                else { store.error = "This device has no camera available." }
            case .addServer: serverPrompt = true
            case .toggle: break
            }
        }
    }

    /// The latest turn (your last message and its reply) is given at least a screen of
    /// height. Sending scrolls your message to the top once, then the reply writes downward
    /// into the space below it, so nothing already on screen moves while it streams.
    private var transcript: some View {
        let messages = store.current.messages
        let split = messages.lastIndex(where: { $0.role == .user }) ?? messages.count
        return ScrollViewReader { proxy in
            ScrollView {
                // Non-lazy: lazy stacks estimate row heights and visibly correct them while scrolling.
                VStack(alignment: .leading, spacing: 26) {
                    ForEach(Array(messages[..<split])) { message in row(message) }
                    VStack(alignment: .leading, spacing: 26) {
                        ForEach(Array(messages[split...])) { message in row(message) }
                        if !store.isBusy && !store.performance.isEmpty {
                            Text(store.performance).font(.geist(11).monospacedDigit()).foregroundStyle(Ink.tertiary)
                                .transition(.opacity)
                        }
                    }
                    .frame(minHeight: max(0, viewport - 32), alignment: .top)
                    .id("turn")
                }
                .padding(.horizontal, 20).padding(.vertical, 16)
                .animation(.spring(response: 0.45, dampingFraction: 0.82), value: messages.count)
            }
            .onGeometryChange(for: CGFloat.self) { $0.size.height - $0.safeAreaInsets.bottom } action: { viewport = $0 }
            .scrollDismissesKeyboard(.interactively)
            .defaultScrollAnchor(.bottom)
            .onChange(of: messages.count) { old, new in
                guard new > old, messages.last(where: { $0.role == .user }) != nil else { return }
                withAnimation(.spring(response: 0.5, dampingFraction: 0.9)) { proxy.scrollTo("turn", anchor: .top) }
            }
        }
    }

    private func row(_ message: ChatMessage) -> some View {
        let isHeld = held == message.id
        return MessageView(message: message, imageDirectory: store.files.images, phase: store.phase)
            // Held: it lifts a touch, the others fade back a little, and its actions pop up
            // under it. No dimmed screen.
            .scaleEffect(isHeld ? 1.02 : 1, anchor: message.role == .user ? .trailing : .leading)
            .opacity(held == nil || isHeld ? 1 : 0.4)
            .contentShape(.rect)
            .simultaneousGesture(LongPressGesture(minimumDuration: 0.35).onEnded { _ in
                guard message.status != .generating else { return }
                composerFocused = false
                heldAt = .now
                withAnimation(.spring(response: 0.35, dampingFraction: 0.72)) { held = message.id }
            })
            .overlay(alignment: message.role == .user ? .bottomTrailing : .bottomLeading) {
                if isHeld {
                    actions(for: message)
                        .offset(y: 54) // just under the message (pill height + a gap)
                        .transition(.scale(scale: 0.6, anchor: message.role == .user ? .topTrailing : .topLeading).combined(with: .opacity))
                }
            }
            .zIndex(isHeld ? 1 : 0)
            .sensoryFeedback(.impact(weight: .medium), trigger: isHeld) { _, now in now }
            .transition(.asymmetric(
                insertion: .move(edge: .bottom).combined(with: .opacity).combined(with: .scale(scale: 0.96, anchor: message.role == .user ? .bottomTrailing : .bottomLeading)),
                removal: .opacity))
    }

    /// Long-press actions on a prompt or a reply: a small pill of icons under the message.
    private func actions(for message: ChatMessage) -> some View {
        let plain = message.role == .user ? message.readable : String(MessageView.rendered(message.text).characters)
        return HStack(spacing: 2) {
            action("Copy", "doc.on.doc") {
                // This device only: copied text never syncs to your other devices via Universal Clipboard.
                UIPasteboard.general.setItems([["public.utf8-plain-text": plain]], options: [.localOnly: true])
            }
            if message.role == .user {
                if !store.isBusy {
                    action("Edit", "pencil") {
                        withAnimation(.spring(response: 0.4, dampingFraction: 0.85)) { store.edit(message) }
                        composerFocused = true
                    }
                }
            } else if !message.text.isEmpty {
                action("Read aloud", "speaker.wave.2") { Speaker.shared.say(plain) }
                ShareLink(item: plain) { actionFace("square.and.arrow.up") }
                    .accessibilityLabel("Share")
                if store.canRegenerate(message) {
                    action("Regenerate", "arrow.clockwise") { store.regenerate() }
                }
            }
            action("Select text", "character.cursor.ibeam") { selecting = message }
        }
        .padding(4)
        .background(Capsule().fill(Ink.paper).shadow(color: .black.opacity(0.1), radius: 14, y: 5))
        .overlay(Capsule().stroke(Ink.hairline))
        .fixedSize()
    }

    private func action(_ label: String, _ icon: String, run: @escaping () -> Void) -> some View {
        Button {
            run()
            withAnimation(.spring(response: 0.3, dampingFraction: 0.85)) { held = nil }
        } label: { actionFace(icon) }
        .buttonStyle(PressStyle(scale: 0.85))
        .accessibilityLabel(label)
    }

    private func actionFace(_ icon: String) -> some View {
        Image(systemName: icon)
            .font(.system(size: 15))
            .foregroundStyle(Ink.primary)
            .frame(width: 40, height: 36)
            .contentShape(.rect)
    }

    private var suggestions: some View {
        SuggestionRow { use($0) }
    }

    /// A suggestion goes into the prompt with its tools as islands, which drop in one after
    /// another. A tool that isn't allowed (Calendar access refused) stays a plain word.
    private func use(_ prompt: EmptyChatView.Prompt) {
        var text = prompt.text
        var placed: [Island] = []
        for connector in store.connectors where text.contains("{\(connector.id)}") {
            let island = Island.connector(connector)
            placed.append(island)
            text = text.replacingOccurrences(of: "{\(connector.id)}", with: island.marker)
        }
        store.draftIslands += placed
        store.draft = text
        composerFocused = true
        DispatchQueue.main.async { islandField.dropAll(placed) }
        for island in placed {
            guard let connector = store.connectors.first(where: { $0.id == island.ref }), !store.activeConnectors.contains(connector.id) else { continue }
            Task {
                await store.toggleConnector(connector)
                if !store.activeConnectors.contains(connector.id) {
                    store.draft = store.draft.replacingOccurrences(of: island.marker, with: connector.name)
                    store.draftIslands.removeAll { $0.id == island.id }
                }
            }
        }
    }

    /// Without a downloaded model, the prompt box itself becomes the download box.
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
        // Room for the + and personas trays (46 pt, a little taller than the buttons) to open
        // inside the box with an even margin all round.
        .padding(10)
        .background(Ink.fill, in: .rect(cornerRadius: 29))
        .padding(.horizontal, 12).padding(.bottom, 8)
    }

    private var input: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .bottom, spacing: 6) {
                HStack(spacing: 0) {
                    AttachControl(open: $attachOpen, connectors: store.connectors, active: [], vision: store.model.vision, act: handle)
                        .disabled(store.isBusy || isPreparingPhoto)
                        .zIndex(1) // the tray unrolls over its neighbours
                }
                // Takes only its button's room in the row: the open tray draws over the prompt
                // instead of pushing it aside.
                .frame(width: 38, height: 38, alignment: .leading)
                .zIndex(1)
                IslandField(text: $store.draft, islands: store.draftIslands, placeholder: store.persona.map { "Ask \($0.name)" } ?? "Ask anything",
                            focused: $composerFocused, enabled: !store.isBusy, frozen: attachOpen || personaOpen, controller: islandField,
                            tools: toolWords, removed: islandRemoved, promote: promote)
                    // The text field is a UIKit view and wins taps over SwiftUI drawn above it,
                    // so it steps aside while a tray covers it.
                    .allowsHitTesting(!attachOpen && !personaOpen)
                    // Under an open tray the prompt stays put, a little dimmed.
                    .opacity(attachOpen || personaOpen ? 0.7 : 1)
                    .animation(.easeOut(duration: 0.2), value: attachOpen || personaOpen)
                Button { composerFocused = false; closeAttach(); conversationOpen = true } label: {
                    AudioLinesIcon(size: 19)
                        .foregroundStyle(Ink.primary)
                        .frame(width: 30, height: 38)
                        .contentShape(.rect)
                }
                .buttonStyle(PressStyle(scale: 0.85))
                .matchedTransitionSource(id: "voice", in: voiceSource)
                .accessibilityLabel("Conversation mode")
                .accessibilityIdentifier("conversation")
                .disabled(store.isBusy)
                .opacity(personaOpen ? 0 : 1)
                .animation(.easeOut(duration: 0.15), value: personaOpen)
                ZStack {
                    if store.isBusy {
                        Button { store.stop() } label: {
                            RoundedRectangle(cornerRadius: 2.5).fill(Ink.paper).frame(width: 11, height: 11)
                                .frame(width: 38, height: 38).background(Ink.primary, in: .circle)
                        }
                        .buttonStyle(PressStyle(scale: 0.88))
                        .accessibilityLabel("Stop generation")
                        .transition(.scale(scale: 0.5).combined(with: .opacity))
                    } else {
                        SendControl(store: store, open: $personaOpen, canSend: store.canSend && !isPreparingPhoto, send: { composerFocused = false; store.send() }, addPersona: openPersonas)
                            // Same as the + tray: the personas capsule covers, never pushes.
                            .frame(width: 38, height: 38, alignment: .trailing)
                            .transition(.scale(scale: 0.5).combined(with: .opacity))
                    }
                }
                .animation(.spring(response: 0.35, dampingFraction: 0.7), value: store.isBusy)
            }
        }
        .animation(.spring(response: 0.4, dampingFraction: 0.85), value: store.attachedFiles)
        .animation(.spring(response: 0.3, dampingFraction: 0.75), value: store.canSend)
        .animation(.spring(response: 0.4, dampingFraction: 0.85), value: store.attachmentName)
    }
}

private struct SuggestionRow: View {
    let choose: (EmptyChatView.Prompt) -> Void
    @State private var appeared = false

    var body: some View {
        ScrollView(.horizontal) {
            HStack(spacing: 8) {
                ForEach(Array(EmptyChatView.prompts.enumerated()), id: \.element.title) { i, prompt in
                    Button { choose(prompt) } label: {
                        VStack(alignment: .leading, spacing: 3) {
                            Text(prompt.title).font(.geist(14, .medium)).foregroundStyle(Ink.primary)
                            Text(prompt.detail).font(.geist(13)).foregroundStyle(Ink.secondary)
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

struct EmptyChatView: View {
    let temporary: Bool
    var toggleTemporary: () -> Void = {}
    @State private var appeared = false
    @State private var flips = 0

    /// A suggestion. `{tool}` in its text (a connector id) becomes that tool's island.
    struct Prompt { let title: String; let detail: String; let text: String }
    static let prompts = [
        Prompt(title: "Plan my day", detail: "around what’s already on it", text: "Plan my day around {calendar} and {reminders}"),
        Prompt(title: "Find a spot", detail: "quiet, close, open now", text: "Find a quiet café near me with {maps}"),
        Prompt(title: "Look it up", detail: "fresh from the web", text: "Search {web} for "),
        Prompt(title: "Reply for me", detail: "short, warm, to the point", text: "Draft a short, warm reply to what’s on my {clipboard}"),
    ]

    var body: some View {
        VStack(spacing: 0) {
            Spacer(minLength: 24)
            VStack(spacing: 18) {
                // The logo is the switch: solid loop for a normal chat, dashed for temporary.
                Button {
                    flips += 1
                    withAnimation(.easeInOut(duration: 1.3)) { toggleTemporary() }
                } label: {
                    // The loop unravels into a ghost for a temporary chat, and winds back.
                    LoopGhostMark(progress: temporary ? 1 : 0)
                        .frame(width: 46)
                        .padding(14)
                        .contentShape(.rect)
                }
                .buttonStyle(PressStyle(scale: 0.85))
                .accessibilityLabel(temporary ? "Temporary chat. Tap for a normal chat" : "Start a temporary chat")
                .accessibilityIdentifier("temporaryToggle")
                .sensoryFeedback(.impact(weight: .medium), trigger: flips)
                .rise(appeared, delay: 0)
                ZStack {
                    // Only the title changes, so the mark and title stay put.
                    if temporary {
                        Text("Off the \(Text("record.").font(.serif(40, italic: true)))")
                            .font(.serif(40)).tracking(-0.4)
                            .transition(.blurReplace.combined(with: .scale(0.96)))
                    } else {
                        Text("What’s on \(Text("your mind?").font(.serif(40, italic: true)))")
                            .font(.serif(40)).tracking(-0.4)
                            .transition(.blurReplace.combined(with: .scale(0.96)))
                    }
                }
                .multilineTextAlignment(.center).foregroundStyle(Ink.primary)
                .rise(appeared, delay: 0.06)
            }
            .padding(.horizontal, 32)
            Spacer(minLength: 24)
        }
        .frame(maxWidth: .infinity)
        .onAppear { appeared = true }
    }
}

struct MessageView: View {
    let message: ChatMessage
    let imageDirectory: URL
    var phase = ""
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        if message.role == .user { user } else { assistant }
    }

    private var user: some View {
        VStack(alignment: .trailing, spacing: 8) {
            if let files = message.files, !files.isEmpty {
                HStack(spacing: 6) {
                    ForEach(Array(files.enumerated()), id: \.offset) { i, name in
                        FileCard(name: name, compact: true).rotationEffect(.degrees(i % 2 == 0 ? 3 : -2))
                    }
                }
            }
            if let photo { AttachedPhoto(image: photo) }
            IslandLabel(text: message.text, islands: message.islands ?? [])
                .padding(.horizontal, 16).padding(.vertical, 10)
                .background(Ink.fill, in: .rect(cornerRadius: 22))
        }
        .frame(maxWidth: .infinity, alignment: .trailing)
        .padding(.leading, 48)
    }

    private var assistant: some View {
        VStack(alignment: .leading, spacing: 12) {
            if let tools = message.tools, !tools.isEmpty {
                ToolTrail(tools: tools).transition(.opacity)
            }
            // "Thinking" stays until the first whole word is ready, so the row never hops.
            if shown.isEmpty && message.status == .generating && !(message.tools?.contains { $0.status == .running } ?? false) {
                HStack(spacing: 10) {
                    SpiralLoader(size: 18)
                    Text(phase.isEmpty || phase == "Generating" || phase.hasPrefix("Using") || phase == "Searching the web" || phase == "Checking your sources" ? "Thinking" : phase)
                        .font(.geist(15)).foregroundStyle(Ink.secondary)
                        .shimmer()
                        .contentTransition(.opacity)
                        .animation(.smooth, value: phase)
                }
                .padding(.top, 2)
                // Leaves at once, so it never crossfades under a tool box or the first words.
                .transition(.asymmetric(insertion: .opacity, removal: .identity))
            } else if !shown.isEmpty {
                // A tool's widget sits in the text, right after the opening paragraph.
                let (lead, rest) = Self.split(shown)
                HandwrittenText(text: Self.rendered(lead), live: message.status == .generating && rest.isEmpty)
                    .font(.geist(16)).foregroundStyle(Ink.primary).lineSpacing(5)
                    .transition(.opacity)
                widgets
                if !rest.isEmpty {
                    HandwrittenText(text: Self.rendered(rest), live: message.status == .generating)
                        .font(.geist(16)).foregroundStyle(Ink.primary).lineSpacing(5)
                        .transition(.opacity)
                }
                if message.status == .generating { SpiralLoader(size: 14, color: Ink.secondary).transition(.opacity) }
            } else if message.status != .generating {
                widgets
            }
            if message.status == .interrupted {
                Text("Stopped · partial reply saved").font(.geist(12)).foregroundStyle(Ink.tertiary)
                    .transition(.opacity)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .animation(.smooth(duration: 0.3), value: shown.isEmpty)
        .animation(.smooth(duration: 0.3), value: message.status)
        .animation(.spring(response: 0.45, dampingFraction: 0.85), value: message.tools)
    }

    private var shown: String { StreamingText.stable(message.text, live: message.status == .generating) }

    @ViewBuilder private var widgets: some View {
        let found = (message.tools ?? []).filter { $0.status == .done }.compactMap(\.widget)
        ForEach(Array(found.enumerated()), id: \.offset) { _, widget in
            ToolWidgetView(widget: widget)
                .padding(.vertical, 2)
                .transition(.scale(scale: 0.94, anchor: .top).combined(with: .opacity))
        }
    }

    /// The first paragraph, and everything after it.
    static func split(_ text: String) -> (String, String) {
        guard let range = text.range(of: "\n\n") else { return (text, "") }
        let rest = text[range.upperBound...].trimmingCharacters(in: .newlines)
        return (String(text[..<range.lowerBound]), rest)
    }

    private var photo: UIImage? {
        guard let name = message.imageName else { return nil }
        return UIImage(contentsOfFile: imageDirectory.appendingPathComponent(name).path)
    }

    /// Light inline Markdown (bold, italics, code, links); falls back to plain text.
    static func rendered(_ text: String) -> AttributedString {
        (try? AttributedString(markdown: text, options: .init(interpretedSyntax: .inlineOnlyPreservingWhitespace))) ?? AttributedString(text)
    }
}

/// A photo sent with a message: sized to its own aspect so the rounded corners sit on the
/// picture itself (not on an empty frame around it), and flush with the right edge.
private struct AttachedPhoto: View {
    let image: UIImage

    var body: some View {
        let w = max(image.size.width, 1), h = max(image.size.height, 1)
        let scale = min(230 / w, 280 / h)
        Image(uiImage: image)
            .resizable()
            .scaledToFill()
            .frame(width: w * scale, height: h * scale)
            .clipShape(.rect(cornerRadius: 20))
            .overlay(RoundedRectangle(cornerRadius: 20).stroke(Ink.hairline))
            .accessibilityLabel("Attached photo")
    }
}

/// One step a reply took before answering: a source it read or a tool it called. Running,
/// its title shimmers; done, it can open to show what it found.
extension EnvironmentValues {
    /// Undo for an action box in a reply (a reminder, an event, a memory).
    @Entry var undoAction: (UUID) -> Void = { _ in }
}

// MARK: - History

struct HistoryView: View {
    let store: AppState
    let open: (Conversation) -> Void
    @State private var query = ""
    @State private var appeared = false

    private var matches: [Conversation] {
        let all = store.history
        let q = query.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty else { return all }
        return all.filter { $0.messages.contains { $0.text.localizedCaseInsensitiveContains(q) } }
    }

    private var groups: [(String, [Conversation])] {
        let calendar = Calendar.current
        let pinned = matches.filter(\.isPinned).sorted { $0.createdAt > $1.createdAt }
        let buckets = Dictionary(grouping: matches.filter { !$0.isPinned }) { c -> Int in
            if calendar.isDateInToday(c.createdAt) { return 0 }
            if calendar.isDateInYesterday(c.createdAt) { return 1 }
            if let days = calendar.dateComponents([.day], from: c.createdAt, to: .now).day, days < 7 { return 2 }
            return 3
        }
        let names = ["Today", "Yesterday", "Previous 7 days", "Earlier"]
        let dated = buckets.keys.sorted().map { (names[$0], buckets[$0]!.sorted { $0.createdAt > $1.createdAt }) }
        return (pinned.isEmpty ? [] : [("Pinned", pinned)]) + dated
    }

    var body: some View {
        VStack(spacing: 0) {
            if store.history.isEmpty {
                Spacer()
                VStack(spacing: 10) {
                    LoopMark(color: Ink.tertiary).frame(width: 40)
                    Text("Nothing here yet.").font(.serif(28, italic: true)).foregroundStyle(Ink.primary)
                    Text("Your conversations stay on this iPhone.").font(.geist(14)).foregroundStyle(Ink.secondary)
                }
                .rise(appeared, delay: 0.08)
                Spacer()
            } else {
                List {
                    ForEach(Array(groups.enumerated()), id: \.element.0) { g, group in
                        Section {
                            ForEach(Array(group.1.enumerated()), id: \.element.id) { i, conversation in
                                row(conversation).rise(appeared, delay: min(0.04 * Double(g * 2 + i), 0.3), distance: 8)
                            }
                        } header: {
                            Text(group.0).font(.geist(13, .medium)).foregroundStyle(Ink.secondary).textCase(nil)
                        }
                    }
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
                .scrollDismissesKeyboard(.immediately)
                .contentMargins(.top, 8, for: .scrollContent)
                .animation(.spring(response: 0.4, dampingFraction: 0.85), value: matches.map(\.id))
                .animation(.spring(response: 0.4, dampingFraction: 0.85), value: matches.map(\.isPinned))
                .dismissesKeyboardOnTap()
            }
        }
        .frame(maxHeight: .infinity)
        .safeAreaInset(edge: .bottom, spacing: 0) {
            HStack(spacing: 10) {
                Image(systemName: "magnifyingglass").font(.system(size: 15)).foregroundStyle(Ink.tertiary)
                TextField("Search chats", text: $query).font(.geist(16)).foregroundStyle(Ink.primary)
                if !query.isEmpty {
                    Button { withAnimation(.smooth) { query = "" } } label: {
                        Image(systemName: "xmark.circle.fill").foregroundStyle(Ink.tertiary)
                    }
                    .buttonStyle(PressStyle(scale: 0.85))
                    .accessibilityLabel("Clear search")
                    .transition(.scale.combined(with: .opacity))
                }
            }
            // Same size as the chat's prompt bar.
            .padding(.horizontal, 18).frame(height: 50)
            .background(Ink.fill, in: .capsule)
            .padding(.horizontal, 12).padding(.top, 8).padding(.bottom, 8)
            .animation(.spring(response: 0.3), value: query.isEmpty)
            .background { BottomFade() }
            .rise(appeared, delay: 0.1)
        }
        .background(Ink.paper)
        .onAppear { appeared = true }
    }

    private func row(_ conversation: Conversation) -> some View {
        Button { open(conversation) } label: {
            VStack(alignment: .leading, spacing: 4) {
                HStack(alignment: .firstTextBaseline) {
                    if conversation.isPinned {
                        Image(systemName: "pin.fill").font(.system(size: 11)).foregroundStyle(Ink.tertiary)
                            .rotationEffect(.degrees(35))
                            .transition(.scale.combined(with: .opacity))
                    }
                    Text(conversation.title).font(.geist(16)).foregroundStyle(Ink.primary).lineLimit(1)
                    Spacer(minLength: 12)
                    // Still being answered, while you read another chat.
                    if store.replying.contains(conversation.id) {
                        SpiralLoader(size: 12, color: Ink.tertiary).transition(.opacity)
                    }
                    Text(conversation.createdAt, format: Calendar.current.isDateInToday(conversation.createdAt) ? .dateTime.hour().minute() : .dateTime.month(.abbreviated).day())
                        .font(.geist(12).monospacedDigit()).foregroundStyle(Ink.tertiary)
                }
                if let reply = conversation.messages.last(where: { $0.role == .assistant && !$0.text.isEmpty }) {
                    Text(String(MessageView.rendered(reply.text).characters)).font(.geist(14)).foregroundStyle(Ink.secondary).lineLimit(1)
                }
            }
            .padding(.vertical, 6)
            .contentShape(.rect)
        }
        .buttonStyle(PressStyle(scale: 0.98))
        .listRowBackground(conversation.id == store.selectedID ? Ink.fill : Ink.paper)
        .listRowSeparatorTint(Ink.hairline)
        .listRowInsets(EdgeInsets(top: 4, leading: 20, bottom: 4, trailing: 20))
        .swipeActions {
            Button("Delete", systemImage: "trash", role: .destructive) { withAnimation(.smooth) { store.delete(conversation) } }
                .tint(.red) // the app-wide ink tint would otherwise turn it white on white
        }
        .swipeActions(edge: .leading) {
            Button(conversation.isPinned ? "Unpin" : "Pin", systemImage: conversation.isPinned ? "pin.slash" : "pin") {
                withAnimation(.spring(response: 0.4, dampingFraction: 0.85)) { store.togglePin(conversation) }
            }
            .tint(Ink.primary)
        }
        .contextMenu {
            Button(conversation.isPinned ? "Unpin" : "Pin", systemImage: conversation.isPinned ? "pin.slash" : "pin") {
                withAnimation(.spring(response: 0.4, dampingFraction: 0.85)) { store.togglePin(conversation) }
            }
        }
    }
}

#Preview("New chat") { EmptyChatView(temporary: false) }
#Preview("Destination bar") { DestinationBar(space: .chats, selections: [.chats: .chat, .errands: .errand], choose: { _ in }) }

/// A message opened for free text selection.
struct SelectTextSheet: View {
    let text: AttributedString
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text("Select text").font(.geist(15, .medium)).foregroundStyle(Ink.secondary)
                Spacer()
                Button("Done") { dismiss() }.font(.geist(15, .medium)).foregroundStyle(Ink.primary)
            }
            .padding(.horizontal, 20).padding(.top, 18).padding(.bottom, 12)
            ScrollView {
                Text(text)
                    .font(.geist(17)).foregroundStyle(Ink.primary).lineSpacing(5)
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 20).padding(.bottom, 24)
            }
        }
        .background(Ink.paper.ignoresSafeArea())
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
        .presentationCornerRadius(28)
    }
}

/// Top toast for errors (exclamation) and notices such as an interrupted reply. Swipe up
/// or tap to dismiss; it leaves on its own after a few seconds.
struct Toast: View {
    @Bindable var store: AppState
    @State private var drag: CGFloat = 0

    private var message: (text: String, isError: Bool)? {
        if let error = store.error { return (error, true) }
        if let notice = store.notice { return (notice, false) }
        return nil
    }

    var body: some View {
        ZStack {
            if let message {
                HStack(alignment: .top, spacing: 10) {
                    Image(systemName: message.isError ? "exclamationmark.circle" : "pause.circle")
                        .font(.system(size: 16, weight: .medium))
                        .foregroundStyle(Ink.primary)
                    Text(message.text)
                        .font(.geist(14)).foregroundStyle(Ink.primary).lineSpacing(2)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                .padding(.horizontal, 16).padding(.vertical, 13)
                .background(RoundedRectangle(cornerRadius: 22).fill(Ink.paper).overlay(RoundedRectangle(cornerRadius: 22).stroke(Ink.hairline)).shadow(color: .black.opacity(0.12), radius: 18, y: 6))
                .padding(.horizontal, 14).padding(.top, 4)
                .offset(y: min(0, drag))
                .gesture(DragGesture().onChanged { drag = $0.translation.height }.onEnded { value in
                    if value.translation.height < -20 { dismiss() }
                    withAnimation(.spring(response: 0.3, dampingFraction: 0.8)) { drag = 0 }
                })
                .onTapGesture { dismiss() }
                .transition(.move(edge: .top).combined(with: .opacity).combined(with: .scale(scale: 0.96, anchor: .top)))
                .id(message.text)
                .task(id: message.text) {
                    try? await Task.sleep(for: .seconds(message.isError ? 6 : 4))
                    if !Task.isCancelled { dismiss() }
                }
                .accessibilityAddTraits(.isStaticText)
                .accessibilityAction(named: "Dismiss") { dismiss() }
            }
        }
        .animation(.spring(response: 0.45, dampingFraction: 0.82), value: message?.text)
        .sensoryFeedback(.warning, trigger: store.error) { _, new in new != nil }
    }

    private func dismiss() {
        withAnimation(.spring(response: 0.4, dampingFraction: 0.85)) {
            if store.error != nil { store.error = nil } else { store.notice = nil }
        }
    }
}

/// The composer while the selected model isn't installed: one tap downloads it, then it
/// shows progress in place and turns back into the prompt box when ready.
struct DownloadComposer: View {
    @Bindable var download: ModelDownload
    let model: ModelChoice

    private var size: String { ByteCountFormatter.string(fromByteCount: download.spec.totalBytes, countStyle: .file) }

    var body: some View {
        Button { download.start() } label: {
            VStack(alignment: .leading, spacing: 10) {
                HStack(spacing: 12) {
                    ZStack {
                        if download.isDownloading {
                            SpiralLoader(size: 18, color: Ink.primary).transition(.scale.combined(with: .opacity))
                        } else {
                            Image(systemName: download.error == nil ? "arrow.down" : "arrow.clockwise")
                                .font(.system(size: 15, weight: .semibold))
                                .foregroundStyle(Ink.paper)
                                .transition(.scale.combined(with: .opacity))
                        }
                    }
                    .frame(width: 38, height: 38)
                    .background(download.isDownloading ? Ink.paper : Ink.primary, in: .circle)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(download.isDownloading ? "Downloading \(model.name)" : download.error == nil ? "Download \(model.name)" : "Retry \(model.name)")
                            .font(.geist(15, .medium)).foregroundStyle(Ink.primary)
                            .contentTransition(.opacity)
                        Text(download.error ?? (download.isDownloading ? "You can leave the app meanwhile" : "\(size) · Wi-Fi · then fully offline"))
                            .font(.geist(13)).foregroundStyle(Ink.secondary).lineLimit(2)
                            .contentTransition(.opacity)
                    }
                    Spacer(minLength: 8)
                    if download.isDownloading {
                        Text(download.progress, format: .percent.precision(.fractionLength(0)))
                            .font(.geist(14, .medium).monospacedDigit()).foregroundStyle(Ink.primary)
                            .contentTransition(.numericText(value: download.progress))
                            .padding(.trailing, 8)
                            .transition(.opacity)
                    }
                }
                if download.isDownloading {
                    GeometryReader { proxy in
                        Capsule().fill(Ink.hairline).overlay(alignment: .leading) {
                            Capsule().fill(Ink.primary).frame(width: max(4, proxy.size.width * download.progress))
                        }
                    }
                    .frame(height: 3)
                    .padding(.horizontal, 6).padding(.bottom, 4)
                    .transition(.opacity.combined(with: .move(edge: .top)))
                }
            }
            .contentShape(.rect)
        }
        .buttonStyle(PressStyle(scale: 0.98))
        .disabled(download.isDownloading)
        .animation(.spring(response: 0.4, dampingFraction: 0.85), value: download.isDownloading)
        .animation(.smooth(duration: 0.6), value: download.progress)
        .animation(.smooth, value: download.error)
        .accessibilityIdentifier("composerDownload")
    }
}

#if DEBUG && targetEnvironment(simulator)
/// Renders the bot app icon (light and dark) to Documents, for the asset catalog.
@MainActor
enum IconRender {
    static func write() {
        for dark in [false, true] {
            let view = ZStack {
                (dark ? Color.black : Color.white)
                PebbleBot(mood: .neutral, shape: .pebble, size: 660, lively: false)
                    .offset(y: 10)
            }
            .frame(width: 1024, height: 1024)
            .environment(\.colorScheme, dark ? .dark : .light)
            let renderer = ImageRenderer(content: view)
            renderer.scale = 1
            guard let image = renderer.uiImage, let data = image.pngData() else { continue }
            let url = URL.documentsDirectory.appendingPathComponent(dark ? "AppIconBot-dark.png" : "AppIconBot.png")
            try? data.write(to: url)
            print("ICON \(url.path)")
        }
    }

    /// The compact island while paused, at 3× its real size.
    static func sleep() {
        let view = HStack {
            LoopMarkShape().stroke(.white, style: StrokeStyle(lineWidth: 1.6, lineCap: .round, lineJoin: .round)).frame(width: 22, height: 11)
            Spacer()
            SleepingPebble(drift: 0).frame(width: 18.4, height: 18.4)
        }
        .padding(.horizontal, 10)
        .frame(width: 230, height: 37)
        .background(Color.black, in: .capsule)
        .padding(10).background(Color.white)
        let renderer = ImageRenderer(content: view)
        renderer.scale = 4
        if let data = renderer.uiImage?.pngData() { try? data.write(to: URL.documentsDirectory.appendingPathComponent("sleep.png")) }
    }

    static func moods() {
        let view = LazyVGrid(columns: Array(repeating: GridItem(.fixed(150)), count: 5), spacing: 10) {
            ForEach(BotMood.allCases, id: \.self) { mood in
                VStack {
                    PebbleBot(mood: mood, size: 120, lively: false)
                    Text(String(describing: mood)).font(.caption)
                }
            }
        }
        .padding(20).background(Color.white)
        let renderer = ImageRenderer(content: view)
        renderer.scale = 1
        if let data = renderer.uiImage?.pngData() { try? data.write(to: URL.documentsDirectory.appendingPathComponent("moods.png")) }
    }
}
#endif
