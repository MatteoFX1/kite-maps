import ActivityKit
import Foundation
import KiteShared

/**
 * The next turn on the lock screen and in the Dynamic Island. The shared app decides what to show
 * and when (at most every two seconds); this just hands it to iOS.
 */
@available(iOS 16.2, *)
final class LiveTurn: NSObject, LiveTurnBridge {
    private var activity: Activity<NavActivityAttributes>?

    func start(destination: String, state: TurnState) {
        end()
        guard ActivityAuthorizationInfo().areActivitiesEnabled else { return }
        activity = try? Activity.request(attributes: NavActivityAttributes(destination: destination),
                                         content: ActivityContent(state: Self.content(state), staleDate: nil))
    }

    func update(state: TurnState) {
        guard let a = activity else { return }
        let c = Self.content(state)
        Task { await a.update(ActivityContent(state: c, staleDate: Date().addingTimeInterval(120))) }
    }

    func end() {
        if let a = activity { Task { await a.end(nil, dismissalPolicy: .immediate) } }
        activity = nil
        // One left over from a drive Kite was closed during.
        for a in Activity<NavActivityAttributes>.activities {
            Task { await a.end(nil, dismissalPolicy: .immediate) }
        }
    }

    private static func content(_ s: TurnState) -> NavActivityAttributes.ContentState {
        NavActivityAttributes.ContentState(symbol: s.symbol, distance: s.distance, instruction: s.instruction,
                                           road: s.road, arrival: s.arrival, arrived: s.arrived)
    }
}
