import MapKit
import SwiftUI

// MARK: - Tool calls, quietly

/// What a reply did on the way, as one line of grey text: "Searched the web · 3 results".
/// Several steps fold into "Used 3 tools". Tap to open the steps, each with what it found.
struct ToolTrail: View {
    let tools: [ToolUse]
    @State private var open = false

    private var running: ToolUse? { tools.last { $0.status == .running } }
    private var single: ToolUse? { tools.count == 1 ? tools[0] : nil }
    private var opens: Bool { single.map { !$0.links.isEmpty || !ToolStep.summary($0).isEmpty } ?? true }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                Button { if opens { withAnimation(.spring(response: 0.38, dampingFraction: 0.86)) { open.toggle() } } } label: { header }
                    .buttonStyle(PressStyle(scale: 0.98))
                    .accessibilityHint(opens ? (open ? "Hides the steps" : "Shows the steps") : "")
                if let single { UndoLink(use: single) }
            }
            if open {
                VStack(alignment: .leading, spacing: 10) {
                    if let single {
                        ToolStep.details(single)
                    } else {
                        ForEach(tools) { ToolStep(use: $0) }
                    }
                }
                .padding(.leading, 12)
                .overlay(alignment: .leading) { Rectangle().fill(Ink.hairline).frame(width: 1).padding(.leading, 6) }
                .transition(.opacity.combined(with: .move(edge: .top)))
            }
        }
        .animation(.spring(response: 0.4, dampingFraction: 0.86), value: tools)
    }

    private var header: some View {
        HStack(spacing: 7) {
            ZStack {
                if running != nil {
                    SpiralLoader(size: 12, color: Ink.secondary).transition(.scale.combined(with: .opacity))
                } else {
                    Image(systemName: single.map(ToolStep.icon) ?? "square.stack.3d.up")
                        .font(.system(size: 12, weight: .medium))
                        .transition(.scale.combined(with: .opacity))
                }
            }
            .frame(width: 14)
            Text(title)
                .font(.geist(14))
                .lineLimit(1)
                .shimmer(running != nil)
                .contentTransition(.opacity)
            if opens {
                Image(systemName: "chevron.right")
                    .font(.system(size: 9, weight: .semibold))
                    .foregroundStyle(Ink.tertiary)
                    .rotationEffect(.degrees(open ? 90 : 0))
            }
        }
        .foregroundStyle(Ink.secondary)
        .contentShape(.rect)
    }

    private var title: String {
        if let running { return running.title }
        if let single {
            let count = single.links.count
            return count > 0 && single.status == .done ? "\(single.title) · \(count) result\(count == 1 ? "" : "s")" : single.title
        }
        let failed = tools.filter { $0.status == .failed }.count
        return "Used \(tools.count) tools" + (failed > 0 ? " · \(failed) didn’t work" : "")
    }
}

/// One step inside an opened trail.
private struct ToolStep: View {
    let use: ToolUse

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 7) {
                ZStack {
                    if use.status == .running { SpiralLoader(size: 11, color: Ink.secondary) }
                    else { Image(systemName: Self.icon(use)).font(.system(size: 11, weight: .medium)) }
                }
                .frame(width: 14)
                Text(use.title).font(.geist(13.5)).shimmer(use.status == .running)
                UndoLink(use: use)
            }
            .foregroundStyle(Ink.secondary)
            Self.details(use).padding(.leading, 21)
        }
    }

    static func icon(_ use: ToolUse) -> String {
        switch use.status {
        case .failed: "exclamationmark.circle"
        case .undone: "arrow.uturn.backward"
        default: use.icon
        }
    }

    static func summary(_ use: ToolUse) -> String {
        guard !use.detail.isEmpty else { return "" }
        return use.kind == Connector.web.id ? "“\(use.detail)”" : use.detail
    }

    /// The query or note, then the sources it found, each one a link.
    @ViewBuilder static func details(_ use: ToolUse) -> some View {
        VStack(alignment: .leading, spacing: 5) {
            if !summary(use).isEmpty {
                Text(summary(use)).font(.geist(12.5)).foregroundStyle(Ink.tertiary).lineLimit(3)
            }
            ForEach(Array(use.links.enumerated()), id: \.offset) { _, link in SourceLink(link: link) }
        }
    }
}

private struct SourceLink: View {
    let link: ToolUse.Link
    @Environment(\.openURL) private var openURL

    var body: some View {
        Button { openURL(link.url) } label: {
            HStack(spacing: 6) {
                Text(link.title).font(.geist(13)).foregroundStyle(Ink.secondary).lineLimit(1)
                Text(link.host).font(.geist(12)).foregroundStyle(Ink.tertiary).lineLimit(1).layoutPriority(-1)
                Image(systemName: "arrow.up.right").font(.system(size: 9, weight: .semibold)).foregroundStyle(Ink.tertiary)
            }
            .contentShape(.rect)
        }
        .buttonStyle(PressStyle(scale: 0.98))
    }
}

private struct UndoLink: View {
    let use: ToolUse
    @Environment(\.undoAction) private var undo

    var body: some View {
        if use.action?.undoable == true && use.status == .done {
            Button { undo(use.id) } label: {
                Text("Undo").font(.geist(13, .medium)).foregroundStyle(Ink.primary).underline(color: Ink.tertiary)
            }
            .buttonStyle(PressStyle(scale: 0.9))
            .transition(.opacity)
        }
    }
}

// MARK: - Widgets in the reply

/// What a tool found, drawn into the reply: the one place colour comes onto the page.
struct ToolWidgetView: View {
    let widget: ToolWidget

    var body: some View {
        switch widget {
        case .map(let title, let pins, let here): MapWidget(title: title, pins: pins, here: here)
        case .calculation(let expression, let result): CalculationWidget(expression: expression, result: result)
        case .translation(let language, let text): TranslationWidget(language: language, text: text)
        }
    }
}

/// A widget's quiet heading: glyph and name in grey, like the rest of the page's labels.
private struct WidgetLabel: View {
    let title: String
    let icon: String

    var body: some View {
        HStack(spacing: 6) {
            Image(systemName: icon).font(.system(size: 11, weight: .semibold))
            Text(title).font(.geist(13, .medium))
        }
        .foregroundStyle(Ink.secondary)
    }
}

/// Every widget sits in the same plain box: the soft fill, rounded, no outline.
private extension View {
    func widgetBox() -> some View {
        frame(maxWidth: .infinity, alignment: .leading)
            .background(Ink.fill, in: .rect(cornerRadius: 22))
            .clipShape(.rect(cornerRadius: 22))
    }
}

private struct MapWidget: View {
    let title: String
    let pins: [ToolWidget.Pin]
    let here: ToolWidget.Pin?
    @Environment(\.openURL) private var openURL

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Map(initialPosition: .automatic, interactionModes: []) {
                ForEach(Array(pins.prefix(5).enumerated()), id: \.offset) { i, pin in
                    Annotation(pin.name, coordinate: pin.coordinate, anchor: .bottom) {
                        Text("\(i + 1)")
                            .font(.geist(12, .semibold))
                            .foregroundStyle(Ink.paper)
                            .frame(width: 24, height: 24)
                            .background(Ink.primary, in: .circle)
                    }
                    .annotationTitles(.hidden)
                }
                if let here {
                    Annotation("You", coordinate: here.coordinate) {
                        Circle().fill(Ink.primary).frame(width: 10, height: 10)
                            .padding(3).background(Ink.paper, in: .circle)
                    }
                    .annotationTitles(.hidden)
                }
            }
            .mapStyle(.standard(elevation: .flat, pointsOfInterest: .excludingAll))
            .frame(height: 164)
            .clipShape(.rect(cornerRadius: 16))
            .padding(6)
            .allowsHitTesting(false)

            VStack(spacing: 0) {
                ForEach(Array(pins.prefix(3).enumerated()), id: \.offset) { i, pin in
                    Button { if let url = pin.url { openURL(url) } } label: {
                        HStack(spacing: 12) {
                            Text("\(i + 1)").font(.geist(13, .medium).monospacedDigit()).foregroundStyle(Ink.tertiary)
                                .frame(width: 12)
                            Text(pin.name).font(.geist(15)).foregroundStyle(Ink.primary).lineLimit(1)
                            Spacer(minLength: 8)
                            Text(pin.detail).font(.geist(13).monospacedDigit()).foregroundStyle(Ink.secondary).lineLimit(1)
                        }
                        .padding(.horizontal, 16).padding(.vertical, 10)
                        .contentShape(.rect)
                    }
                    .buttonStyle(PressStyle(scale: 0.98))
                }
            }
            .padding(.top, 2).padding(.bottom, 6)
        }
        .widgetBox()
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Map of \(title)")
    }
}

private struct CalculationWidget: View {
    let expression: String
    let result: String

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            WidgetLabel(title: expression.isEmpty ? "Calculator" : expression, icon: "function")
            Text(result)
                .font(.serif(result.count > 18 ? 28 : 44))
                .foregroundStyle(Ink.primary)
                .lineLimit(2).minimumScaleFactor(0.6)
                .textSelection(.enabled)
        }
        .padding(.horizontal, 18).padding(.vertical, 16)
        .widgetBox()
        .accessibilityElement(children: .combine)
    }
}

private struct TranslationWidget: View {
    let language: String
    let text: String

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                WidgetLabel(title: language, icon: "character.bubble")
                Spacer()
                Button { Speaker.shared.say(text) } label: {
                    Image(systemName: "speaker.wave.2").font(.system(size: 14)).foregroundStyle(Ink.primary)
                        .frame(width: 32, height: 32).contentShape(.rect)
                }
                .buttonStyle(PressStyle(scale: 0.88))
                .accessibilityLabel("Read the translation aloud")
            }
            Text(text).font(.serif(28)).foregroundStyle(Ink.primary).textSelection(.enabled)
        }
        .padding(.leading, 18).padding(.trailing, 10).padding(.vertical, 14)
        .widgetBox()
    }
}

private extension ToolWidget.Pin {
    var coordinate: CLLocationCoordinate2D { CLLocationCoordinate2D(latitude: latitude, longitude: longitude) }
}
