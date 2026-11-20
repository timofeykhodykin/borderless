import AppKit
import SwiftUI

/// A single styled text document: timestamps, source badges and messages all share one selection.
@MainActor
enum LogDocument {
    static let badgeColor = NSAttributedString.Key("BorderlessLogBadgeColor")

    static func events(_ events: [LogEvent]) -> NSAttributedString {
        let out = NSMutableAttributedString()
        for (i, event) in events.enumerated() {
            let paragraph = NSMutableParagraphStyle()
            paragraph.tabStops = [NSTextTab(textAlignment: .left, location: 145), NSTextTab(textAlignment: .left, location: 160)]
            paragraph.headIndent = 160
            paragraph.paragraphSpacing = 10
            let start = out.length
            append(LogExport.stamp(event.time) + "\t", to: out, font: .monospacedSystemFont(ofSize: 11.5, weight: .regular), color: Palette.textSecondary)
            let color: Color = switch event.kind {
            case .good: Palette.ping(40)
            case .info: Palette.peach
            case .warn: Palette.butter
            case .error: Palette.rose
            }
            append("●", to: out, font: .systemFont(ofSize: 8), color: color)
            append("\t" + event.text + (i == events.count - 1 ? "" : "\n"), to: out,
                   font: .systemFont(ofSize: 13), color: Palette.text)
            out.addAttribute(.paragraphStyle, value: paragraph, range: NSRange(location: start, length: out.length - start))
        }
        return out
    }

    static func detailed(_ lines: [String]) -> NSAttributedString {
        let out = NSMutableAttributedString()
        for (i, raw) in lines.reversed().enumerated() {
            let parsed = parse(raw)
            if let timestamp = parsed.timestamp {
                let headerStart = out.length
                append(timestamp, to: out, font: .monospacedSystemFont(ofSize: 10.5, weight: .regular), color: Palette.textSecondary)
                if let source = parsed.source {
                    let (label, color) = sourceStyle(source)
                    append("   ", to: out, font: .monospacedSystemFont(ofSize: 10.5, weight: .regular), color: Palette.textSecondary)
                    let badgeStart = out.length
                    append(" " + label + " ", to: out, font: .monospacedSystemFont(ofSize: 9, weight: .bold), color: Palette.text)
                    out.addAttribute(badgeColor, value: NSColor(color.opacity(0.18)), range: NSRange(location: badgeStart, length: out.length - badgeStart))
                }
                append("\n", to: out, font: .monospacedSystemFont(ofSize: 10.5, weight: .regular), color: Palette.textSecondary)
                let header = NSMutableParagraphStyle()
                header.paragraphSpacing = 2
                out.addAttribute(.paragraphStyle, value: header, range: NSRange(location: headerStart, length: out.length - headerStart))
            }
            let start = out.length
            let bad = parsed.message.contains("[Error]") || parsed.message.contains(" failed") || parsed.message.contains(": timeout")
            let warn = parsed.message.contains("[Warning]")
            append(parsed.message + (i == lines.count - 1 ? "" : "\n"), to: out,
                   font: .monospacedSystemFont(ofSize: 11.5, weight: .regular),
                   color: bad ? Palette.danger : warn ? Palette.warningIcon : Palette.text)
            let body = NSMutableParagraphStyle()
            body.paragraphSpacing = parsed.timestamp == nil ? 4 : 10
            out.addAttribute(.paragraphStyle, value: body, range: NSRange(location: start, length: out.length - start))
        }
        return out
    }

    private static func append(_ text: String, to out: NSMutableAttributedString, font: NSFont, color: Color) {
        out.append(NSAttributedString(string: text, attributes: [.font: font, .foregroundColor: NSColor(color)]))
    }

    static func parse(_ line: String) -> (timestamp: String?, source: String?, message: String) {
        let pattern = #/^((?:(?:\d\d-\d\d|\d\d\d\d[-/.]\d\d[-/.]\d\d)\s+)?\d\d:\d\d:\d\d(?:\.\d+)?)\s+(?:\[([^\]]+)\]\s+)?(.*)$/#
        guard let m = line.wholeMatch(of: pattern) else { return (nil, nil, line) }
        return (String(m.1), m.2.map(String.init), String(m.3))
    }

    static func sourceStyle(_ source: String) -> (String, Color) {
        let c = SeriesColors.all
        switch source {
        case "event": return ("EVENT", c[0])
        case "Engine": return ("ENGINE", c[1])
        case "probe": return ("PROBE", c[2])
        case "core": return ("CORE", c[6])
        case "xray": return ("XRAY", c[3])
        case "Tunnel": return ("TUNNEL", c[5])
        case "Importer": return ("SUBS", c[7])
        default: return (String(source.uppercased().prefix(8)), Palette.textMuted)
        }
    }
}
