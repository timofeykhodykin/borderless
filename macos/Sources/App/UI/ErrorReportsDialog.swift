import AppKit
import Shared
import SwiftUI

/// Inspect the retained failures without exporting statistics or marking anything as reported.
struct ErrorReportsDialog: View {
    @Environment(MenuState.self) private var menus
    @State private var report: EngineStatus.PendingError?
    @State private var failure: String?

    var body: some View {
        Group {
            if let report {
                ErrorDialog(error: report, dismiss: {})
            } else {
                AppDialog(title: L("set_errors"), width: 480, spacing: 12) {
                    if let failure {
                        Text(failure).font(Fonts.body(13)).textSelection(.enabled)
                        MacButton(title: L("ok"), accent: true) { menus.closeDialog() }
                    } else {
                        ProgressView().controlSize(.small)
                    }
                }
            }
        }
        .task {
            let reply = await ServiceClient.send(Request(.errorReports), timeoutSec: 10)
            if let reply, reply.error == nil, let text = reply.report?.joined(separator: "\n"), !text.isEmpty {
                report = EngineStatus.PendingError(id: 0, message: L("set_errors"), details: text, crash: false, bug: true)
            } else {
                failure = reply?.error.map { ServiceError.friendly($0) } ?? String(localized: "main.no.answer")
            }
        }
    }
}
