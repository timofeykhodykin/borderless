import AppKit

/// `BORDERLESS_DEMO=1 BORDERLESS_WINDOW_SHOT=<file.png>`: draws the real window — AppKit fields, switches and all,
/// which `ImageRenderer` snapshots can't — into a PNG and quits. The app draws its own window, so no screen recording
/// permission is needed. `BORDERLESS_SECTION=<main|groups|stats|log|settings>` and `BORDERLESS_SELECT=<server or
/// subscription id>` choose what is shown. `BORDERLESS_SHOT_FRAMES=<n>` (with "%d" in the file name): a scan starts and n
/// frames are drawn 0.2 s apart — the orbits' animation, frame by frame.
@MainActor
enum WindowShot {
    static var section: Section {
        Section(rawValue: ProcessInfo.processInfo.environment["BORDERLESS_SECTION"] ?? "") ?? .main
    }

    static var selected: String? { ProcessInfo.processInfo.environment["BORDERLESS_SELECT"] }

    /// `BORDERLESS_CHOOSING=<server|strategy>`: the menu bar panel opens with that list already out.
    static var choosing: MenuBarPanel.Choice? {
        switch ProcessInfo.processInfo.environment["BORDERLESS_CHOOSING"] {
        case "server": .server
        case "strategy": .strategy
        default: nil
        }
    }

    static var frames: Int { Int(ProcessInfo.processInfo.environment["BORDERLESS_SHOT_FRAMES"] ?? "") ?? 0 }

    static func runIfAsked() {
        runPanelShotIfAsked()
        guard let path = ProcessInfo.processInfo.environment["BORDERLESS_WINDOW_SHOT"] else { return }
        Task {
            try? await Task.sleep(for: .seconds(1.5))
            // The window is the biggest visible one, not a hardcoded width: the app window is the only
            // large window this process makes, and an exact size breaks the day it changes.
            guard let window = NSApp.windows
                    .filter({ $0.isVisible && $0.contentView != nil })
                    .max(by: { $0.frame.width * $0.frame.height < $1.frame.width * $1.frame.height }),
                  let view = window.contentView?.superview ?? window.contentView else { exit(1) }
            // Native log regression check: badges and message runs must share one Cmd+A range.
            if ProcessInfo.processInfo.environment["BORDERLESS_SHOT_SELECT_ALL"] == "1" {
                func texts(_ v: NSView) -> [NSTextView] {
                    (v as? NSTextView).map { [$0] } ?? v.subviews.flatMap(texts)
                }
                guard let text = texts(view).max(by: { $0.string.utf16.count < $1.string.utf16.count }), !text.string.isEmpty else { exit(2) }
                window.makeFirstResponder(text)
                text.selectAll(nil)
                let total = (text.string as NSString).length
                guard text.selectedRange() == NSRange(location: 0, length: total) else { exit(3) }
                let result = "Selected all \(total) UTF-16 characters across the styled document."
                try? result.write(toFile: path + ".selection.txt", atomically: true, encoding: .utf8)
            }
            @MainActor func shoot(_ file: String) {
                guard let rep = view.bitmapImageRepForCachingDisplay(in: view.bounds) else { exit(1) }
                view.cacheDisplay(in: view.bounds, to: rep)
                do {
                    guard let data = rep.representation(using: .png, properties: [:]) else {
                        NSLog("Border(less): the window shot of \(file) gave no PNG data")
                        return
                    }
                    try data.write(to: URL(fileURLWithPath: file))
                } catch {
                    NSLog("Border(less): the window shot \(file) couldn't be written: \(error)")
                }
            }
            if frames > 0 {
                NotificationCenter.default.post(name: .shotScan, object: nil)
                for i in 0..<frames {
                    try? await Task.sleep(for: .milliseconds(200))
                    shoot(path.replacingOccurrences(of: "%d", with: String(format: "%03d", i)))
                }
            } else {
                shoot(path)
            }
            exit(0)
        }
    }

    /// `BORDERLESS_DEMO=1 BORDERLESS_PANEL_SHOT=<file.png>`: clicks the status item and draws the real panel window —
    /// the MenuBarExtraWindow itself, not an ImageRenderer stand-in — into a PNG and quits. `BORDERLESS_CHOOSING`
    /// opens an inline list first. The panel's height can be limited with `BORDERLESS_PANEL_MAXH=<pt>` (its content
    /// is re-laid out inside a capped window, as the system does on a short screen).
    private static func runPanelShotIfAsked() {
        guard let path = ProcessInfo.processInfo.environment["BORDERLESS_PANEL_SHOT"] else { return }
        Task {
            try? await Task.sleep(for: .seconds(1))
            @MainActor func press(_ view: NSView) -> Bool {
                // The first button that would actually take the click: a hidden or disabled one is skipped.
                if let button = view as? NSButton, button.isEnabled, !button.isHidden {
                    button.performClick(nil)
                    return true
                }
                return view.subviews.contains(where: press)
            }
            for window in NSApp.windows where String(describing: type(of: window)) == "NSStatusBarWindow" {
                if let content = window.contentView { _ = press(content) }
            }
            try? await Task.sleep(for: .milliseconds(700))
            guard let panel = NSApp.windows.first(where: {
                String(describing: type(of: $0)).hasPrefix("MenuBarExtraWindow")
            }) else { exit(2) }
            if let maxH = Double(ProcessInfo.processInfo.environment["BORDERLESS_PANEL_MAXH"] ?? ""),
               maxH < panel.frame.height {
                panel.setContentSize(NSSize(width: panel.frame.width, height: maxH))
                try? await Task.sleep(for: .milliseconds(300))
            }
            guard let view = panel.contentView?.superview ?? panel.contentView else { exit(1) }
            guard let rep = view.bitmapImageRepForCachingDisplay(in: view.bounds) else { exit(1) }
            view.cacheDisplay(in: view.bounds, to: rep)
            do {
                guard let data = rep.representation(using: .png, properties: [:]) else {
                    NSLog("Border(less): the panel shot of \(path) gave no PNG data")
                    exit(1)
                }
                try data.write(to: URL(fileURLWithPath: path))
            } catch {
                NSLog("Border(less): the panel shot \(path) couldn't be written: \(error)")
            }
            exit(0)
        }
    }
}

extension Notification.Name {
    /// [WindowShot] asks the main screen to start a scan (frames of the orbits).
    static let shotScan = Notification.Name("borderless.shotScan")
}
