import SwiftUI

/// The page shown instead of the main screen while the system service is missing or belongs to another version: it
/// says why the app needs a service with administrator rights before macOS asks for the password (owner, 2026-10-22).
struct ServiceSetupView: View {
    /// Installed from another version of the app: the same page, worded as an update.
    var update: Bool
    var onInstalled: () -> Void

    @State private var working = false
    /// The installer ran and the service is being waited on — the "waiting" note belongs to this part
    /// only; while the password dialog is up it would claim a wait that hasn't begun.
    @State private var waiting = false
    @State private var failure: String?

    var body: some View {
        HStack(alignment: .center, spacing: 36) {
            VStack(alignment: .leading, spacing: 0) {
                Crane()
                    .fill(Palette.accent)
                    .frame(width: 112, height: 72, alignment: .leading)
                    .padding(.bottom, 22)
                Text(update ? "setup.title.update" : "setup.title")
                    .font(Fonts.display(25, bold: true))
                    .foregroundStyle(Palette.text)
                    .fixedSize(horizontal: false, vertical: true)
                    .textSelection(.enabled)
                    .padding(.bottom, 12)
                Text(update ? "setup.lead.update" : "setup.lead")
                    .font(Fonts.body(13))
                    .foregroundStyle(Palette.text)
                    .lineSpacing(2)
                    .fixedSize(horizontal: false, vertical: true)
                    .textSelection(.enabled)
                    .padding(.bottom, 28)
                HStack(spacing: 10) {
                    Button(action: install) {
                        HStack(spacing: 8) {
                            if working { ProgressView().controlSize(.small).tint(Palette.onAccent) }
                            Text(update ? "setup.update" : "setup.install")
                        }
                        .font(Fonts.body(13, bold: true))
                        .foregroundStyle(Palette.onAccent)
                        .padding(.horizontal, 22)
                        .frame(height: 30)
                        .background(Capsule().fill(Palette.accent))
                    }
                    .buttonStyle(.plain)
                    .disabled(working)
                    .keyboardShortcut(.defaultAction)

                    Button { AppDelegate.quit() } label: {
                        Text("setup.quit")
                            .font(Fonts.body(13))
                            .foregroundStyle(Palette.textSecondary)
                            .padding(.horizontal, 18)
                            .frame(height: 30)
                            .background(Capsule().fill(Palette.card))
                    }
                    .buttonStyle(.plain)
                }
                .padding(.bottom, 12)
                Group {
                    if let failure {
                        Text("setup.failed \(failure)").foregroundStyle(Palette.danger).textSelection(.enabled)
                    } else {
                        Text(waiting ? "setup.waiting" : "setup.note").foregroundStyle(Palette.textSecondary).textSelection(.enabled)
                    }
                }
                .font(Fonts.body(13))
                .fixedSize(horizontal: false, vertical: true)
            }
            .frame(width: 330, alignment: .leading)

            VStack(spacing: 12) {
                Point(icon: "questionmark.circle", title: "setup.why.title", text: "setup.why.text")
                Point(icon: "arrow.triangle.branch", title: "setup.does.title", text: "setup.does.text")
                Point(icon: "hand.raised", title: "setup.doesnt.title", text: "setup.doesnt.text")
                Point(icon: "arrow.clockwise", title: "setup.updates.title", text: "setup.updates.text")
            }
        }
        .padding(.horizontal, 48)
        // More air above than below: the window's top strip is the hidden title bar, and a centred page read
        // too close to it (owner, 2026-11-07).
        .padding(.top, 72)
        .padding(.bottom, 32)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Palette.background)
    }

    private func install() {
        failure = nil
        working = true
        // The installer's password prompt runs off the main actor (ServiceInstaller), so the spinner
        // keeps turning while the system's dialog is up — it doesn't wait for the prompt any more.
        Task {
            switch await ServiceInstaller.install() {
            case .done:
                waiting = true
                defer { waiting = false }
                for _ in 0..<20 {
                    if Task.isCancelled { working = false; return }
                    if case .running = await ServiceClient.status() {
                        working = false
                        onInstalled()
                        return
                    }
                    try? await Task.sleep(for: .milliseconds(500))
                }
                working = false
                if !Task.isCancelled { failure = String(localized: "setup.not.answering") }
            case .cancelled:
                working = false
            case .failed(let message):
                working = false
                failure = message
            }
        }
    }
}

private struct Point: View {
    var icon: String
    var title: LocalizedStringKey
    var text: LocalizedStringKey

    var body: some View {
        HStack(alignment: .top, spacing: 14) {
            Image(systemName: icon)
                .font(.system(size: 17, weight: .medium))
                .foregroundStyle(Palette.accentStrong)
                .frame(width: 36, height: 36)
                .background(RoundedRectangle(cornerRadius: 10).fill(Palette.accentSoft))
            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(Fonts.display(15, bold: true))
                    .foregroundStyle(Palette.text)
                    .textSelection(.enabled)
                Text(text)
                    .font(Fonts.body(12))
                    .foregroundStyle(Palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .textSelection(.enabled)
                    .lineSpacing(2)
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 14)
        .frame(maxWidth: .infinity, alignment: .topLeading)
        .background(RoundedRectangle(cornerRadius: 16).fill(Palette.card))
    }
}
