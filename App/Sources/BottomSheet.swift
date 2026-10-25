import SwiftUI
import UIKit

/** The spring the sheet settles with. */
private let sheetSnap = Animation.spring(response: 0.32, dampingFraction: 0.85)

/**
 * Vela's draggable bottom sheet: peek, half and full heights. Drag it from anywhere: below full
 * height the whole sheet moves; at full height its list scrolls, and pulling down from the top of
 * the list brings the sheet down.
 */
struct BottomSheet<Header: View, Content: View>: View {
    @Binding var detent: SheetDetent
    let containerHeight: CGFloat
    var peekHeight: CGFloat = 150
    @ViewBuilder var header: () -> Header
    @ViewBuilder var content: () -> Content
    // When a drag ends, the finger's offset springs away together with the snap to the new
    // height, instead of jumping back first (that jump made the sheet twitch on release).
    @GestureState(resetTransaction: Transaction(animation: sheetSnap)) private var drag: CGFloat = 0
    @GestureState(resetTransaction: Transaction(animation: sheetSnap)) private var bodyDrag: CGFloat = 0
    @State private var contentAtTop = true
    // What the current drag on the list does, chosen once when it starts (keyed by where the
    // finger went down). Choosing again mid-drag made a scroll back up to the top of the list
    // suddenly grab the whole sheet and pull it down.
    @State private var bodyDragMode: (start: CGPoint, movesSheet: Bool)?
    @Environment(\.colorScheme) private var scheme

    static func height(_ d: SheetDetent, container: CGFloat, peek: CGFloat) -> CGFloat {
        switch d {
        case .peek: return peek
        case .half: return container * 0.46
        case .full: return container * 0.94
        }
    }

    private var current: CGFloat {
        let h = Self.height(detent, container: containerHeight, peek: peekHeight) - drag - bodyDrag
        return min(max(h, peekHeight * 0.8), containerHeight * 0.96)
    }

    var body: some View {
        VStack(spacing: 0) {
            VStack(spacing: 0) {
                Capsule()
                    .fill(Color.secondary.opacity(0.45))
                    .frame(width: 36, height: 4)
                    .padding(.top, 8)
                    .padding(.bottom, 6)
                header()
            }
            .frame(maxWidth: .infinity)
            .contentShape(Rectangle())
            // Drags are measured on the screen, not on the sheet: the sheet moves under the
            // finger, so measuring on the sheet fed that movement back in and made it shake.
            .gesture(
                DragGesture(minimumDistance: 6, coordinateSpace: .global)
                    .updating($drag) { v, s, _ in s = v.translation.height }
                    .onEnded { settle($0) }
            )
            .onTapGesture {
                if detent == .peek { withAnimation(sheetSnap) { detent = .half } }
            }
            content()
                .frame(maxHeight: .infinity, alignment: .top)
                // Below full height the list doesn't scroll: a drag anywhere moves the sheet.
                .scrollDisabled(detent != .full)
                .coordinateSpace(name: SheetTopMarker.space)
                .onPreferenceChange(SheetTopKey.self) { contentAtTop = $0 >= -2 }
                .simultaneousGesture(
                    DragGesture(minimumDistance: 12, coordinateSpace: .global)
                        .onChanged { v in
                            if bodyDragMode?.start != v.startLocation {
                                bodyDragMode = (v.startLocation, follows(v))
                            }
                        }
                        .updating($bodyDrag) { v, s, _ in
                            if movesSheet(v) { s = v.translation.height }
                        }
                        .onEnded { v in
                            if movesSheet(v) { settle(v) }
                            bodyDragMode = nil
                        }
                )
        }
        .frame(height: current, alignment: .top)
        .frame(maxWidth: .infinity)
        .background(
            // Frosted, like iOS 26: mostly solid so text stays easy to read.
            shape.fill(.ultraThinMaterial)
                .overlay(shape.fill(Vela.sheet(scheme).opacity(0.88)))
                .overlay(shape.stroke(Color.white.opacity(scheme == .dark ? 0.12 : 0.5), lineWidth: 0.75))
                .shadow(color: .black.opacity(0.25), radius: 14, y: -2)
        )
        .clipShape(shape)
        // Below full height it floats just off the screen's edges, the way iOS 26 sheets do.
        .padding(.horizontal, floating ? 8 : 0)
        .padding(.bottom, floating ? 8 : 0)
        .animation(.spring(response: 0.32, dampingFraction: 0.85), value: floating)
    }

    private var floating: Bool { detent != .full }

    private var shape: RoundedRectangle {
        RoundedRectangle(cornerRadius: floating ? 34 : 28, style: .continuous)
    }
}

extension BottomSheet {
    /** Whether the drag in progress on the sheet's body was chosen to move the sheet. */
    private func movesSheet(_ v: DragGesture.Value) -> Bool {
        guard let m = bodyDragMode, m.start == v.startLocation else { return false }
        return m.movesSheet
    }

    /** Whether a drag on the sheet's body should move the sheet, judged as it starts: mostly
     *  vertical, and at full height only a pull down that starts with the list at its top. */
    private func follows(_ v: DragGesture.Value) -> Bool {
        guard abs(v.translation.height) > abs(v.translation.width) else { return false }
        return detent != .full || (contentAtTop && v.translation.height > 0)
    }

    private func settle(_ v: DragGesture.Value) {
        let end = Self.height(detent, container: containerHeight, peek: peekHeight) - v.predictedEndTranslation.height
        let options: [SheetDetent] = [.peek, .half, .full]
        let best = options.min { a, b in
            abs(Self.height(a, container: containerHeight, peek: peekHeight) - end)
                < abs(Self.height(b, container: containerHeight, peek: peekHeight) - end)
        } ?? .half
        if best != detent { UISelectionFeedbackGenerator().selectionChanged() }
        withAnimation(sheetSnap) { detent = best }
    }
}

/** Put at the top of a sheet's scrolling list, so the sheet knows when the list is at its top. */
struct SheetTopMarker: View {
    static let space = "sheet-content"

    var body: some View {
        GeometryReader { g in
            Color.clear.preference(key: SheetTopKey.self, value: g.frame(in: .named(Self.space)).minY)
        }
        .frame(height: 0)
    }
}

struct SheetTopKey: PreferenceKey {
    static var defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = nextValue() }
}

/** A rectangle with only its top corners rounded (iOS 16 has no built-in one). */
struct UnevenTopRounded: Shape {
    var radius: CGFloat

    func path(in rect: CGRect) -> Path {
        Path(UIBezierPath(roundedRect: CGRect(x: rect.minX, y: rect.minY, width: rect.width, height: rect.height + 2000),
                          byRoundingCorners: [.topLeft, .topRight],
                          cornerRadii: CGSize(width: radius, height: radius)).cgPath)
    }
}
