import AppKit
import Shared
import SwiftUI

/// One failure the engine queued for the user (`EngineStatus.pendingErrors`, Android's `Errors.pending` →
/// ui/ErrorDialog.kt): the plain-words message, the full details a button copies for the developer, and for a
/// bug a "report on GitHub" — the details go to the clipboard and a new issue opens with the short text filled
/// in. Closing the dialog by any path (OK, Esc, the dim behind it) dismisses the report in the engine.
struct ErrorDialog: View {
    var error: EngineStatus.PendingError
    /// Called once, however the dialog goes away.
    var dismiss: () -> Void
    @Environment(MenuState.self) private var menus
    @State private var done = false
    /// "Copy details" turned into "copied" (the share dialog's own confirm, Android's clipboard toast).
    @State private var copied = false

    /// The project's page, as Android's `Updater.project()`.
    private static let project = "timofeykhodykin/borderless"

    /// What a crash report says in place of the service's message (Android `err_crashed`, the service
    /// standing for "the app" here): a text of the app, so it renders in the UI language — the service's
    /// `message` is English-only `ServiceTexts` it can't render for the user.
    private static var crashBody: String {
        macText("mac.err.crash_body",
                "Last time the background service stopped because of an error. Copy the details and send them to the developer — it helps fix it.")
    }

    /// The text the dialog shows: the localized crash words for a crash, the service's message otherwise.
    private var message: String { error.crash ? Self.crashBody : error.message }

    var body: some View {
        // `crash` on the Mac means the background service died (on Android the service is the app, hence
        // "the app closed" there); other failures' message stays the service's own English text —
        // `ServiceTexts` is service-side and the app only relays it.
        AppDialog(title: error.crash ? macText("mac.err.crash_title", "The background service stopped unexpectedly") : L("err_title"), width: 480, spacing: 12) {
            ScrollView {
                VStack(alignment: .leading, spacing: 8) {
                    Text(message).font(Fonts.body(13)).foregroundStyle(Palette.textSecondary).textSelection(.enabled)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    // The details preview (Android's dialog area): the crash's own text explains the
                    // dialog, a service message's details fill it; a crash without them reads as empty.
                    if !error.details.isEmpty && error.details != error.message {
                        SelectableLogText(text: error.details).frame(height: 240)
                    }
                }
            }
            .frame(maxHeight: 300)
            if error.bug {
                Text(L("err_report_please")).font(Fonts.body(13)).foregroundStyle(Palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true).textSelection(.enabled)
            }
            // The buttons are `MacButton`s like every dialog's bottom row (the share dialog's):
            // raw `Button` + `MacButtonStyle` had no horizontal padding and a nonstandard height,
            // so the two small grey buttons and the accent one never lined up.
            HStack(spacing: 6) {
                if error.bug {
                    MacButton(title: L("err_report_github"), symbol: "ladybug") { report() }
                }
                MacButton(title: L(copied ? "copied" : "err_copy"), symbol: "doc.on.doc") { copy() }
                Spacer()
                MacButton(title: L("ok"), accent: true) { finish() }
                    .keyboardShortcut(.defaultAction)
            }
        }
        // Esc, the dim behind the dialog, a replace — every close dismisses the report (Android's
        // onDismissRequest), once. Only the report: the dialog is already gone, and closing "the" dialog
        // here kills a second error's dialog when this view was torn down by a replace.
        .onDisappear { finish(closing: false) }
    }

    /// `closing` is for the button paths that take the dialog down too; a teardown must never close.
    private func finish(closing: Bool = true) {
        guard !done else { return }
        done = true
        dismiss()
        if closing { menus.closeDialog() }
    }

    private func copy() {
        NSPasteboard.general.clearContents()
        // Marked concealed, so clipboard managers don't keep it (Android `copySensitive` — the share
        // dialog does the same; the details can name a server).
        NSPasteboard.general.setString(error.details, forType: .string)
        NSPasteboard.general.setString("", forType: NSPasteboard.PasteboardType("org.nspasteboard.ConcealedType"))
        copied = true
    }

    /// The details go to the clipboard, the browser opens a new issue with the short text filled in
    /// (`Updater.issuesUrl` on Android).
    private func report() {
        copy()
        let title = L("err_report_title", borderlessVersion, String(message.components(separatedBy: .newlines).first?.prefix(70) ?? ""))
        // The Mac's wording for the template: the details it describes are the app's and macOS's.
        let bodyFormat = macText("mac.err.report.body",
            "**What happened**\n\n%1$@\n\n**Details**\n\nPaste them below: the app copied them to the clipboard (app version, macOS version, device, what the service was doing and the stack trace; secrets are hidden).")
        let body = String(format: bodyFormat, locale: Locale.current, String(message.prefix(2000)))
        func encoded(_ s: String) -> String {
            s.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? s
        }
        if let url = URL(string: "https://github.com/\(Self.project)/issues/new?labels=bug&title=\(encoded(title))&body=\(encoded(body))") {
            NSWorkspace.shared.open(url)
        }
        finish()
    }
}
