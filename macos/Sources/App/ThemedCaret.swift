import AppKit
import SwiftUI

/// macOS text fields share a native field editor; SwiftUI's tint alone doesn't colour its caret.
@MainActor
enum ThemedCaret {
    private static var observers: [NSObjectProtocol] = []

    static func install() {
        guard observers.isEmpty else { return }
        let center = NotificationCenter.default
        for name in [NSControl.textDidBeginEditingNotification, NSText.didBeginEditingNotification,
                     NSTextView.didChangeSelectionNotification, NSWindow.didBecomeKeyNotification] {
            observers.append(center.addObserver(forName: name, object: nil, queue: .main) { _ in
                MainActor.assumeIsolated {
                    refresh()
                    if name != NSTextView.didChangeSelectionNotification {
                        DispatchQueue.main.async { refresh() }
                    }
                }
            })
        }
    }

    static func refresh() {
        for window in NSApp.windows {
            if let editor = window.firstResponder as? NSTextView { apply(editor) }
        }
    }

    private static func apply(_ editor: NSTextView) {
        guard editor.isEditable else { return }
        let color = NSColor(Palette.accent)
        if editor.insertionPointColor != color {
            editor.insertionPointColor = color
            editor.needsDisplay = true
        }
    }
}
