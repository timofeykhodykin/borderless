import AppKit
import SwiftUI

/// The surface the window is dragged by: a clear NSView sitting behind the parts of the top
/// strip no control covers (the header height across the window, and the sidebar's title row
/// which reaches deeper). A press that reaches it moves the window with the system's own drag —
/// `performDrag`, snapped screen edges and all — instead of hand-rolled `setFrameOrigin` math.
///
/// It is an NSView rather than a SwiftUI `DragGesture` so a press on a control can never start
/// a drag: the old gesture on the whole content fired for any drag starting in the strip, and
/// a press on a header control (a period segment, a menu anchor) that travelled 2 pt moved the
/// window and still tapped on release — the audit's U1.2. Behind the content, AppKit's hit test
/// decides who takes the press: a control claims its own spot first and only bare strip reaches
/// this view. It can't move the wrong window either — `performDrag` acts on the view's own
/// `window`, where the gesture's `NSApp.keyWindow` could resolve to the menu-bar panel.
struct WindowDrag: View {
    /// A menu or popup is closed when the window starts moving (it would hang over the spot it
    /// was opened at).
    var menus: MenuState?
    /// AppKit views can't be drawn by ImageRenderer: no surface in a snapshot (nothing to drag there).
    @Environment(\.snapshot) private var snapshot

    var body: some View {
        if !snapshot { DragSurface(menus: menus) }
    }
}

private struct DragSurface: NSViewRepresentable {
    var menus: MenuState?

    func makeNSView(context: Context) -> DragView {
        let view = DragView()
        view.onPress = { menus?.close() }
        return view
    }

    func updateNSView(_ view: DragView, context: Context) {
        view.onPress = { menus?.close() }
    }

    /// The press itself is the whole interaction — there is no up-action of its own to lose — so it
    /// also takes the click that only activates an inactive window, and a real title bar drags that
    /// window in one press. `isMovableByWindowBackground` stays off: the drag is this strip's, not
    /// every empty part of the window's (owner, 2026-10-23).
    final class DragView: NSView {
        /// Runs before the drag starts (the menus close); a `@MainActor` callback because it
        /// writes observable state, on the main thread like the rest of the view's life.
        var onPress: (@MainActor () -> Void)?

        override func acceptsFirstMouse(for event: NSEvent?) -> Bool { true }

        override func mouseDown(with event: NSEvent) {
            MainActor.assumeIsolated { onPress?() }
            window?.performDrag(with: event)
        }
    }
}
