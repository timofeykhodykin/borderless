import AppKit
import SwiftUI

/// The visible window of a `TimeChart` (Android's `timeView` / `valueView`; nil = the whole axis).
/// The data behind a chart spans exactly `from`…`to`, so the window never leaves that range.
struct ChartViewport: Equatable {
    /// The visible time range in absolute dates: while the loaded period slides forward on refreshes,
    /// the window stays put at its times until the range pushes it along (as Android's does).
    var time: ClosedRange<Date>? = nil
    /// The visible part of the value axis in its normalized space — 0…1 across the whole `YScale`.
    /// Normalized space *is* ln-space for a log scale, so a value zoom behaves like Android's "units".
    var value: ClosedRange<Double>? = nil

    var zoomed: Bool { time != nil || value != nil }

    /// The narrowest the time axis goes — Android's `MIN_TIME_SPAN` (5 min).
    static let minTimeSpan: TimeInterval = 300
}

/// ⌥ held during a pinch: the value axis instead of the time one — the desktop form of Android's
/// "fingers side by side zoom time, one above the other zoom value".
func chartOptionDown() -> Bool { NSEvent.modifierFlags.contains(.option) }

/// Catches scroll-wheel events over a chart so a two-finger scroll can pan a zoomed window
/// (SwiftUI has no scroll-wheel gesture; AppKit does the routing like `RightClickCatcher` in Menus).
/// Deltas the chart can't use are passed up the responder chain, so the page keeps scrolling.
struct ScrollWheelCatch: NSViewRepresentable {
    /// Pans with the delta (a size in points) and returns whether it was used.
    var onScroll: (CGSize) -> Bool

    func makeNSView(context: Context) -> CatchView {
        let v = CatchView()
        v.onScroll = onScroll
        return v
    }

    func updateNSView(_ view: CatchView, context: Context) { view.onScroll = onScroll }

    final class CatchView: NSView {
        var onScroll: ((CGSize) -> Bool)?

        override func hitTest(_ point: NSPoint) -> NSView? {
            // Only scroll events concern this overlay; everything else falls through to the chart.
            NSApp.currentEvent?.type == .scrollWheel ? super.hitTest(point) : nil
        }

        override func scrollWheel(with event: NSEvent) {
            // A wheel mouse reports line deltas; bring them to points so both pan at the same feel.
            let k: CGFloat = event.hasPreciseScrollingDeltas ? 1 : 12
            let d = CGSize(width: event.scrollingDeltaX * k, height: event.scrollingDeltaY * k)
            // Not used (nothing zoomed, or a page-scroll direction): the ScrollView above handles it.
            if onScroll?(d) != true { super.scrollWheel(with: event) }
        }
    }
}
