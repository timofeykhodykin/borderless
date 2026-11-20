import AppKit
import QuartzCore
import Shared

/// Exercises only this process's demo window; never drives the installed app or its service.
@MainActor
enum NavigationProbe {
    private static var started = false
    private static let tracePath = ProcessInfo.processInfo.environment["BORDERLESS_NAV_TRACE"]

    static func trace(_ section: Section, selected: Bool, action: () -> Void) {
        guard let tracePath else { action(); return }
        let began = ProcessInfo.processInfo.systemUptime
        let event = NSApp.currentEvent?.timestamp ?? began
        action()
        let handled = ProcessInfo.processInfo.systemUptime
        DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(16)) {
            let sample = NavigationTraceSample(section: section.rawValue, reselect: selected,
                queuedMs: max(0, (began - event) * 1000), handlerMs: (handled - began) * 1000,
                nextFrameMs: (ProcessInfo.processInfo.systemUptime - began) * 1000)
            Task { await NavigationTraceWriter.shared.append(sample, path: tracePath) }
        }
    }

    static func runIfAsked(_ model: MainModel) {
        #if DEBUG
        guard !started, ProcessInfo.processInfo.environment["BORDERLESS_DEMO"] == "1",
              let path = ProcessInfo.processInfo.environment["BORDERLESS_NAV_PROBE"] else { return }
        started = true
        Task {
            if let dataPath = ProcessInfo.processInfo.environment["BORDERLESS_NAV_DATA"] {
                let data = try Data(contentsOf: URL(fileURLWithPath: dataPath))
                let before = try JSONDecoder().decode([String: Reply].self, from: data)
                if let list = before["servers"]?.servers { model.load(list) }
                if let settings = before["settings"]?.settings { model.loadSettings(settings) }
                if let zones = before["zones"]?.zones { model.loadZones(zones) }
            }
            try? await Task.sleep(for: .seconds(1))
            guard let window = NSApp.windows.first(where: { $0.isVisible && $0.styleMask.contains(.titled) }) else { exit(2) }
            func press(_ section: Section) -> Bool {
                guard let index = Section.allCases.firstIndex(of: section) else { return false }
                // The fixed demo window's sidebar: first row at y=130, 28 pt rows with 1 pt gaps.
                let point = NSPoint(x: 100, y: window.frame.height - 144 - CGFloat(index) * 29)
                for type in [NSEvent.EventType.leftMouseDown, .leftMouseUp] {
                    guard let event = NSEvent.mouseEvent(with: type, location: point, modifierFlags: [],
                        timestamp: ProcessInfo.processInfo.systemUptime, windowNumber: window.windowNumber,
                        context: nil, eventNumber: 0, clickCount: 1, pressure: 1) else { return false }
                    window.sendEvent(event)
                }
                return true
            }
            var timings: [String: [Double]] = [:]
            let pages = ProcessInfo.processInfo.environment["BORDERLESS_NAV_ALL_PAGES"] == "1"
                ? SettingsPage.allCases : [.server]
            for page in pages {
              model.settingsPage = page
              for test in ["reselect", "return"] {
                for _ in 0..<(pages.count == 1 ? 12 : 3) {
                    model.section = test == "return" ? .main : .settings
                    try? await Task.sleep(for: .milliseconds(80))
                    let start = CACurrentMediaTime()
                    guard press(.settings) else { exit(3) }
                    try? await Task.sleep(for: .milliseconds(1))
                    window.contentView?.layoutSubtreeIfNeeded()
                    window.displayIfNeeded()
                    timings["\(page)-\(test)", default: []].append((CACurrentMediaTime() - start) * 1000)
                    guard model.section == .settings else { exit(4) }
                }
              }
            }
            let data = try JSONSerialization.data(withJSONObject: timings, options: [.prettyPrinted, .sortedKeys])
            try data.write(to: URL(fileURLWithPath: path))
            exit(0)
        }
        #endif
    }
}

private struct NavigationTraceSample: Codable, Sendable {
    let section: String
    let reselect: Bool
    let queuedMs: Double
    let handlerMs: Double
    let nextFrameMs: Double
}

private actor NavigationTraceWriter {
    static let shared = NavigationTraceWriter()
    private var samples: [NavigationTraceSample] = []
    func append(_ sample: NavigationTraceSample, path: String) {
        samples.append(sample)
        samples = Array(samples.suffix(100))
        guard let data = try? JSONEncoder().encode(samples) else { return }
        try? data.write(to: URL(fileURLWithPath: path), options: .atomic)
        try? FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: path)
    }
}
