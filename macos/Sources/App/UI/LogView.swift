import AppKit
import SwiftUI

/// The log (Android `LogScreen.kt`): events in the UI language and the detailed log written verbatim, as two segments;
/// share (asking first whether to hide the servers' addresses) and clear (asking first) in the bar.
struct LogView: View {
    @Bindable var model: MainModel
    @Environment(MenuState.self) private var menus
    @Environment(\.snapshot) private var snapshot
    @State private var selectAll = 0

    var body: some View {
        VStack(spacing: 0) {
            ScreenHeader(title: L("log_title")) {
                Segmented(items: [(0, L("tab_events")), (1, L("tab_detailed"))], selection: $model.logTab).fixedSize()
                Hairline(vertical: true).frame(height: 16).padding(.horizontal, 4)
                ToolbarIcon(symbol: "text.badge.checkmark", help: macText("mac.log.select_all", "Select all")) { selectAll += 1 }
                ToolbarIcon(symbol: "square.and.arrow.up", help: L("share"), action: nil)
                    .appPopupOnClick(menus) { AnyView(shareQuestion) }
                ToolbarIcon(symbol: "trash", help: L("clear"), action: nil)
                    .appPopupOnClick(menus) { AnyView(clearQuestion) }
            }
            Group {
                if !snapshot {
                    VStack(alignment: .leading, spacing: 0) {
                        if model.logTab == 1 && !model.settings.verboseLog {
                            Text(L("log_verbose_off")).font(Fonts.body(11)).foregroundStyle(Palette.textSecondary)
                                .padding(.horizontal, 20).padding(.vertical, 8)
                        }
                        SelectableLogText(text: logText, selectAll: selectAll, attributedText: logDocument)
                    }
                } else if model.logTab == 0 { events } else { detailed }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        }
        .background(Palette.background)
    }

    private var logDocument: NSAttributedString {
        model.logTab == 0 ? LogDocument.events(model.events) : LogDocument.detailed(model.debugLines)
    }

    private var logText: String {
        if model.logTab == 0 {
            return model.events.map { "\(LogExport.stamp($0.time))  \($0.text)" }.joined(separator: "\n\n")
        }
        // Keep the original timestamps, including each core's date and fractional seconds.
        return model.debugLines.reversed().joined(separator: "\n")
    }

    // MARK: Events

    @ViewBuilder private var events: some View {
        if model.events.isEmpty {
            Text(L("log_empty")).font(Fonts.body(13)).foregroundStyle(Palette.textSecondary).padding(24).textSelection(.enabled)
        } else {
            list {
                ForEach(model.events) { e in
                    HStack(alignment: .firstTextBaseline, spacing: 10) {
                        Text(timeText(e.time)).font(.system(size: 11.5, design: .monospaced)).foregroundStyle(Palette.textSecondary)
                            .textSelection(.enabled).frame(width: 70, alignment: .leading)
                        Circle().fill(color(e.kind)).frame(width: 7, height: 7).alignmentGuide(.firstTextBaseline) { $0[.bottom] - 1 }
                        Text(e.text).font(Fonts.body(13)).foregroundStyle(Palette.text).textSelection(.enabled)
                            .fixedSize(horizontal: false, vertical: true)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .padding(.vertical, 5)
                }
            }
        }
    }

    // MARK: Detailed

    @ViewBuilder private var detailed: some View {
        VStack(alignment: .leading, spacing: 0) {
            if !model.settings.verboseLog {
                Text(L("log_verbose_off")).font(Fonts.body(11)).foregroundStyle(Palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true).textSelection(.enabled)
                    .padding(.horizontal, 20).padding(.top, 10)
            }
            if model.debugLines.isEmpty {
                // The events tab's empty state ("Nothing yet") doesn't fit — this log is written by the core.
                Text(macText("mac.detailed.empty", "The detailed log is empty."))
                    .font(Fonts.body(13)).foregroundStyle(Palette.textSecondary).padding(24).textSelection(.enabled)
            } else {
                list {
                    // Newest first, like the events tab (owner, 2026-11-10).
                    ForEach(Array(model.debugLines.reversed().enumerated()), id: \.offset) { _, raw in DebugLine(raw: raw) }
                }
            }
        }
    }

    @ViewBuilder private func list<Content: View>(@ViewBuilder _ rows: () -> Content) -> some View {
        if snapshot {
            let body = VStack(alignment: .leading, spacing: 0) { rows() }
                .padding(.horizontal, 20).padding(.top, 10).padding(.bottom, 20).padding(.trailing, 6)
            GeometryReader { g in body.frame(width: g.size.width, alignment: .top) }.clipped()
        } else {
            // Lazy: the detailed log is a few thousand lines and every line parses its source out (a plain stack
            // re-built them all on each 5 s refresh).
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 0) { rows() }
                    .padding(.horizontal, 20).padding(.top, 10).padding(.bottom, 20).padding(.trailing, 6)
            }
            .scrollIndicators(.automatic)
        }
    }

    private func color(_ kind: LogEvent.Kind) -> Color {
        switch kind {
        case .good: Palette.ping(40)
        case .info: Palette.peach
        case .warn: Palette.butter
        case .error: Palette.rose
        }
    }

    // MARK: Share and clear

    private var shareQuestion: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(L("log_share_title")).font(Fonts.body(13, bold: true)).foregroundStyle(Palette.text).textSelection(.enabled)
            Text(L("log_share_addresses")).font(Fonts.body(12)).foregroundStyle(Palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true).textSelection(.enabled)
            HStack(spacing: 6) {
                Spacer()
                MacButton(title: L("log_share_keep")) { share(hide: false) }
                MacButton(title: L("log_share_hide"), accent: true) { share(hide: true) }
            }
        }
        .padding(12)
        .frame(width: 280)
    }

    private var clearQuestion: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(L("clear_log_q")).font(Fonts.body(13, bold: true)).foregroundStyle(Palette.text).textSelection(.enabled)
            Text(L("cannot_undo")).font(Fonts.body(11)).foregroundStyle(Palette.danger).textSelection(.enabled)
                HStack(spacing: 6) {
                    Spacer()
                    MacButton(title: L("cancel")) { menus.close() }
                    // Destructive: the danger face, not accent (Android's red confirm text).
                    DangerButton(title: L("clear")) { menus.close(); model.clearLog() }
                }
        }
        .padding(12)
        .frame(width: 240)
    }

    /// The log as text into the system's share menu, opened where the question was — in Android's export format
    /// ([LogExport]): `yyyy-MM-dd HH:mm:ss` stamps on the events, a `Border(less) <version>` header and an
    /// `exported` line on the detailed log. Hiding: the servers' hosts and the public IPv4 addresses become
    /// "server-N" / "ip-N" (the shared `Scrub`, as Android's).
    private func share(hide: Bool) {
        let anchor = menus.anchor
        menus.close()
        Task { @MainActor in
            var text: String
            if model.logTab == 0 {
                text = LogExport.events(model.events)
            } else {
                // The core's version is asked of the bundled helper — seconds the first time, never on the
                // window's thread (`SharedCode.coreVersion` caches the answer for the next share).
                let core = await Task.detached { SharedCode.coreVersion }.value
                text = LogExport.detailed(header: LogExport.header(coreVersion: core), exportedAt: Date(), lines: model.debugLines)
            }
            text = SharedCode.scrub(text, hideServers: hide)
            guard let view = NSApp.keyWindow?.contentView else { return }
            let rect = NSRect(x: anchor.x, y: view.isFlipped ? anchor.y - 8 : view.bounds.height - anchor.y + 8, width: 1, height: 1)
            NSSharingServicePicker(items: [text]).show(relativeTo: rect, of: view, preferredEdge: .minY)
        }
    }
}

/// One entry of the detailed log: time and a coloured source label on top, the untouched message below (Android
/// `DebugLine`).
private struct DebugLine: View {
    var raw: String

    var body: some View {
        let line = parse(raw)
        let bad = line.message.contains("[Error]") || line.message.contains(" failed") || line.message.contains(": timeout")
        let warn = line.message.contains("[Warning]")
        VStack(alignment: .leading, spacing: 2) {
            HStack(spacing: 6) {
                if let time = line.time { Text(time).font(.system(size: 10.5, design: .monospaced)).foregroundStyle(Palette.textSecondary).textSelection(.enabled) }
                if let source = line.source {
                    let (label, color) = style(source)
                    Text(label).font(.system(size: 9, weight: .bold, design: .monospaced)).foregroundStyle(Palette.text)
                        .textSelection(.enabled)
                        .padding(.horizontal, 4).padding(.vertical, 1)
                        .background(RoundedRectangle(cornerRadius: 3).fill(color.opacity(0.18)))
                }
            }
            Text(line.message).font(.system(size: 11.5, design: .monospaced))
                .foregroundStyle(bad ? Palette.danger : warn ? Palette.warningIcon : Palette.text)
                .textSelection(.enabled)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.vertical, 4)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func parse(_ line: String) -> (time: String?, source: String?, message: String) {
        // Stamps seen in this log: the service's own "MM-dd HH:mm:ss(.SSS)", the cores' own
        // "yyyy/MM/dd" or "yyyy-MM-dd" dates, then an optional "[tag]" before the message.
        let pattern = #/^(?:(?:\d\d-\d\d|\d\d\d\d[-/.]\d\d[-/.]\d\d)\s+)?(\d\d:\d\d:\d\d)(?:\.\d+)?\s+(?:\[([^\]]+)\]\s+)?(.*)$/#
        guard let m = line.wholeMatch(of: pattern) else { return (nil, nil, line) }
        let messageStart = m.3.startIndex
        let header = String(line[..<messageStart])
        let stamp = header.components(separatedBy: "[").first?.trimmingCharacters(in: .whitespaces)
        return (stamp, m.2.map(String.init), String(m.3))
    }

    private func style(_ source: String) -> (String, Color) {
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

/// "14:05:09" today, "22.10 14:05:09" before (Android `formatTime`).
private func timeText(_ t: Date) -> String {
    Calendar.current.isDateInToday(t) ? t.formatted(.dateTime.hour(.twoDigits(amPM: .omitted)).minute(.twoDigits).second(.twoDigits))
        : t.formatted(.dateTime.day(.twoDigits).month(.twoDigits).hour(.twoDigits(amPM: .omitted)).minute(.twoDigits).second(.twoDigits))
}

/// The danger fill for a destructive confirm lives in `Model+Settings.swift` (`DangerButton`,
/// `DangerButtonStyle`) — shared by the settings, the zone dialog and this popup.
