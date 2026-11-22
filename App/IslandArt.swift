import SwiftUI
import UIKit

// MARK: - Drawing an island

/// Draws an island as an image, so the same capsule sits in the text field (as a text
/// attachment), in sent messages (inside a `Text`) and in the chips above the prompt.
///
/// Each island is a tiny scene of the app it stands for, painted edge to edge under its
/// name: Maps is a scrap of map with a park, roads and the sea; Calendar a ruled page with
/// today's date; Reminders a list with its coloured dots; Web a globe; Photos a sunset over
/// hills; Files a notepad; Wikipedia a page of text; MCP servers a circuit board.
@MainActor
enum IslandArt {
    static let height: CGFloat = 26
    /// Room around the capsule for the shadow it casts (the image is this much larger).
    static let margin = UIEdgeInsets(top: 1, left: 2, bottom: 4, right: 2)
    private static var cache: [String: UIImage] = [:]

    /// Vertical offset that centres the capsule (not its shadow) on a line of the given font size.
    static func baseline(for size: CGFloat) -> CGFloat {
        let cap = (UIFont(name: "Geist-Regular", size: size) ?? .systemFont(ofSize: size)).capHeight
        return ((cap - height) / 2).rounded() - margin.bottom
    }

    /// How the name and glyph sit on a scene.
    private struct Style {
        var ink: UInt32
        var glyphInk: UInt32? = nil
        /// A soft glow behind the words, so they read over busy art.
        var halo: (UInt32, CGFloat)? = nil
        var serif = false
        /// Room left at the end for part of the scene (a date, a few dots).
        var trailing: CGFloat = 0
        var glyph: String? = nil
        /// Photos lead with a little instant print of the picture instead of a glyph.
        var polaroid = false
    }

    /// Where attached photos live, so a photo island can show the picture itself.
    static var photos: URL?
    private static var thumbnails: [String: UIImage] = [:]

    private static func thumbnail(_ name: String) -> UIImage? {
        if let hit = thumbnails[name] { return hit }
        guard let url = photos?.appendingPathComponent(name), let image = UIImage(contentsOfFile: url.path) else { return nil }
        // Square crop, small: it's drawn about 11 pt across.
        let side = min(image.size.width, image.size.height)
        let crop = CGRect(x: (image.size.width - side) / 2, y: (image.size.height - side) / 2, width: side, height: side)
        let thumb = UIGraphicsImageRenderer(size: CGSize(width: 40, height: 40)).image { _ in
            image.draw(in: CGRect(x: -crop.minX * 40 / side, y: -crop.minY * 40 / side, width: image.size.width * 40 / side, height: image.size.height * 40 / side))
        }
        thumbnails[name] = thumb
        return thumb
    }

    private static func style(_ tint: IslandTint) -> Style {
        switch tint {
        case .green: Style(ink: 0x1C2B20, glyphInk: 0xEA4335, halo: (0xF4F2EA, 3), glyph: "mappin")
        case .red: Style(ink: 0xD92D23, trailing: 20)
        case .orange: Style(ink: 0x1A1A1A, glyphInk: 0xFF9500, trailing: 22)
        case .blue: Style(ink: 0xFFFFFF, halo: (0x0B4FB8, 3), glyph: "safari")
        case .purple: Style(ink: 0x5A2FD0, trailing: 12)
        case .slate: Style(ink: 0x111111, halo: (0xFAFAF7, 3), serif: true)
        case .amber: Style(ink: 0x3A2E00, glyphInk: 0xD9A000)
        case .pink: Style(ink: 0x2A2724, polaroid: true)
        case .teal: Style(ink: 0xE6FFFC, glyphInk: 0x5BE0D3, halo: (0x0B2E2C, 3))
        case .indigo: Style(ink: 0xFFFFFF, halo: (0x2F28A8, 3))
        }
    }

    static func image(_ island: Island, traits: UITraitCollection, faded: Bool = false) -> UIImage {
        let key = "\(island.icon)|\(island.title)|\(island.tint.rawValue)|\(island.kind == .photo ? island.ref : "")|\(faded)|\(traits.displayScale)"
        if let hit = cache[key] { return hit }
        let look = style(island.tint)
        let ink = UIColor(rgb: look.ink)
        let font = look.serif
            ? (UIFont(name: "InstrumentSerif-Regular", size: 16) ?? .systemFont(ofSize: 15))
            : (UIFont(name: "Geist-SemiBold", size: 13) ?? .systemFont(ofSize: 13, weight: .semibold))
        var attributes: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: ink, .kern: look.serif ? 0 : -0.15]
        if let halo = look.halo {
            let shadow = NSShadow()
            shadow.shadowColor = UIColor(rgb: halo.0).withAlphaComponent(0.9)
            shadow.shadowBlurRadius = halo.1
            shadow.shadowOffset = .zero
            attributes[.shadow] = shadow
        }
        let title = island.title.count > 24 ? String(island.title.prefix(23)) + "…" : island.title
        let text = NSAttributedString(string: title, attributes: attributes)
        let glyph: UIImage? = {
            if look.serif || look.polaroid { return nil }
            return UIImage(systemName: look.glyph ?? island.icon, withConfiguration: UIImage.SymbolConfiguration(pointSize: 11, weight: .bold))?
                .withTintColor(UIColor(rgb: look.glyphInk ?? look.ink), renderingMode: .alwaysOriginal)
        }()
        // Wikipedia signs with a serif W instead of a glyph.
        let mark = look.serif ? NSAttributedString(string: "W", attributes: attributes.merging([.font: UIFont(name: "InstrumentSerif-Regular", size: 17) ?? font]) { $1 }) : nil
        let print = CGSize(width: 15, height: 17.5)
        let glyphSize = look.polaroid ? CGSize(width: print.width + 2, height: print.height) : glyph?.size ?? mark?.size() ?? .zero
        let textSize = text.size()
        let size = CGSize(width: (10 + glyphSize.width + 5 + ceil(textSize.width) + 11 + look.trailing).rounded(.up), height: height)
        let canvas = CGSize(width: size.width + margin.left + margin.right, height: size.height + margin.top + margin.bottom)
        let format = UIGraphicsImageRendererFormat(for: traits)
        let image = UIGraphicsImageRenderer(size: canvas, format: format).image { context in
            let cg = context.cgContext
            if faded { cg.setAlpha(0.3) }
            cg.beginTransparencyLayer(auxiliaryInfo: nil)
            let rect = CGRect(origin: .zero, size: size)
            let capsule = UIBezierPath(roundedRect: rect, cornerRadius: height / 2)
            cg.translateBy(x: margin.left, y: margin.top)
            // Raised off the page: a tight contact shadow and a softer one further down.
            cg.saveGState()
            cg.setShadow(offset: CGSize(width: 0, height: 1.5), blur: 3, color: UIColor.black.withAlphaComponent(0.22).cgColor)
            UIColor(rgb: island.tint.stops.last!).setFill(); capsule.fill()
            cg.restoreGState()
            cg.saveGState()
            capsule.addClip()
            paint(island.tint, in: rect, cg)
            relief(rect, cg)
            let glyphRect = CGRect(x: 10, y: (height - glyphSize.height) / 2, width: glyphSize.width, height: glyphSize.height)
            if let glyph {
                if let halo = look.halo { cg.setShadow(offset: .zero, blur: halo.1, color: UIColor(rgb: halo.0).withAlphaComponent(0.9).cgColor) }
                glyph.draw(in: glyphRect)
                cg.setShadow(offset: .zero, blur: 0, color: nil)
            }
            mark?.draw(at: CGPoint(x: glyphRect.minX, y: ((height - glyphSize.height) / 2).rounded()))
            if look.polaroid { polaroid(island.kind == .photo ? thumbnail(island.ref) : nil, size: print, at: CGPoint(x: glyphRect.midX, y: height / 2), cg) }
            text.draw(at: CGPoint(x: glyphRect.maxX + 5, y: ((height - textSize.height) / 2).rounded()))
            cg.restoreGState()
            cg.endTransparencyLayer()
        }
        cache[key] = image
        return image
    }

    /// The light on a rounded surface: a gloss across the top, shade gathering underneath,
    /// a lit rim along the upper edge and a darker one along the lower.
    private static func relief(_ r: CGRect, _ cg: CGContext) {
        let h = r.height
        let space = CGColorSpaceCreateDeviceRGB()
        func band(_ colors: [UIColor], _ from: CGFloat, _ to: CGFloat) {
            guard let g = CGGradient(colorsSpace: space, colors: colors.map(\.cgColor) as CFArray, locations: nil) else { return }
            cg.drawLinearGradient(g, start: CGPoint(x: 0, y: from), end: CGPoint(x: 0, y: to), options: [])
        }
        cg.saveGState()
        cg.clip(to: CGRect(x: 0, y: 0, width: r.width, height: h * 0.55))
        band([UIColor.white.withAlphaComponent(0.42), UIColor.white.withAlphaComponent(0.06)], 0, h * 0.55)
        cg.restoreGState()
        cg.saveGState()
        cg.clip(to: CGRect(x: 0, y: h * 0.5, width: r.width, height: h * 0.5))
        band([UIColor.black.withAlphaComponent(0), UIColor.black.withAlphaComponent(0.16)], h * 0.5, h)
        cg.restoreGState()
        // Rims, drawn inside the edge: light above, shade below.
        let inner = UIBezierPath(roundedRect: r.insetBy(dx: 0.5, dy: 0.5), cornerRadius: (h - 1) / 2)
        inner.lineWidth = 1
        cg.saveGState()
        cg.clip(to: CGRect(x: 0, y: 0, width: r.width, height: h * 0.5))
        UIColor.white.withAlphaComponent(0.6).setStroke(); inner.stroke()
        cg.restoreGState()
        cg.saveGState()
        cg.clip(to: CGRect(x: 0, y: h * 0.5, width: r.width, height: h * 0.5))
        UIColor.black.withAlphaComponent(0.12).setStroke(); inner.stroke()
        cg.restoreGState()
    }

    // MARK: Scenes

    private static func paint(_ tint: IslandTint, in r: CGRect, _ cg: CGContext) {
        switch tint {
        case .green: map(r, cg)
        case .red: calendar(r, cg)
        case .orange: reminders(r, cg)
        case .blue: globe(r, cg)
        case .purple: clipboard(r, cg)
        case .slate: page(r, cg)
        case .amber: notepad(r, cg)
        case .pink: paper(r, cg)
        case .teal: circuit(r, cg)
        case .indigo: letters(r, cg)
        }
    }

    private static func fill(_ rgb: UInt32, _ alpha: CGFloat = 1) { UIColor(rgb: rgb).withAlphaComponent(alpha).setFill() }
    private static func stroke(_ rgb: UInt32, _ alpha: CGFloat = 1) { UIColor(rgb: rgb).withAlphaComponent(alpha).setStroke() }

    private static func gradient(_ stops: [UInt32], _ r: CGRect, _ cg: CGContext, from: CGPoint? = nil, to: CGPoint? = nil) {
        let colors = stops.map { UIColor(rgb: $0).cgColor } as CFArray
        guard let g = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: colors, locations: nil) else { return }
        cg.saveGState()
        cg.clip(to: r)
        cg.drawLinearGradient(g, start: from ?? CGPoint(x: 0, y: r.minY), end: to ?? CGPoint(x: 0, y: r.maxY), options: [.drawsBeforeStartLocation, .drawsAfterEndLocation])
        cg.restoreGState()
    }

    /// A scrap of street map: land, a park, the sea along the right, streets and a highway.
    private static func map(_ r: CGRect, _ cg: CGContext) {
        let w = r.width, h = r.height
        fill(0xF2EFE6); UIRectFill(r)
        fill(0xCDE7B4); UIBezierPath(ovalIn: CGRect(x: w * 0.04, y: h * 0.5, width: w * 0.32, height: h * 0.95)).fill()
        fill(0xD5ECC2); UIBezierPath(ovalIn: CGRect(x: w * 0.5, y: -h * 0.55, width: w * 0.2, height: h * 0.9)).fill()
        fill(0xE9E3D3); UIBezierPath(roundedRect: CGRect(x: w * 0.46, y: h * 0.58, width: w * 0.1, height: h * 0.3), cornerRadius: 1.5).fill()
        // The sea, with a softer shallow band along the coast.
        let coast = UIBezierPath()
        coast.move(to: CGPoint(x: w * 0.74, y: -1))
        coast.addCurve(to: CGPoint(x: w * 0.79, y: h + 1), controlPoint1: CGPoint(x: w * 0.84, y: h * 0.35), controlPoint2: CGPoint(x: w * 0.7, y: h * 0.7))
        coast.addLine(to: CGPoint(x: w + 1, y: h + 1)); coast.addLine(to: CGPoint(x: w + 1, y: -1)); coast.close()
        fill(0xBFE0F7); coast.fill()
        cg.saveGState(); cg.translateBy(x: w * 0.035, y: 0)
        fill(0xA7D3F3); coast.fill()
        cg.restoreGState()
        // City blocks and the small side streets between them.
        fill(0xE8E3D6)
        for (x, y, bw, bh) in [(0.08, 0.12, 0.07, 0.22), (0.2, 0.08, 0.06, 0.18), (0.28, 0.42, 0.05, 0.16), (0.58, 0.5, 0.06, 0.2), (0.62, 0.1, 0.05, 0.14), (0.12, 0.42, 0.06, 0.12)] as [(CGFloat, CGFloat, CGFloat, CGFloat)] {
            UIBezierPath(roundedRect: CGRect(x: w * x, y: h * y, width: w * bw, height: h * bh), cornerRadius: 1).fill()
        }
        stroke(0xFFFFFF); let lanes = UIBezierPath(); lanes.lineWidth = 0.9
        for (x1, y1, x2, y2) in [(0.0, 0.36, 0.7, 0.44), (0.05, -0.1, 0.2, 1.1), (0.52, -0.1, 0.6, 1.1), (0.25, 1.0, 0.68, 0.62), (0.27, -0.1, 0.3, 0.5)] as [(CGFloat, CGFloat, CGFloat, CGFloat)] {
            lanes.move(to: CGPoint(x: w * x1, y: h * y1)); lanes.addLine(to: CGPoint(x: w * x2, y: h * y2))
        }
        lanes.stroke()
        // Streets: white with a faint casing.
        func road(_ path: UIBezierPath, _ width: CGFloat, _ color: UInt32, _ casing: UInt32) {
            path.lineCapStyle = .round
            stroke(casing); path.lineWidth = width + 1.2; path.stroke()
            stroke(color); path.lineWidth = width; path.stroke()
        }
        let street = UIBezierPath()
        street.move(to: CGPoint(x: -2, y: h * 0.82))
        street.addCurve(to: CGPoint(x: w * 0.76, y: h * 0.18), controlPoint1: CGPoint(x: w * 0.25, y: h * 0.62), controlPoint2: CGPoint(x: w * 0.5, y: h * 0.2))
        road(street, 2.2, 0xFFFFFF, 0xE0DACB)
        let cross = UIBezierPath()
        cross.move(to: CGPoint(x: w * 0.36, y: -2)); cross.addLine(to: CGPoint(x: w * 0.43, y: h + 2))
        road(cross, 1.8, 0xFFFFFF, 0xE0DACB)
        let highway = UIBezierPath()
        highway.move(to: CGPoint(x: w * 0.16, y: -2))
        highway.addCurve(to: CGPoint(x: w * 0.7, y: h + 2), controlPoint1: CGPoint(x: w * 0.3, y: h * 0.6), controlPoint2: CGPoint(x: w * 0.55, y: h * 0.75))
        road(highway, 2.4, 0xFAD877, 0xE9BE57)
    }

    /// A ruled page with a red top edge and today's date fading off the end.
    private static func calendar(_ r: CGRect, _ cg: CGContext) {
        let w = r.width, h = r.height
        gradient([0xFFFFFF, 0xFBF4F3], r, cg)
        stroke(0xF3E1DF); let grid = UIBezierPath(); grid.lineWidth = 0.6
        var x: CGFloat = 8
        while x < w { grid.move(to: CGPoint(x: x, y: 5)); grid.addLine(to: CGPoint(x: x, y: h)); x += 9 }
        for y in [h * 0.52, h * 0.8] { grid.move(to: CGPoint(x: 0, y: y)); grid.addLine(to: CGPoint(x: w, y: y)) }
        grid.stroke()
        gradient([0xFF6A5E, 0xE5302A], CGRect(x: 0, y: 0, width: w, height: 4.5), cg)
        let day = NSAttributedString(string: "\(Calendar.current.component(.day, from: .now))", attributes: [
            .font: UIFont(name: "InstrumentSerif-Regular", size: 24) ?? .systemFont(ofSize: 22),
            .foregroundColor: UIColor(rgb: 0xE5302A).withAlphaComponent(0.22),
        ])
        let size = day.size()
        day.draw(at: CGPoint(x: w - size.width - 7, y: (h - size.height) / 2 + 2))
    }

    /// A list: faint rules and the three coloured dots of a Reminders list.
    private static func reminders(_ r: CGRect, _ cg: CGContext) {
        let w = r.width, h = r.height
        gradient([0xFFFFFF, 0xF6F6F8], r, cg)
        stroke(0xEAEAEE); let rules = UIBezierPath(); rules.lineWidth = 0.7
        for y in [h * 0.3, h * 0.7] { rules.move(to: CGPoint(x: 0, y: y)); rules.addLine(to: CGPoint(x: w, y: y)) }
        rules.stroke()
        for (i, color) in [UInt32(0x3478F6), 0xFF3B30, 0xFF9500].enumerated() {
            let cx = w - 25 + CGFloat(i) * 6.5
            fill(color); UIBezierPath(ovalIn: CGRect(x: cx - 2.6, y: h / 2 - 2.6, width: 5.2, height: 5.2)).fill()
        }
    }

    /// Safari blue with a globe's meridians showing through.
    private static func globe(_ r: CGRect, _ cg: CGContext) {
        let w = r.width, h = r.height
        gradient([0x63C3FF, 0x1A73F0], r, cg)
        let center = CGPoint(x: w - 14, y: h * 0.55), radius: CGFloat = 19
        stroke(0xFFFFFF, 0.28)
        let lines = UIBezierPath(); lines.lineWidth = 0.8
        lines.append(UIBezierPath(arcCenter: center, radius: radius, startAngle: 0, endAngle: .pi * 2, clockwise: true))
        for k in [0.35, 0.7] as [CGFloat] { lines.append(UIBezierPath(ovalIn: CGRect(x: center.x - radius * k, y: center.y - radius, width: radius * 2 * k, height: radius * 2))) }
        for dy in [-0.5, 0, 0.5] as [CGFloat] {
            let y = center.y + radius * dy, half = sqrt(max(0, radius * radius - (radius * dy) * (radius * dy)))
            lines.move(to: CGPoint(x: center.x - half, y: y)); lines.addLine(to: CGPoint(x: center.x + half, y: y))
        }
        lines.stroke()
        // A little compass sparkle across the left.
        stroke(0xFFFFFF, 0.14); let ticks = UIBezierPath(); ticks.lineWidth = 0.7
        for i in 0..<14 { let x = 4 + CGFloat(i) * 7; ticks.move(to: CGPoint(x: x, y: h - 3)); ticks.addLine(to: CGPoint(x: x, y: h - (i % 2 == 0 ? 6 : 4.5))) }
        ticks.stroke()
    }

    /// Lavender with two copied sheets stacked at the end.
    private static func clipboard(_ r: CGRect, _ cg: CGContext) {
        let w = r.width, h = r.height
        gradient([0xF4EEFF, 0xE7DDFF], r, cg)
        for (i, dx) in [CGFloat(22), 14].enumerated() {
            let sheet = UIBezierPath(roundedRect: CGRect(x: w - dx, y: 4 + CGFloat(i) * 4, width: 16, height: 22), cornerRadius: 3)
            fill(0xFFFFFF, 0.9); sheet.fill()
            stroke(0xC9B6FF, 0.9); sheet.lineWidth = 0.7; sheet.stroke()
        }
        stroke(0xD8CBFF); let lines = UIBezierPath(); lines.lineWidth = 0.8
        for y in [13.0, 17.0] as [CGFloat] { lines.move(to: CGPoint(x: w - 11, y: y)); lines.addLine(to: CGPoint(x: w - 3, y: y)) }
        lines.stroke()
    }

    /// An encyclopedia page: off-white with faint lines of text.
    private static func page(_ r: CGRect, _ cg: CGContext) {
        let w = r.width
        fill(0xFAFAF7); UIRectFill(r)
        fill(0xE9E9E3)
        let widths: [CGFloat] = [0.5, 0.3, 0.62, 0.22, 0.4, 0.55, 0.18, 0.35]
        var x: CGFloat = 4
        for (row, y) in [4.0, 9.5, 15.0, 20.5].enumerated() {
            x = row % 2 == 0 ? 4 : 12
            for (i, part) in widths.enumerated() where (i + row) % 3 != 0 {
                let len = w * part * 0.35
                UIBezierPath(roundedRect: CGRect(x: x, y: y, width: len, height: 2), cornerRadius: 1).fill()
                x += len + 4
            }
        }
    }

    /// A notepad: Notes-yellow top, ruled paper below.
    private static func notepad(_ r: CGRect, _ cg: CGContext) {
        let w = r.width, h = r.height
        fill(0xFFFDF4); UIRectFill(r)
        gradient([0xFFD95A, 0xF6C12E], CGRect(x: 0, y: 0, width: w, height: 6), cg, to: CGPoint(x: 0, y: 6))
        fill(0xFFFDF4); UIRectFill(CGRect(x: 0, y: 6, width: w, height: h - 6))
        stroke(0xF0E6C4); let rules = UIBezierPath(); rules.lineWidth = 0.7
        for y in [12.5, 18.5, 24.5] as [CGFloat] { rules.move(to: CGPoint(x: 0, y: y)); rules.addLine(to: CGPoint(x: w, y: y)) }
        rules.stroke()
    }

    /// Warm photo paper, faintly grained, for the photo island.
    private static func paper(_ r: CGRect, _ cg: CGContext) {
        gradient([0xFFFDF8, 0xF1ECE2], r, cg)
        fill(0xE6DFD2, 0.55)
        for i in 0..<Int(r.width / 5) {
            let x = CGFloat(i) * 5 + CGFloat((i * 7) % 3), y = CGFloat((i * 11) % Int(max(1, r.height - 2)))
            UIBezierPath(ovalIn: CGRect(x: x, y: y, width: 0.9, height: 0.9)).fill()
        }
    }

    /// A tiny instant print, tilted: a white frame, deeper at the bottom, around the picture.
    private static func polaroid(_ photo: UIImage?, size: CGSize, at centre: CGPoint, _ cg: CGContext) {
        cg.saveGState()
        cg.translateBy(x: centre.x, y: centre.y)
        cg.rotate(by: -0.14)
        let frame = CGRect(x: -size.width / 2, y: -size.height / 2, width: size.width, height: size.height)
        cg.setShadow(offset: CGSize(width: 0, height: 0.8), blur: 1.6, color: UIColor.black.withAlphaComponent(0.3).cgColor)
        fill(0xFFFFFF); UIBezierPath(roundedRect: frame, cornerRadius: 1.2).fill()
        cg.setShadow(offset: .zero, blur: 0, color: nil)
        let window = CGRect(x: frame.minX + 1.6, y: frame.minY + 1.6, width: size.width - 3.2, height: size.width - 3.2)
        cg.saveGState()
        UIBezierPath(rect: window).addClip()
        if let photo {
            photo.draw(in: window)
        } else {
            cg.translateBy(x: window.minX, y: window.minY)
            sunset(CGRect(origin: .zero, size: window.size), cg)
        }
        cg.restoreGState()
        cg.restoreGState()
    }

    /// A photo of a sunset: warm sky, low sun, hills in the Photos petals' purples.
    private static func sunset(_ r: CGRect, _ cg: CGContext) {
        let w = r.width, h = r.height
        gradient([0xFFC685, 0xFF8EA0], r, cg)
        let sun = min(11, w * 0.32)
        fill(0xFFF1B8, 0.95); UIBezierPath(ovalIn: CGRect(x: w - sun - min(11, w * 0.18), y: h * 0.2, width: sun, height: sun)).fill()
        let back = UIBezierPath()
        back.move(to: CGPoint(x: -1, y: h * 0.7))
        back.addCurve(to: CGPoint(x: w + 1, y: h * 0.62), controlPoint1: CGPoint(x: w * 0.3, y: h * 0.35), controlPoint2: CGPoint(x: w * 0.6, y: h * 0.95))
        back.addLine(to: CGPoint(x: w + 1, y: h + 1)); back.addLine(to: CGPoint(x: -1, y: h + 1)); back.close()
        fill(0xD98AE0); back.fill()
        let front = UIBezierPath()
        front.move(to: CGPoint(x: -1, y: h * 0.92))
        front.addCurve(to: CGPoint(x: w + 1, y: h * 0.78), controlPoint1: CGPoint(x: w * 0.45, y: h * 0.62), controlPoint2: CGPoint(x: w * 0.7, y: h * 1.05))
        front.addLine(to: CGPoint(x: w + 1, y: h + 1)); front.addLine(to: CGPoint(x: -1, y: h + 1)); front.close()
        fill(0x9A63EE); front.fill()
    }

    /// Deep teal circuit board: traces and solder points.
    private static func circuit(_ r: CGRect, _ cg: CGContext) {
        let w = r.width, h = r.height
        gradient([0x124643, 0x0B2E2C], r, cg)
        stroke(0x2FC6B8, 0.4); let traces = UIBezierPath(); traces.lineWidth = 0.9
        var x: CGFloat = 18
        var up = true
        while x < w {
            let y1: CGFloat = up ? 5 : h - 5, y2: CGFloat = up ? 10 : h - 10
            traces.move(to: CGPoint(x: x, y: up ? -1 : h + 1)); traces.addLine(to: CGPoint(x: x, y: y1))
            traces.addLine(to: CGPoint(x: x + 6, y: y2)); traces.addLine(to: CGPoint(x: x + 14, y: y2))
            traces.stroke()
            fill(0x5BE0D3, 0.55); UIBezierPath(ovalIn: CGRect(x: x + 13, y: y2 - 1.5, width: 3, height: 3)).fill()
            x += 17; up.toggle()
        }
    }

    /// Indigo with letters from other scripts floating behind.
    private static func letters(_ r: CGRect, _ cg: CGContext) {
        let w = r.width, h = r.height
        gradient([0x6C64FF, 0x4038D6], r, cg)
        let marks = ["文", "A", "あ", "Ж", "ع", "é", "한"]
        for (i, mark) in marks.enumerated() {
            let size: CGFloat = i % 2 == 0 ? 15 : 11
            NSAttributedString(string: mark, attributes: [.font: UIFont.systemFont(ofSize: size, weight: .bold), .foregroundColor: UIColor.white.withAlphaComponent(0.14)])
                .draw(at: CGPoint(x: CGFloat(i) * w / CGFloat(marks.count) + 2, y: i % 2 == 0 ? -2 : h - 14))
        }
    }
}

/// The SwiftUI twin of the capsule, for chips above the prompt and file names on a message.
struct IslandBadge: View {
    let title: String
    let icon: String
    let tint: IslandTint
    @Environment(\.displayScale) private var scale

    var body: some View {
        let island = Island(id: "badge", kind: .file, ref: "", title: title, icon: icon, tint: tint)
        Image(uiImage: IslandArt.image(island, traits: UITraitCollection(displayScale: scale)))
            .accessibilityLabel(title)
    }
}
