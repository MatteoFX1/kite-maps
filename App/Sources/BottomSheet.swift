import SwiftUI
import UIKit

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
    @GestureState private var drag: CGFloat = 0
    @GestureState private var bodyDrag: CGFloat = 0
    @State private var contentAtTop = true
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
            .gesture(
                DragGesture(minimumDistance: 6)
                    .updating($drag) { v, s, _ in s = v.translation.height }
                    .onEnded { settle($0) }
            )
            .onTapGesture {
                if detent == .peek { withAnimation(.spring(response: 0.32, dampingFraction: 0.85)) { detent = .half } }
            }
            content()
                .frame(maxHeight: .infinity, alignment: .top)
                // Below full height the list doesn't scroll: a drag anywhere moves the sheet.
                .scrollDisabled(detent != .full)
                .coordinateSpace(name: SheetTopMarker.space)
                .onPreferenceChange(SheetTopKey.self) { contentAtTop = $0 >= -2 }
                .simultaneousGesture(
                    DragGesture(minimumDistance: 12)
                        .updating($bodyDrag) { v, s, _ in
                            if follows(v) { s = v.translation.height }
                        }
                        .onEnded { v in if follows(v) { settle(v) } }
                )
        }
        .frame(height: current, alignment: .top)
        .frame(maxWidth: .infinity)
        .background(
            UnevenTopRounded(radius: 22)
                .fill(Vela.sheet(scheme))
                .shadow(color: .black.opacity(0.25), radius: 10, y: -2)
                .ignoresSafeArea(edges: .bottom)
        )
        .clipShape(UnevenTopRounded(radius: 22))
    }
}

extension BottomSheet {
    /** Whether a drag on the sheet's body moves the sheet: mostly vertical, and at full height
     *  only a pull down from the top of the list. */
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
        withAnimation(.spring(response: 0.32, dampingFraction: 0.85)) { detent = best }
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
