import SwiftUI
import UIKit

// MARK: - Shared typography

/// Text with islands in it, laid out on fixed-height lines, so a line holding a pill is
/// exactly as tall as one without. The prompt and sent messages use the same layout.
@MainActor
enum IslandType {
    static let size: CGFloat = 16
    /// Every line is this tall; a pill (26 pt plus its shadow) overhangs a little rather
    /// than pushing the line apart.
    static let lineHeight: CGFloat = 24
    static let font = UIFont(name: "Geist-Regular", size: size) ?? .systemFont(ofSize: size)

    /// Extra room between lines once two or more pills are in the text, just enough that
    /// pills on neighbouring lines never touch.
    static let pillGap: CGFloat = 3

    static func spacing(forPills count: Int) -> CGFloat { count > 1 ? pillGap : 0 }

    static func paragraph(spacing: CGFloat = 0) -> NSParagraphStyle {
        let style = NSMutableParagraphStyle()
        style.minimumLineHeight = lineHeight
        style.maximumLineHeight = lineHeight
        style.lineSpacing = spacing
        return style
    }

    static func attributes(_ color: UIColor = UIColor(Ink.primary), spacing: CGFloat = 0) -> [NSAttributedString.Key: Any] {
        [.font: font, .foregroundColor: color, .paragraphStyle: paragraph(spacing: spacing), .baselineOffset: ((lineHeight - font.lineHeight) / 4).rounded()]
    }

    /// In the text, an island is only an empty slot: as wide as its pill and exactly one
    /// line tall, so a line holding one is as tall as any other. The pill itself is drawn
    /// over the slot by `IslandCanvas`, free to overhang the line.
    static func slot(for image: UIImage) -> CGRect {
        let extent = font.ascender - font.descender
        return CGRect(x: 0, y: (font.descender - (lineHeight - extent) / 2).rounded(),
                      width: image.size.width - IslandArt.margin.left - IslandArt.margin.right, height: lineHeight)
    }

    enum Look { case normal, faded, hidden }

    private static var blanks: [CGSize: UIImage] = [:]

    static func attachment(for island: Island, traits: UITraitCollection) -> NSAttributedString {
        let attachment = IslandAttachment()
        attachment.islandID = island.id
        let slot = slot(for: IslandArt.image(island, traits: traits))
        let blank = blanks[slot.size] ?? UIGraphicsImageRenderer(size: slot.size).image { _ in }
        blanks[slot.size] = blank
        attachment.image = blank
        attachment.bounds = slot
        let string = NSMutableAttributedString(attachment: attachment)
        string.addAttributes(attributes(), range: NSRange(location: 0, length: string.length))
        return string
    }

    static func build(_ text: String, _ islands: [Island], traits: UITraitCollection) -> NSAttributedString {
        let built = NSMutableAttributedString()
        let pieces = IslandText.pieces(text, islands)
        let pills = pieces.filter { if case .island = $0 { return true } else { return false } }.count
        defer { built.addAttribute(.paragraphStyle, value: paragraph(spacing: spacing(forPills: pills)), range: NSRange(location: 0, length: built.length)) }
        for piece in pieces {
            switch piece {
            case .text(let words): built.append(NSAttributedString(string: words, attributes: attributes()))
            case .island(let island): built.append(attachment(for: island, traits: traits))
            }
        }
        return built
    }
}

/// The text attachment standing for one island inside the prompt.
final class IslandAttachment: NSTextAttachment {
    var islandID = ""
}

extension CGSize: @retroactive Hashable {
    public func hash(into hasher: inout Hasher) { hasher.combine(width); hasher.combine(height) }
}

/// A text view that draws each island's pill over its slot in the text, so pills can be
/// taller than the line without pushing anything apart.
class IslandCanvas: UITextView {
    var islands: [String: Island] = [:]
    /// How each island's pill shows right now (normal, faded while held, hidden as a gap).
    var looks: [String: IslandType.Look] = [:]
    private(set) var pills: [String: UIImageView] = [:]

    override func layoutSubviews() {
        super.layoutSubviews()
        layoutPills()
    }

    /// Where an attachment character sits, without forcing a new layout pass.
    func slotRect(at index: Int) -> CGRect? {
        guard let start = position(from: beginningOfDocument, offset: index),
              let end = position(from: start, offset: 1),
              let span = textRange(from: start, to: end) else { return nil }
        let rect = firstRect(for: span)
        return rect.isNull || rect.isInfinite ? nil : rect
    }

    func layoutPills() {
        var seen = Set<String>()
        textStorage.enumerateAttribute(.attachment, in: NSRange(location: 0, length: textStorage.length)) { value, range, _ in
            guard let attachment = value as? IslandAttachment, let island = islands[attachment.islandID],
                  let slot = slotRect(at: range.location) else { return }
            seen.insert(island.id)
            let pill = pills[island.id] ?? {
                let view = UIImageView()
                view.isUserInteractionEnabled = false
                addSubview(view)
                pills[island.id] = view
                return view
            }()
            let art = IslandArt.image(island, traits: traitCollection)
            if pill.image !== art { pill.image = art }
            // Centred on the slot (one line tall), which is centred on the text.
            // Bounds and centre, not frame: a pill mid-animation carries a transform.
            pill.bounds = CGRect(origin: .zero, size: art.size)
            pill.center = CGPoint(x: slot.minX - IslandArt.margin.left + art.size.width / 2,
                                  y: (slot.midY - IslandArt.height / 2 - IslandArt.margin.top).rounded() + art.size.height / 2)
            switch looks[island.id] ?? .normal {
            case .normal: if pill.layer.animationKeys()?.contains("opacity") != true { pill.alpha = 1 }
            case .faded: pill.alpha = 0.3
            case .hidden: pill.alpha = 0
            }
        }
        for (id, pill) in pills where !seen.contains(id) {
            pill.removeFromSuperview()
            pills[id] = nil
        }
    }
}

// MARK: - A sent prompt

/// A sent prompt with its islands, laid out exactly as it was typed.
struct IslandLabel: UIViewRepresentable {
    let text: String
    let islands: [Island]

    func makeUIView(context: Context) -> IslandCanvas {
        let view = IslandCanvas()
        view.clipsToBounds = false
        view.isEditable = false
        view.isSelectable = false
        view.isScrollEnabled = false
        view.isUserInteractionEnabled = false
        view.backgroundColor = .clear
        view.textContainerInset = .zero
        view.textContainer.lineFragmentPadding = 0
        view.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        return view
    }

    func updateUIView(_ view: IslandCanvas, context: Context) {
        view.islands = Dictionary(islands.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
        view.attributedText = IslandType.build(text, islands, traits: view.traitCollection)
        view.setNeedsLayout()
        view.accessibilityLabel = IslandText.titled(text, islands)
    }

    /// As wide as its longest line (up to what's offered), as tall as its lines.
    func sizeThatFits(_ proposal: ProposedViewSize, uiView view: IslandCanvas, context: Context) -> CGSize? {
        let limit = proposal.width ?? 300
        let used = view.attributedText.boundingRect(with: CGSize(width: limit, height: .greatestFiniteMagnitude), options: [.usesLineFragmentOrigin, .usesFontLeading], context: nil)
        let width = min(limit, ceil(used.width) + 1)
        let height = view.sizeThatFits(CGSize(width: width, height: .greatestFiniteMagnitude)).height
        return CGSize(width: width, height: height)
    }
}

// MARK: - The prompt field

/// Lets the chat screen put an island where the cursor is.
@MainActor
final class IslandFieldController {
    fileprivate weak var view: IslandTextView?
    /// Inserts at the cursor (or the end) with a little fall and pop. Returns false when the
    /// field isn't on screen.
    @discardableResult
    func insert(_ island: Island, delay: Double = 0) -> Bool {
        guard let view else { return false }
        view.insert(island, dropAfter: delay)
        return true
    }

    /// Islands already in the text drop in one after another.
    func dropAll(_ islands: [Island]) {
        guard let view else { return }
        // Ready to keep typing after the suggestion.
        view.selectedRange = NSRange(location: view.textStorage.length, length: 0)
        for (i, island) in islands.enumerated() {
            view.hide(island)
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.12 + Double(i) * 0.12) { [weak view] in view?.drop(island) }
        }
    }
}

/// The prompt box: a growing text view whose islands sit inline, can be picked up with a
/// long press and set down anywhere else, and whose typed tool names ("Maps") shimmer and
/// turn into islands with a tap.
struct IslandField: UIViewRepresentable {
    @Binding var text: String
    let islands: [Island]
    let placeholder: String
    @Binding var focused: Bool
    var enabled = true
    /// While a tray covers the field it keeps its height, rather than reflowing in the
    /// narrower space the open tray leaves it.
    var frozen = false
    let controller: IslandFieldController
    /// Typed words that can become a tool island, lowercased, with the connector they name.
    var tools: [String: Connector] = [:]
    /// An island the person deleted from the text (backspace or cut).
    var removed: (Island) -> Void = { _ in }
    /// A typed tool name was tapped: the island to put in its place, or nil to leave it.
    var promote: (Connector) -> Island? = { _ in nil }

    static let maxLines: CGFloat = 6

    func makeUIView(context: Context) -> IslandTextView {
        let view = IslandTextView()
        view.delegate = context.coordinator
        view.changed = { [weak coordinator = context.coordinator] in coordinator?.push() }
        view.promote = { [weak coordinator = context.coordinator] connector in coordinator?.parent.promote(connector) }
        view.accessibilityIdentifier = "prompt"
        controller.view = view
        view.tools = tools
        view.islands = Dictionary(islands.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
        view.load(text)
        return view
    }

    func updateUIView(_ view: IslandTextView, context: Context) {
        context.coordinator.parent = self
        controller.view = view
        view.tools = tools
        for island in islands where view.islands[island.id] == nil { view.islands[island.id] = island }
        if view.markerText != text { view.load(text) }
        view.placeholder.text = placeholder
        view.placeholder.isHidden = !view.textStorage.string.isEmpty
        view.isEditable = enabled
        view.isSelectable = enabled
        if focused && !view.isFirstResponder && enabled {
            DispatchQueue.main.async { view.becomeFirstResponder() }
        } else if !focused && view.isFirstResponder {
            DispatchQueue.main.async { view.resignFirstResponder() }
        }
    }

    func sizeThatFits(_ proposal: ProposedViewSize, uiView view: IslandTextView, context: Context) -> CGSize? {
        if frozen, let last = context.coordinator.lastSize { return CGSize(width: proposal.width ?? last.width, height: last.height) }
        let width = proposal.width ?? 240
        let fit = view.sizeThatFits(CGSize(width: width, height: .greatestFiniteMagnitude)).height
        let cap = (IslandType.lineHeight * Self.maxLines + view.textContainerInset.top + view.textContainerInset.bottom).rounded(.up)
        let scrolls = fit > cap
        if view.isScrollEnabled != scrolls { DispatchQueue.main.async { view.isScrollEnabled = scrolls } }
        let size = CGSize(width: width, height: min(fit, cap))
        if !frozen { context.coordinator.lastSize = size }
        return size
    }

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    @MainActor
    final class Coordinator: NSObject, UITextViewDelegate {
        var parent: IslandField
        var lastSize: CGSize?
        init(_ parent: IslandField) { self.parent = parent }

        /// Sends the field's text back to the draft, and reports islands that left it.
        func push() {
            guard let view = parent.controller.view else { return }
            let now = view.markerText
            view.placeholder.isHidden = !view.textStorage.string.isEmpty
            view.relayout()
            guard now != parent.text else { return }
            let before = IslandText.present(in: parent.text, Array(view.islands.values))
            parent.text = now
            for island in before where !now.contains(island.marker) { parent.removed(island) }
            view.invalidateIntrinsicContentSize()
        }

        func textViewDidChange(_ textView: UITextView) {
            (textView as? IslandTextView)?.refreshMentions()
            push()
        }
        func textViewDidBeginEditing(_ textView: UITextView) { if !parent.focused { parent.focused = true } }
        func textViewDidEndEditing(_ textView: UITextView) { if parent.focused { parent.focused = false } }
        func textViewDidChangeSelection(_ textView: UITextView) {
            (textView as? IslandTextView)?.rememberCursor()
        }
    }
}

final class IslandTextView: IslandCanvas, UIGestureRecognizerDelegate {
    var tools: [String: Connector] = [:] { didSet { if oldValue.keys != tools.keys { refreshMentions() } } }
    var changed: () -> Void = {}
    var promote: (Connector) -> Island? = { _ in nil }
    let placeholder = UILabel()
    /// Where the next island goes when the field isn't being edited.
    private var cursor: Int?

    // Dragging an island
    private var press: UILongPressGestureRecognizer!
    private var tap: UITapGestureRecognizer!
    private var lifted: Island?
    private var gapIndex: Int?
    private var ghost: UIImageView?
    private var lastPoint: CGPoint = .zero
    private let dropCaret = UIView()
    private var lastSpaced = -1
    private let pick = UIImpactFeedbackGenerator(style: .medium)
    private let settle = UIImpactFeedbackGenerator(style: .soft)
    private let tick = UISelectionFeedbackGenerator()

    // Typed tool names
    private var mentions: [(range: NSRange, connector: Connector)] = []
    private var shimmers: [UIView] = []

    init() {
        super.init(frame: .zero, textContainer: nil)
        backgroundColor = .clear
        font = IslandType.font
        textColor = UIColor(Ink.primary)
        tintColor = UIColor(Ink.primary)
        typingAttributes = IslandType.attributes()
        textContainerInset = UIEdgeInsets(top: 7, left: 0, bottom: 7, right: 0)
        textContainer.lineFragmentPadding = 0
        isScrollEnabled = false
        clipsToBounds = false
        showsVerticalScrollIndicator = false
        keyboardDismissMode = .none
        setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        setContentHuggingPriority(.defaultLow, for: .horizontal)

        placeholder.font = IslandType.font
        placeholder.textColor = UIColor(Ink.tertiary)
        placeholder.isUserInteractionEnabled = false
        placeholder.translatesAutoresizingMaskIntoConstraints = false
        addSubview(placeholder)
        NSLayoutConstraint.activate([
            placeholder.leadingAnchor.constraint(equalTo: leadingAnchor),
            placeholder.centerYAnchor.constraint(equalTo: topAnchor, constant: 7 + IslandType.lineHeight / 2),
        ])

        press = UILongPressGestureRecognizer(target: self, action: #selector(drag(_:)))
        press.minimumPressDuration = 0.2
        press.delegate = self
        addGestureRecognizer(press)

        tap = UITapGestureRecognizer(target: self, action: #selector(tapped(_:)))
        tap.delegate = self
        addGestureRecognizer(tap)

        registerForTraitChanges([UITraitUserInterfaceStyle.self]) { (view: IslandTextView, _: UITraitCollection) in
            let text = view.markerText, selection = view.selectedRange
            view.load(text)
            view.selectedRange = selection
        }
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    override func layoutSubviews() {
        super.layoutSubviews()
        layoutShimmers()
    }

    /// Pills follow every edit at once, not just the next layout pass.
    func relayout() {
        applySpacing()
        layoutIfNeeded()
        layoutPills()
    }

    /// Opens the lines a touch once a second pill is in, closes them again below two.
    private func applySpacing() {
        guard markedTextRange == nil else { return }
        var count = 0
        textStorage.enumerateAttribute(.attachment, in: NSRange(location: 0, length: textStorage.length)) { value, _, _ in
            if value is IslandAttachment { count += 1 }
        }
        let spacing = IslandType.spacing(forPills: count)
        typingAttributes = IslandType.attributes(spacing: spacing)
        guard textStorage.length > 0,
              (textStorage.attribute(.paragraphStyle, at: 0, effectiveRange: nil) as? NSParagraphStyle)?.lineSpacing != spacing
                || textStorage.length != lastSpaced else { return }
        lastSpaced = textStorage.length
        let keep = selectedRange
        textStorage.addAttribute(.paragraphStyle, value: IslandType.paragraph(spacing: spacing), range: NSRange(location: 0, length: textStorage.length))
        selectedRange = keep
    }

    // MARK: Text ↔ markers

    /// The field's contents with each island written as its marker.
    var markerText: String {
        var out = ""
        let storage = textStorage
        storage.enumerateAttributes(in: NSRange(location: 0, length: storage.length)) { attributes, range, _ in
            if let attachment = attributes[.attachment] as? IslandAttachment {
                out += Island.open + attachment.islandID + Island.close
            } else {
                out += (storage.string as NSString).substring(with: range).replacingOccurrences(of: "\u{FFFC}", with: "")
            }
        }
        return out
    }

    func load(_ text: String) {
        let built = IslandType.build(text, Array(islands.values), traits: traitCollection)
        let keep = selectedRange
        attributedText = built
        typingAttributes = IslandType.attributes()
        selectedRange = NSRange(location: min(keep.location, built.length), length: 0)
        placeholder.isHidden = built.length > 0
        refreshMentions()
        invalidateIntrinsicContentSize()
        relayout()
    }

    private func attachment(for island: Island, look: IslandType.Look = .normal) -> NSAttributedString {
        looks[island.id] = look
        return IslandType.attachment(for: island, traits: traitCollection)
    }

    private var space: NSAttributedString { NSAttributedString(string: " ", attributes: IslandType.attributes()) }

    func rememberCursor() {
        if isFirstResponder { cursor = selectedRange.location }
    }

    private func isBlank(_ c: unichar) -> Bool { c == 32 || c == 10 }

    /// Sets an island down at `index`, spaced from the words around it. Returns where the
    /// island itself ended up.
    @discardableResult
    private func put(_ piece: NSAttributedString, at index: Int, replacing: NSRange? = nil) -> Int {
        let storage = textStorage
        let range = replacing ?? NSRange(location: min(index, storage.length), length: 0)
        let string = storage.string as NSString
        let built = NSMutableAttributedString()
        let lead = range.location > 0 && !isBlank(string.character(at: range.location - 1))
        if lead { built.append(space) }
        built.append(piece)
        let end = range.location + range.length
        if end >= string.length || !isBlank(string.character(at: end)) { built.append(space) }
        storage.replaceCharacters(in: range, with: built)
        return range.location + (lead ? 1 : 0)
    }

    /// Takes the character at `index` out, with one of the spaces around it. Returns how
    /// many characters went before `index` (to shift later positions by).
    private func takeOut(at index: Int) -> (removed: NSRange, piece: NSAttributedString) {
        let storage = textStorage
        let string = storage.string as NSString
        var out = NSRange(location: index, length: 1)
        if out.location + 1 < string.length, string.character(at: out.location + 1) == 32 { out.length += 1 }
        else if out.location > 0, string.character(at: out.location - 1) == 32 { out.location -= 1; out.length += 1 }
        let piece = storage.attributedSubstring(from: NSRange(location: index, length: 1))
        storage.replaceCharacters(in: out, with: "")
        return (out, piece)
    }

    /// Puts an island at the cursor. It drops in from above and pops into place.
    func insert(_ island: Island, dropAfter delay: Double = 0) {
        islands[island.id] = island
        let storage = textStorage
        let at = min(isFirstResponder ? selectedRange.location : (cursor ?? storage.length), storage.length)
        let replace = isFirstResponder ? selectedRange : NSRange(location: at, length: 0)
        let index = put(attachment(for: island, look: .hidden), at: at, replacing: replace)
        selectedRange = NSRange(location: min(storage.length, index + 2), length: 0)
        cursor = selectedRange.location
        typingAttributes = IslandType.attributes()
        changed()
        refreshMentions()
        DispatchQueue.main.asyncAfter(deadline: .now() + delay) { [weak self] in self?.fall(island) }
    }

    private func rect(ofCharacterAt index: Int) -> CGRect? {
        guard let start = position(from: beginningOfDocument, offset: index),
              let end = position(from: start, offset: 1),
              let span = textRange(from: start, to: end) else { return nil }
        layoutIfNeeded()
        return firstRect(for: span)
    }

    private func index(of island: Island) -> Int? {
        var found: Int?
        textStorage.enumerateAttribute(.attachment, in: NSRange(location: 0, length: textStorage.length)) { value, range, stop in
            if (value as? IslandAttachment)?.islandID == island.id { found = range.location; stop.pointee = true }
        }
        return found
    }

    /// Swaps how an island in the text looks (shown, faded, an empty gap).
    private func show(_ island: Island, _ look: IslandType.Look) {
        looks[island.id] = look
        layoutPills()
    }

    /// Where the island's pill is drawn, in window coordinates.
    private func pillFrame(for island: Island) -> CGRect? {
        relayout()
        guard let window, let pill = pills[island.id] else { return nil }
        return pill.convert(pill.bounds, to: window)
    }

    /// Shows the island for real in its place.
    private func reveal(_ island: Island) { show(island, .normal) }

    func hide(_ island: Island) { show(island, .hidden) }
    func drop(_ island: Island) { fall(island) }

    /// The island falls a short way onto the line, squashes a touch on landing and springs
    /// back. It's the pill in the text itself that moves, so it ends exactly in place.
    private func fall(_ island: Island) {
        reveal(island)
        relayout()
        guard let pill = pills[island.id] else { return }
        pill.layer.removeAllAnimations()
        pill.transform = CGAffineTransform(translationX: 0, y: -34).scaledBy(x: 0.82, y: 0.82)
        pill.alpha = 0
        UIView.animate(withDuration: 0.12) { pill.alpha = 1 }
        UIView.animate(withDuration: 0.26, delay: 0, options: [.curveEaseIn]) {
            pill.transform = CGAffineTransform(scaleX: 1.08, y: 0.9) // landing squash
        } completion: { _ in
            self.settle.impactOccurred(intensity: 0.8)
            UIView.animate(withDuration: 0.42, delay: 0, usingSpringWithDamping: 0.42, initialSpringVelocity: 0.6) {
                pill.transform = .identity
            }
        }
    }

    /// A tapped tool name turns into its island where it stands, with a pop.
    private func pop(_ island: Island) {
        reveal(island)
        relayout()
        guard let pill = pills[island.id] else { return }
        pill.transform = CGAffineTransform(scaleX: 0.55, y: 0.55)
        pill.alpha = 0
        pick.impactOccurred(intensity: 0.7)
        UIView.animate(withDuration: 0.5, delay: 0, usingSpringWithDamping: 0.45, initialSpringVelocity: 0.8) {
            pill.transform = .identity
            pill.alpha = 1
        }
    }

    // MARK: Typed tool names

    /// Finds tool names typed as words ("maps", "Reminders") and makes them shimmer in the
    /// tool's colour, ready to tap.
    func refreshMentions() {
        guard markedTextRange == nil else { return }
        let storage = textStorage
        let full = NSRange(location: 0, length: storage.length)
        let string = storage.string
        var found: [(NSRange, Connector)] = []
        for (word, connector) in tools {
            let pattern = "(?<![\\p{L}\\p{N}])" + NSRegularExpression.escapedPattern(for: word) + "(?![\\p{L}\\p{N}])"
            guard let regex = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive]) else { continue }
            for match in regex.matches(in: string, range: full) where !found.contains(where: { NSIntersectionRange($0.0, match.range).length > 0 }) {
                found.append((match.range, connector))
            }
        }
        storage.beginEditing()
        storage.enumerateAttribute(.attachment, in: full) { value, range, _ in
            if value == nil { storage.addAttribute(.foregroundColor, value: UIColor(Ink.primary), range: range) }
        }
        for (range, connector) in found {
            storage.addAttribute(.foregroundColor, value: IslandTint.for(connector: connector.id).ui, range: range)
        }
        storage.endEditing()
        typingAttributes = IslandType.attributes()
        mentions = found.map { ($0.0, $0.1) }
        layoutShimmers()
    }

    private func rects(for range: NSRange) -> [CGRect] {
        guard let start = position(from: beginningOfDocument, offset: range.location),
              let end = position(from: start, offset: range.length),
              let span = textRange(from: start, to: end) else { return [] }
        return selectionRects(for: span).map(\.rect).filter { $0.width > 1 }
    }

    /// A soft band of light sweeps over each tool name, again and again.
    private func layoutShimmers() {
        shimmers.forEach { $0.removeFromSuperview() }
        shimmers = []
        for mention in mentions {
            for rect in rects(for: mention.range) {
                let view = UIView(frame: rect.insetBy(dx: -2, dy: 1))
                view.isUserInteractionEnabled = false
                view.clipsToBounds = true
                let band = CAGradientLayer()
                let paper = UIColor(Ink.fill).resolvedColor(with: traitCollection)
                band.colors = [paper.withAlphaComponent(0).cgColor, paper.withAlphaComponent(0.75).cgColor, paper.withAlphaComponent(0).cgColor]
                band.startPoint = CGPoint(x: 0, y: 0.5)
                band.endPoint = CGPoint(x: 1, y: 0.5)
                let width = max(18, view.bounds.width * 0.5)
                band.frame = CGRect(x: -width, y: 0, width: width, height: view.bounds.height)
                view.layer.addSublayer(band)
                let sweep = CABasicAnimation(keyPath: "position.x")
                sweep.fromValue = -width / 2
                sweep.toValue = view.bounds.width + width / 2
                sweep.duration = 1.1
                let group = CAAnimationGroup()
                group.animations = [sweep]
                group.duration = 1.8
                group.repeatCount = .infinity
                band.add(group, forKey: "shimmer")
                addSubview(view)
                shimmers.append(view)
            }
        }
    }

    private func mention(at point: CGPoint) -> (range: NSRange, connector: Connector)? {
        mentions.first { mention in rects(for: mention.range).contains { $0.insetBy(dx: -6, dy: -8).contains(point) } }
    }

    @objc private func tapped(_ tap: UITapGestureRecognizer) {
        guard let hit = mention(at: tap.location(in: self)), let island = promote(hit.connector) else { return }
        islands[island.id] = island
        let index = put(attachment(for: island, look: .hidden), at: hit.range.location, replacing: hit.range)
        selectedRange = NSRange(location: min(textStorage.length, index + 2), length: 0)
        cursor = selectedRange.location
        refreshMentions()
        changed()
        DispatchQueue.main.async { self.pop(island) }
    }

    // MARK: Drag an island to a new place

    /// The island under a point, if any.
    private func island(at point: CGPoint) -> (index: Int, island: Island, rect: CGRect)? {
        var found: (Int, Island, CGRect)?
        textStorage.enumerateAttribute(.attachment, in: NSRange(location: 0, length: textStorage.length)) { value, range, stop in
            guard let attachment = value as? IslandAttachment, let island = islands[attachment.islandID],
                  let rect = rect(ofCharacterAt: range.location) else { return }
            if rect.insetBy(dx: -6, dy: -8).contains(point) { found = (range.location, island, rect); stop.pointee = true }
        }
        return found
    }

    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldReceive touch: UITouch) -> Bool {
        let point = touch.location(in: self)
        if gestureRecognizer === tap { return mention(at: point) != nil }
        return isEditable && island(at: point) != nil
    }

    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldBeRequiredToFailBy other: UIGestureRecognizer) -> Bool {
        other.view === self && other !== press && other !== tap
    }

    /// Where an island may land near a point: never inside a word.
    private func landing(near point: CGPoint) -> Int? {
        guard let position = closestPosition(to: point) else { return nil }
        let string = textStorage.string as NSString
        var i = offset(from: beginningOfDocument, to: position)
        guard string.length > 0 else { return 0 }
        func wordy(_ k: Int) -> Bool { k >= 0 && k < string.length && !isBlank(string.character(at: k)) && string.character(at: k) != 0xFFFC }
        if wordy(i - 1) && wordy(i) {
            var back = i, ahead = i
            while wordy(back - 1) { back -= 1 }
            while wordy(ahead) { ahead += 1 }
            i = (i - back) <= (ahead - i) ? back : ahead
        }
        return min(max(0, i), string.length)
    }

    @objc private func drag(_ press: UILongPressGestureRecognizer) {
        let point = press.location(in: self)
        switch press.state {
        case .began:
            guard let hit = island(at: point), let window, let start = pillFrame(for: hit.island) else { return }
            lifted = hit.island
            pick.impactOccurred()
            // The island lifts off the line and follows the finger; a gap the same size stays
            // in the text and travels with it, the words moving aside to make room.
            let ghost = UIImageView(image: IslandArt.image(hit.island, traits: traitCollection))
            ghost.frame = start
            ghost.layer.shadowColor = UIColor.black.cgColor
            ghost.layer.shadowOpacity = 0
            ghost.layer.shadowRadius = 12
            ghost.layer.shadowOffset = CGSize(width: 0, height: 10)
            window.addSubview(ghost)
            self.ghost = ghost
            UIView.animate(withDuration: 0.4, delay: 0, usingSpringWithDamping: 0.55, initialSpringVelocity: 0.4) {
                ghost.transform = CGAffineTransform(translationX: 0, y: -16).scaledBy(x: 1.16, y: 1.16).rotated(by: -0.05)
                ghost.layer.shadowOpacity = 0.22
            }
            // Where it will land shows as its own faint shadow, with the cursor just before it.
            show(hit.island, .faded)
            gapIndex = hit.index
            lastPoint = point
            placeCaret()
        case .changed:
            follow(point)
        case .ended:
            drop()
        default:
            drop()
        }
    }

    private func follow(_ point: CGPoint) {
        guard let ghost, let window, let lifted else { return }
        let dx = point.x - lastPoint.x
        lastPoint = point
        let inWindow = convert(point, to: window)
        // It leans into the direction it's moving, like something carried.
        let lean = max(-0.14, min(0.14, dx / 60))
        UIView.animate(withDuration: 0.18, delay: 0, options: [.beginFromCurrentState, .allowUserInteraction, .curveEaseOut]) {
            ghost.center = CGPoint(x: inWindow.x, y: inWindow.y - 30)
            ghost.transform = CGAffineTransform(scaleX: 1.16, y: 1.16).rotated(by: -0.03 + lean)
        }
        guard let gap = gapIndex, var target = landing(near: CGPoint(x: point.x, y: point.y - 30)) else { return }
        if target == gap || target == gap + 1 { return }
        // Move the gap: the words shift over, blending from where they were.
        let before = snapshotView(afterScreenUpdates: false)
        let out = takeOut(at: gap)
        if target > out.removed.location { target = max(out.removed.location, target - out.removed.length) }
        gapIndex = put(out.piece, at: target)
        if let before {
            before.frame = CGRect(origin: CGPoint(x: 0, y: contentOffset.y), size: before.bounds.size)
            before.isUserInteractionEnabled = false
            addSubview(before)
            UIView.animate(withDuration: 0.2, delay: 0, options: [.curveEaseOut]) { before.alpha = 0 } completion: { _ in before.removeFromSuperview() }
        }
        tick.selectionChanged()
        changed()
        placeCaret()
    }

    /// The cursor follows the landing spot while an island is carried: the real one when
    /// typing, otherwise a drawn one.
    private func placeCaret() {
        guard let gap = gapIndex, let lifted else { dropCaret.isHidden = true; return }
        if isFirstResponder {
            selectedRange = NSRange(location: gap, length: 0)
            dropCaret.isHidden = true
            return
        }
        relayout()
        guard let pill = pills[lifted.id] else { return }
        let height = IslandType.font.lineHeight
        let frame = CGRect(x: pill.frame.minX + IslandArt.margin.left - 4, y: pill.frame.midY - height / 2, width: 2, height: height)
        if dropCaret.superview == nil {
            dropCaret.backgroundColor = UIColor(Ink.primary)
            dropCaret.layer.cornerRadius = 1
            addSubview(dropCaret)
        }
        bringSubviewToFront(dropCaret)
        if dropCaret.isHidden {
            dropCaret.frame = frame
            dropCaret.isHidden = false
        } else {
            UIView.animate(withDuration: 0.12, delay: 0, options: [.beginFromCurrentState, .curveEaseOut]) { self.dropCaret.frame = frame }
        }
    }

    /// Released: the island drops onto its shadow, lands with a little squash and settles.
    /// The carried copy hands over to the pill in the text, which finishes the move, so it
    /// ends exactly in place.
    private func drop() {
        guard let lifted, let ghost, let gap = gapIndex else { cancelDrag(); return }
        self.lifted = nil
        self.ghost = nil
        gapIndex = nil
        dropCaret.isHidden = true
        selectedRange = NSRange(location: min(textStorage.length, gap + 2), length: 0)
        cursor = selectedRange.location
        reveal(lifted)
        relayout()
        changed()
        guard let pill = pills[lifted.id], let window else { ghost.removeFromSuperview(); return }
        // Start the pill where the carried copy is now: same place, size and tilt.
        let carried = window.convert(ghost.center, to: self)
        let angle = atan2(ghost.transform.b, ghost.transform.a)
        let scale = sqrt(ghost.transform.a * ghost.transform.a + ghost.transform.b * ghost.transform.b)
        ghost.removeFromSuperview()
        pill.layer.removeAllAnimations()
        pill.transform = CGAffineTransform(translationX: carried.x - pill.center.x, y: carried.y - pill.center.y).scaledBy(x: scale, y: scale).rotated(by: angle)
        pill.alpha = 1
        bringSubviewToFront(pill)
        UIView.animate(withDuration: 0.22, delay: 0, options: [.curveEaseIn]) {
            pill.transform = CGAffineTransform(scaleX: 1.07, y: 0.9)
        } completion: { _ in
            self.settle.impactOccurred()
            UIView.animate(withDuration: 0.4, delay: 0, usingSpringWithDamping: 0.42, initialSpringVelocity: 0.5) {
                pill.transform = .identity
            }
        }
    }

    private func cancelDrag() {
        dropCaret.isHidden = true
        if let lifted { reveal(lifted) }
        ghost?.removeFromSuperview()
        ghost = nil
        lifted = nil
        gapIndex = nil
    }
}
