import SwiftUI

/** Vela's draggable bottom sheet: peek, half and full heights. Drag the header to move it. */
struct BottomSheet<Header: View, Content: View>: View {
    @Binding var detent: SheetDetent
    let containerHeight: CGFloat
    var peekHeight: CGFloat = 150
    @ViewBuilder var header: () -> Header
    @ViewBuilder var content: () -> Content
    @GestureState private var drag: CGFloat = 0
    @Environment(\.colorScheme) private var scheme

    static func height(_ d: SheetDetent, container: CGFloat, peek: CGFloat) -> CGFloat {
        switch d {
        case .peek: return peek
        case .half: return container * 0.46
        case .full: return container * 0.94
        }
    }

    private var current: CGFloat {
        let h = Self.height(detent, container: containerHeight, peek: peekHeight) - drag
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
                    .onEnded { v in
                        let end = Self.height(detent, container: containerHeight, peek: peekHeight) - v.predictedEndTranslation.height
                        let options: [SheetDetent] = [.peek, .half, .full]
                        let best = options.min { a, b in
                            abs(Self.height(a, container: containerHeight, peek: peekHeight) - end)
                                < abs(Self.height(b, container: containerHeight, peek: peekHeight) - end)
                        } ?? .half
                        withAnimation(.spring(response: 0.32, dampingFraction: 0.85)) { detent = best }
                    }
            )
            .onTapGesture {
                if detent == .peek { withAnimation(.spring(response: 0.32, dampingFraction: 0.85)) { detent = .half } }
            }
            content()
                .frame(maxHeight: .infinity, alignment: .top)
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

/** A rectangle with only its top corners rounded (iOS 16 has no built-in one). */
struct UnevenTopRounded: Shape {
    var radius: CGFloat

    func path(in rect: CGRect) -> Path {
        Path(UIBezierPath(roundedRect: CGRect(x: rect.minX, y: rect.minY, width: rect.width, height: rect.height + 2000),
                          byRoundingCorners: [.topLeft, .topRight],
                          cornerRadii: CGSize(width: radius, height: radius)).cgPath)
    }
}
