import SwiftUI
import UIKit

// MARK: - Model

/// A tool, connector or attachment set into the prompt itself: a small coloured capsule that
/// sits between words, so you can say *where* a source matters ("find a café ⟦Maps⟧ that
/// doesn't clash with ⟦Calendar⟧"). In text it's stored as a marker, `⟦id⟧`.
struct Island: Identifiable, Codable, Hashable, Sendable {
    enum Kind: String, Codable, Sendable { case photo, file, connector }
    var id: String = Island.newID()
    var kind: Kind
    /// The connector id, the attached file's id, or the photo's image name.
    var ref: String
    var title: String
    var icon: String
    var tint: IslandTint
    /// The attached file's own name; its island just says "File".
    var name: String? = nil

    static let open = "⟦", close = "⟧"
    var marker: String { Island.open + id + Island.close }
    static func newID() -> String { String(UUID().uuidString.prefix(8)) }

    static func connector(_ connector: Connector) -> Island {
        Island(kind: .connector, ref: connector.id, title: connector.name, icon: connector.islandIcon, tint: .for(connector: connector.id))
    }
    static func file(_ file: AttachedFile) -> Island {
        Island(kind: .file, ref: file.id.uuidString, title: "File", icon: "doc.text.fill", tint: .amber, name: file.name)
    }
    static func photo(_ name: String) -> Island {
        Island(kind: .photo, ref: name, title: "Photo", icon: "photo.fill", tint: .pink)
    }

    /// How the model reads it in your prompt.
    var spoken: String {
        switch kind {
        case .photo: "[the attached photo]"
        case .file: "[the attached file “\(name ?? title)”]"
        case .connector: "[\(title)]"
        }
    }
}

/// The splash of colour each island brings to the monochrome page. Each fill borrows from
/// the app icon it stands for (Maps' greens, Safari's blues, Calendar's red, the Photos
/// petals), drawn top to bottom like an icon, with no outline.
enum IslandTint: String, Codable, Sendable, CaseIterable {
    case green, blue, red, orange, purple, teal, amber, pink, indigo, slate

    /// Gradient stops, top to bottom.
    var stops: [UInt32] {
        switch self {
        case .green: [0x7BDD72, 0x34B556]        // Maps
        case .blue: [0x5BBDFF, 0x1A73F0]         // Safari
        case .red: [0xFF6F61, 0xE5302A]          // Calendar
        case .orange: [0xFFB24A, 0xFF7A1C]       // Reminders
        case .purple: [0xBE96FF, 0x7D4AEA]       // Clipboard
        case .teal: [0x5ADBCB, 0x14968F]         // MCP servers
        case .amber: [0xFFE07A, 0xF6C12E]        // Files, Notes-yellow
        case .pink: [0xFFB86B, 0xFF5E8E, 0xA86DFF] // Photos petals
        case .indigo: [0x938CFF, 0x4F46E5]       // Translate
        case .slate: [0xF7F7F7, 0xDCDCDC]        // Wikipedia, paper-white
        }
    }

    /// Glyph and title colour on the fill.
    var inkRGB: UInt32 {
        switch self {
        case .amber: 0x4A3700
        case .slate: 0x111111
        default: 0xFFFFFF
        }
    }

    var ink: Color { Color(uiColor: UIColor(rgb: inkRGB)) }
    /// The deepest colour of the fill (a drop caret, a dot).
    var ui: UIColor { UIColor(rgb: stops.last!) }
    var color: Color { Color(uiColor: ui) }
    var gradient: LinearGradient {
        LinearGradient(colors: stops.map { Color(uiColor: UIColor(rgb: $0)) }, startPoint: .top, endPoint: .bottom)
    }

    static func `for`(connector id: String) -> IslandTint {
        switch id {
        case Connector.maps.id: .green
        case Connector.web.id: .blue
        case Connector.calendar.id: .red
        case Connector.reminders.id: .orange
        case Connector.clipboard.id: .purple
        case Connector.wikipedia.id: .slate
        default: .teal
        }
    }
}

extension UIColor {
    convenience init(rgb: UInt32) {
        self.init(red: CGFloat((rgb >> 16) & 0xFF) / 255, green: CGFloat((rgb >> 8) & 0xFF) / 255, blue: CGFloat(rgb & 0xFF) / 255, alpha: 1)
    }
}

extension Connector {
    /// Filled glyphs read better inside the island's little disc.
    var islandIcon: String {
        switch id {
        case Connector.maps.id: "map.fill"
        case Connector.web.id: "magnifyingglass"
        case Connector.calendar.id: "calendar"
        case Connector.reminders.id: "checklist"
        case Connector.clipboard.id: "doc.on.clipboard.fill"
        case Connector.wikipedia.id: "globe"
        default: "server.rack"
        }
    }
}

// MARK: - Text with markers

enum IslandText {
    enum Piece { case text(String), island(Island) }

    /// Splits marker text into words and islands. Markers for unknown islands are dropped.
    static func pieces(_ text: String, _ islands: [Island]) -> [Piece] {
        guard text.contains(Island.open) else { return text.isEmpty ? [] : [.text(text)] }
        let byID = Dictionary(islands.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
        var pieces: [Piece] = []
        var rest = Substring(text)
        while let start = rest.range(of: Island.open) {
            if start.lowerBound > rest.startIndex { pieces.append(.text(String(rest[..<start.lowerBound]))) }
            guard let end = rest.range(of: Island.close, range: start.upperBound..<rest.endIndex) else {
                pieces.append(.text(String(rest[start.lowerBound...]))); rest = ""; break
            }
            if let island = byID[String(rest[start.upperBound..<end.lowerBound])] { pieces.append(.island(island)) }
            rest = rest[end.upperBound...]
        }
        if !rest.isEmpty { pieces.append(.text(String(rest))) }
        return pieces
    }

    /// The prompt as the model reads it: "find a café [Maps] near me".
    static func spoken(_ text: String, _ islands: [Island]) -> String {
        join(text, islands) { $0.spoken }
    }

    /// The prompt as a person reads it (titles, copy, edit): "find a café Maps near me".
    static func titled(_ text: String, _ islands: [Island]) -> String {
        join(text, islands) { $0.name ?? $0.title }
    }

    /// Words only, for spotting what a prompt asks (tools ignore the islands themselves).
    static func stripped(_ text: String) -> String {
        guard text.contains(Island.open) else { return text }
        return text.replacingOccurrences(of: "⟦[^⟧]*⟧", with: " ", options: .regularExpression)
            .replacingOccurrences(of: " {2,}", with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// The islands whose markers are still in the text.
    static func present(in text: String, _ islands: [Island]) -> [Island] {
        islands.filter { text.contains($0.marker) }
    }

    private static func join(_ text: String, _ islands: [Island], _ word: (Island) -> String) -> String {
        pieces(text, islands).map { piece in
            switch piece { case .text(let s): s; case .island(let island): word(island) }
        }.joined().replacingOccurrences(of: " {2,}", with: " ", options: .regularExpression)
    }

}

