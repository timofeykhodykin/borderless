import Foundation
import Shared

/// The log's export texts (Android `AppLog.exportEvents` / `AppLog.exportDebug`, shared from
/// `LogScreen.share`): the same text a bug report from the phone carries, so reports from both
/// platforms read alike to the developer — a `Border(less) <version>` header, an `exported <date>`
/// line on the detailed log, and full `yyyy-MM-dd HH:mm:ss` stamps (`platform.dateTime`) on every
/// event, today included.
enum LogExport {
    /// `platform.dateTime`: `yyyy-MM-dd HH:mm:ss` in the machine's zone, POSIX digits — the calendar
    /// date is always there (the screen's `timeText` drops it for today; a shared log needs it).
    private static let stamps: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd HH:mm:ss"
        f.locale = Locale(identifier: "en_US_POSIX")
        return f
    }()

    static func stamp(_ t: Date) -> String { stamps.string(from: t) }

    /// The events as text, oldest first: `<stamp>  <text>` per line — two spaces between them, as
    /// `AppLog.exportEvents` joins them. [events] arrive newest-first (the screen's order).
    static func events(_ events: [LogEvent]) -> String {
        events.reversed().map { "\(stamp($0.time))  \($0.text)" }.joined(separator: "\n")
    }

    /// The detailed log as text (`AppLog.exportDebug`): the header, `exported <stamp>`, a blank line,
    /// then the lines as written, each followed by a newline.
    static func detailed(header: String, exportedAt: Date, lines: [String]) -> String {
        var out = header + "\nexported \(stamp(exportedAt))\n\n"
        for line in lines { out += line + "\n" }
        return out
    }

    /// The header `exportDebug` leads with (Android `LogScreen.share`:
    /// "Border(less) <version>, <core>, <OS> (<model>)"). The core's version line is cut at " (" as
    /// `CoreEnv.version().substringBefore(" (")` does there.
    static func header(coreVersion: String) -> String {
        let core = coreVersion.components(separatedBy: " (").first ?? coreVersion
        let os = ProcessInfo.processInfo.operatingSystemVersion
        var text = "Border(less) \(borderlessVersion), \(core), macOS \(os.majorVersion).\(os.minorVersion).\(os.patchVersion)"
        let model = machineModel()
        if !model.isEmpty { text += " (\(model))" }
        return text
    }

    /// "Mac14,2" (sysctl `hw.model`, like the service's own `sysctl` for `Host.deviceModel`);
    /// "" when it can't be read.
    static func machineModel() -> String {
        var size = 0
        guard sysctlbyname("hw.model", nil, &size, nil, 0) == 0, size > 0 else { return "" }
        var buffer = [CChar](repeating: 0, count: size)
        guard sysctlbyname("hw.model", &buffer, &size, nil, 0) == 0 else { return "" }
        return String(cString: buffer)
    }
}
