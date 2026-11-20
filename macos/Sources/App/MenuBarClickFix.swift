import AppKit
import Shared

/// Makes a click on the menu bar item close the open panel wherever on the item it lands, like the system's own items
/// (the same fix as in mac-claude-usage).
///
/// With the pointer pushed against the top of the screen, the press first lands in the system's menu bar: MenuBarExtra
/// closes the panel as for an outside click. The menu bar then forwards the press to our status item, whose button
/// toggles the panel on release and opens it again — the panel blinks and stays. This drops that forwarded press (and
/// its release) when the panel closed with it, so the button never sees the click. Clicks right on the button take
/// another path and are left alone; SwiftUI handles those fine.
@MainActor
enum MenuBarClickFix {
    /// System uptime of the last time the panel closed.
    private static var closedAt: TimeInterval = -.infinity
    /// Set while a dropped press waits for its release, which is dropped too.
    private static var droppedPressAt: TimeInterval?
    private static var tokens: [Any] = []

    static func install() {
        guard tokens.isEmpty else { return }
        for name in [NSWindow.didResignKeyNotification, NSWindow.didChangeOcclusionStateNotification] {
            tokens.append(NotificationCenter.default.addObserver(forName: name, object: nil, queue: .main) { note in
                guard let window = note.object as? NSWindow, isPanel(window) else { return }
                MainActor.assumeIsolated {
                    if name == NSWindow.didResignKeyNotification || !window.isVisible {
                        closedAt = ProcessInfo.processInfo.systemUptime
                    }
                }
            })
        }
        tokens.append(NSEvent.addLocalMonitorForEvents(matching: [.leftMouseDown, .leftMouseUp]) { event in
            guard let window = event.window, isStatusItem(window) else { return event }
            let panelVisible = NSApp.windows.contains { isPanel($0) && $0.isVisible }
            let drop: Bool
            if event.type == .leftMouseDown {
                // The shared rule's own window is 0.5 s — wide enough that a genuine fast re-click (press →
                // close → a deliberate press again within half a second) was swallowed with the forwarded
                // one. The forward lags the close by a frame or two; 0.3 s still covers it.
                drop = MenuBarClick.closedWithPress(pressAt: event.timestamp, closedAt: closedAt, dropdownVisible: panelVisible)
                    && closedAt <= event.timestamp + 0.3
                droppedPressAt = drop ? event.timestamp : nil
            } else {
                drop = droppedPressAt.map { event.timestamp - $0 < 5 } ?? false
                droppedPressAt = nil
            }
            return drop ? nil : event
        } as Any)
    }

    // Private AppKit / SwiftUI class names: if they ever change, this quietly does nothing.
    nonisolated private static func isStatusItem(_ window: NSWindow) -> Bool {
        String(describing: type(of: window)) == "NSStatusBarWindow"
    }

    nonisolated private static func isPanel(_ window: NSWindow) -> Bool {
        String(describing: type(of: window)).hasPrefix("MenuBarExtraWindow")
    }
}
