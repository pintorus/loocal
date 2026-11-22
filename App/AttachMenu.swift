import SwiftUI

enum AttachAction: Equatable {
    case photos, camera, files
    case toggle(Connector)
    case addServer
}

// MARK: - Tray

/// The + button as a tray, in the persona picker's language: one capsule that unrolls
/// rightward out of the button with icons in a row, an ink circle gliding under the icon
/// you're on. Tap + to open it and tap an icon, or press, slide and release.
struct AttachControl: View {
    @Binding var open: Bool
    let connectors: [Connector]
    let active: Set<String>
    let vision: Bool
    let act: (AttachAction) -> Void

    private enum Page { case main, connectors }
    private enum Kind: Equatable { case action(AttachAction), connectors, back }
    private struct Item: Identifiable { let id: String; let icon: String; let on: Bool; let enabled: Bool; let kind: Kind }

    @State private var page: Page = .main
    @State private var pressing = false
    @State private var openedThisPress = false
    @State private var highlighted: Int?
    @State private var hold: DispatchWorkItem?
    @State private var center: CGPoint = .zero

    /// Icon spacing; tightens when many icons must fit the composer width.
    private var slot: CGFloat { min(42, 228 / CGFloat(max(items.count, 1))) } // fits beside the other composer buttons
    private let size: CGFloat = 38
    private let spring = Animation.spring(response: 0.32, dampingFraction: 0.8)

    private var items: [Item] {
        switch page {
        case .main:
            return [
                Item(id: "photos", icon: "photo.on.rectangle", on: false, enabled: vision, kind: .action(.photos)),
                Item(id: "camera", icon: "camera", on: false, enabled: vision, kind: .action(.camera)),
                Item(id: "files", icon: "doc", on: false, enabled: true, kind: .action(.files)),
                Item(id: "connectors", icon: "point.3.connected.trianglepath.dotted", on: !active.isEmpty, enabled: true, kind: .connectors),
            ]
        case .connectors:
            return [Item(id: "back", icon: "chevron.left", on: false, enabled: true, kind: .back)]
                + connectors.map { Item(id: $0.id, icon: $0.icon, on: active.contains($0.id), enabled: true, kind: .action(.toggle($0))) }
                + [Item(id: "server", icon: "server.rack", on: false, enabled: true, kind: .action(.addServer))]
        }
    }

    /// Offset of item `i` from the button's centre (items run left to right).
    private func dx(_ i: Int) -> CGFloat { slot * CGFloat(i + 1) + 6 }
    private var trayWidth: CGFloat { open ? slot * CGFloat(items.count) + size + 18 : size }

    var body: some View {
        ZStack {
            tray
            button
        }
        .frame(width: size, height: size)
        .onGeometryChange(for: CGPoint.self) { proxy in
            let frame = proxy.frame(in: .global)
            return CGPoint(x: frame.midX, y: frame.midY)
        } action: { center = $0 }
        // While open, the control's own frame grows to cover the capsule: SwiftUI only
        // delivers a gesture inside its view's frame, so taps on the icons need this.
        .frame(width: open ? trayWidth - 4 : size, height: size, alignment: .leading)
        .contentShape(.rect)
        .highPriorityGesture(press)
        .onChange(of: open) { _, now in if !now { highlighted = nil; page = .main } }
        .sensoryFeedback(.impact(weight: .light), trigger: open) { _, now in now }
        .sensoryFeedback(.selection, trigger: highlighted) { _, now in now != nil }
        .sensoryFeedback(.selection, trigger: active)
        .sensoryFeedback(.selection, trigger: page)
        .accessibilityElement()
        .accessibilityLabel("Add photos, files or connectors")
        .accessibilityAddTraits(.isButton)
        .accessibilityAction { withAnimation(spring) { open.toggle() } }
        .accessibilityActions {
            ForEach(items) { item in Button(item.id.capitalized) { choose(item) } }
        }
    }

    private var button: some View {
        Image(systemName: "plus")
            .font(.system(size: 18, weight: .regular))
            .foregroundStyle(open ? Ink.secondary : Ink.primary)
            .rotationEffect(.degrees(open ? 45 : 0))
            .frame(width: size, height: size)
            .background(open ? Ink.fill : .clear, in: .circle)
            .scaleEffect(pressing && !open ? 0.9 : 1)
            .animation(.spring(response: 0.3, dampingFraction: 0.65), value: pressing)
            .animation(spring, value: open)
    }

    private var tray: some View {
        ZStack {
            Capsule()
                .fill(Ink.paper)
                .overlay(Capsule().stroke(Ink.hairline))
                .shadow(color: .black.opacity(open ? 0.10 : 0), radius: 14, y: 5)
                .frame(width: trayWidth, height: open ? 46 : size)
                .offset(x: trayWidth / 2 - size / 2 - (open ? 4 : 0))
                .opacity(open ? 1 : 0)

            if let highlighted, open {
                Circle().fill(Ink.primary)
                    .frame(width: min(36, slot - 4), height: min(36, slot - 4))
                    .offset(x: dx(highlighted))
                    .animation(.spring(response: 0.3, dampingFraction: 0.72), value: highlighted)
            }

            ForEach(Array(items.enumerated()), id: \.element.id) { i, item in
                let lit = (highlighted == i || item.on) && open
                Image(systemName: item.icon)
                    .font(.system(size: 15, weight: .regular))
                    .foregroundStyle(lit ? Ink.paper : Ink.primary)
                    .frame(width: min(36, slot - 4), height: min(36, slot - 4))
                    .background(item.on && highlighted != i ? Ink.primary : .clear, in: .circle)
                    .opacity(open ? (item.enabled ? 1 : 0.3) : 0)
                    .scaleEffect(open ? (highlighted == i ? 1.08 : 1) : 0.4)
                    .blur(radius: open ? 0 : 3)
                    .offset(x: open ? dx(i) : 0)
                    .animation(spring.delay(open ? Double(i) * 0.02 : 0), value: open)
                    .animation(.spring(response: 0.25, dampingFraction: 0.6), value: lit)
                    .transition(.scale(scale: 0.4).combined(with: .opacity))
            }
        }
        .allowsHitTesting(false)
        .animation(spring, value: open)
        .animation(spring, value: page)
        // Invisible hit area matching the open capsule, so taps on it reach the gesture.
        .background {
            Color.clear
                .frame(width: trayWidth, height: 46)
                .offset(x: trayWidth / 2 - size / 2 - 4)
                .contentShape(.rect)
                .allowsHitTesting(open)
        }
    }

    private var press: some Gesture {
        DragGesture(minimumDistance: 0, coordinateSpace: .global)
            .onChanged { value in
                if !pressing {
                    pressing = true
                    openedThisPress = false
                    if !open {
                        let item = DispatchWorkItem { if pressing && !open { reveal() } }
                        hold = item
                        DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(180), execute: item)
                    }
                }
                // Sliding right opens at once.
                if !open && value.translation.width > 10 { hold?.cancel(); reveal() }
                if open { highlighted = hit(value.location) }
            }
            .onEnded { value in
                hold?.cancel()
                pressing = false
                if open {
                    if let index = hit(value.location) { choose(items[index]) }
                    else if !openedThisPress { close() } // tapped the × or away from the icons
                    highlighted = nil
                } else {
                    withAnimation(spring) { open = true } // plain tap: open and wait for a tap
                }
            }
    }

    private func hit(_ point: CGPoint) -> Int? {
        guard abs(point.y - center.y) < 48 else { return nil }
        let offset = point.x - center.x
        let index = items.indices.min { abs(dx($0) - offset) < abs(dx($1) - offset) }
        guard let index, items[index].enabled, abs(dx(index) - offset) < slot / 2 + (index == items.count - 1 ? 14 : 0) else { return nil }
        return index
    }

    private func choose(_ item: Item) {
        switch item.kind {
        case .connectors: withAnimation(spring) { page = .connectors }
        case .back: withAnimation(spring) { page = .main }
        case .action(let action):
            act(action) // toggles keep the tray open; everything else closes it
        }
    }

    private func reveal() {
        openedThisPress = true
        withAnimation(spring) { open = true }
    }

    private func close() {
        highlighted = nil
        withAnimation(spring) { open = false }
    }
}

// MARK: - Attachments, floating above the prompt

/// A document drawn as a sheet of paper: folded corner, a few lines of "text", its type
/// along the bottom. Used above the prompt and on sent messages.
struct FileCard: View {
    let name: String
    var compact = false

    private var kind: String {
        let ext = (name as NSString).pathExtension.uppercased()
        return ext.isEmpty ? "TXT" : String(ext.prefix(4))
    }

    var body: some View {
        let w: CGFloat = compact ? 40 : 54, h: CGFloat = compact ? 50 : 68
        ZStack(alignment: .topLeading) {
            FoldedSheet(fold: compact ? 9 : 12)
                .fill(Ink.paper)
                .shadow(color: .black.opacity(0.13), radius: compact ? 4 : 8, y: compact ? 2 : 4)
            FoldedSheet.Corner(fold: compact ? 9 : 12)
                .fill(Ink.fill)
            VStack(alignment: .leading, spacing: compact ? 2.5 : 3.5) {
                ForEach([0.62, 0.8, 0.5, 0.72], id: \.self) { part in
                    Capsule().fill(Ink.hairline).frame(width: (w - 16) * part, height: compact ? 1.6 : 2)
                }
            }
            .padding(.top, compact ? 13 : 17).padding(.leading, compact ? 6 : 8)
            Text(kind)
                .font(.geist(compact ? 7.5 : 9, .semibold)).tracking(0.3)
                .foregroundStyle(Ink.secondary)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomLeading)
                .padding(.leading, compact ? 6 : 8).padding(.bottom, compact ? 5 : 7)
        }
        .frame(width: w, height: h)
        .accessibilityElement()
        .accessibilityLabel("File \(name)")
    }
}

/// A page with its top-right corner folded over.
struct FoldedSheet: Shape {
    var fold: CGFloat
    func path(in r: CGRect) -> Path {
        var p = Path()
        let radius: CGFloat = 6
        p.move(to: CGPoint(x: r.minX + radius, y: r.minY))
        p.addLine(to: CGPoint(x: r.maxX - fold, y: r.minY))
        p.addLine(to: CGPoint(x: r.maxX, y: r.minY + fold))
        p.addLine(to: CGPoint(x: r.maxX, y: r.maxY - radius))
        p.addQuadCurve(to: CGPoint(x: r.maxX - radius, y: r.maxY), control: CGPoint(x: r.maxX, y: r.maxY))
        p.addLine(to: CGPoint(x: r.minX + radius, y: r.maxY))
        p.addQuadCurve(to: CGPoint(x: r.minX, y: r.maxY - radius), control: CGPoint(x: r.minX, y: r.maxY))
        p.addLine(to: CGPoint(x: r.minX, y: r.minY + radius))
        p.addQuadCurve(to: CGPoint(x: r.minX + radius, y: r.minY), control: CGPoint(x: r.minX, y: r.minY))
        p.closeSubpath()
        return p
    }

    /// The folded-over flap.
    struct Corner: Shape {
        var fold: CGFloat
        func path(in r: CGRect) -> Path {
            var p = Path()
            p.move(to: CGPoint(x: r.maxX - fold, y: r.minY))
            p.addLine(to: CGPoint(x: r.maxX - fold, y: r.minY + fold - 2))
            p.addQuadCurve(to: CGPoint(x: r.maxX - fold + 2, y: r.minY + fold), control: CGPoint(x: r.maxX - fold, y: r.minY + fold))
            p.addLine(to: CGPoint(x: r.maxX, y: r.minY + fold))
            p.closeSubpath()
            return p
        }
    }
}

/// What's attached to the next message, floating above the prompt box: the photo as a
/// little bordered print, each document as a sheet of paper, slightly askew.
struct AttachmentShelf: View {
    let photo: UIImage?
    let files: [AttachedFile]
    let preparing: Bool
    let removePhoto: () -> Void
    let removeFile: (AttachedFile) -> Void

    var body: some View {
        HStack(alignment: .bottom, spacing: 14) {
            if let photo {
                Image(uiImage: photo)
                    .resizable().scaledToFill()
                    .frame(width: 66, height: 66)
                    .clipShape(.rect(cornerRadius: 11))
                    .padding(3.5)
                    .background(Ink.paper, in: .rect(cornerRadius: 14))
                    .shadow(color: .black.opacity(0.16), radius: 10, y: 5)
                    .overlay(alignment: .topTrailing) { RemoveBadge(label: "Remove photo", action: removePhoto) }
                    .rotationEffect(.degrees(-3))
                    .transition(.asymmetric(insertion: .offset(y: 26).combined(with: .scale(scale: 0.7, anchor: .bottom)).combined(with: .opacity), removal: .scale(scale: 0.6).combined(with: .opacity)))
            }
            if preparing {
                RoundedRectangle(cornerRadius: 14).fill(Ink.paper)
                    .frame(width: 73, height: 73)
                    .shadow(color: .black.opacity(0.12), radius: 10, y: 5)
                    .overlay { SpiralLoader(size: 18, color: Ink.secondary) }
                    .rotationEffect(.degrees(-3))
                    .transition(.opacity)
            }
            ForEach(Array(files.enumerated()), id: \.element.id) { i, file in
                VStack(spacing: 6) {
                    FileCard(name: file.name)
                        .overlay(alignment: .topTrailing) { RemoveBadge(label: "Remove \(file.name)") { removeFile(file) } }
                    Text(file.name).font(.geist(11)).foregroundStyle(Ink.secondary).lineLimit(1).truncationMode(.middle)
                        .frame(maxWidth: 70)
                }
                .rotationEffect(.degrees(i % 2 == 0 ? 3 : -2))
                .transition(.asymmetric(insertion: .offset(y: 26).combined(with: .scale(scale: 0.7, anchor: .bottom)).combined(with: .opacity), removal: .scale(scale: 0.6).combined(with: .opacity)))
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 26).padding(.bottom, 10).padding(.top, 6)
    }
}

private struct RemoveBadge: View {
    let label: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: "xmark").font(.system(size: 8.5, weight: .bold)).foregroundStyle(Ink.paper)
                .frame(width: 20, height: 20).background(Ink.primary, in: .circle)
                .overlay(Circle().stroke(Ink.paper, lineWidth: 2))
                .frame(width: 32, height: 32).contentShape(.circle)
        }
        .buttonStyle(PressStyle(scale: 0.85))
        .offset(x: 12, y: -12)
        .accessibilityLabel(label)
    }
}

// MARK: - Camera

/// System camera, returning a JPEG.
struct CameraPicker: UIViewControllerRepresentable {
    let captured: (Data) -> Void
    @Environment(\.dismiss) private var dismiss

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.sourceType = .camera
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ controller: UIImagePickerController, context: Context) {}
    func makeCoordinator() -> Coordinator { Coordinator(self) }

    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let parent: CameraPicker
        init(_ parent: CameraPicker) { self.parent = parent }

        func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
            if let image = info[.originalImage] as? UIImage, let data = image.jpegData(compressionQuality: 0.9) { parent.captured(data) }
            parent.dismiss()
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) { parent.dismiss() }
    }
}
