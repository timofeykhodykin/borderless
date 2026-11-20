import Shared
import SwiftUI

// APP-SHELL's model/view additions (PLAN §0: Model.swift belongs to APP-LIST — extensions live here).

/// What the wire's `engine.update` asks the shell to show (ruling R2, PLAN §2): a compact banner over
/// the window's content and a row in the menu-bar panel. Optional-safe until the updater ships: a
/// service that never sends `update` leaves `AppModel.update` nil and nothing of this exists.
extension EngineStatus.UpdateStatus {
    /// The states the user should be told about on the surfaces (the banner, the menu-bar row): an
    /// offer that waits for an answer, work in progress or a failure — never plain "idle".
    var interesting: Bool { state != "idle" && !state.isEmpty }

    /// The banner's title line (nil for "idle" — and for a state a newer service adds that this build
    /// doesn't know: unknown states are shown plainly, not guessed at).
    var bannerTitle: String {
        switch state {
        case "checking": L("update_checking")
        case "available": L("update_available", version ?? "?")
        case "downloading": macText("mac.update.downloading", "Downloading the update…")
        case "ready": L("update_notif_ready")
        case "installing": macText("mac.update.installing", "Installing the update…")
        case "error": L("update_failed", error ?? "?")
        default: L("update_available", version ?? "?")  // anything a newer service adds
        }
    }

    /// Whether the user's yes decides something right now: a staged build waits for its install.
    var waitsForUser: Bool { state == "ready" }
}

/// The update banner over the window's content: one line of state, and while a staged build waits,
/// "Update" (which asks once more — the explicit confirmation ruling R2 wants before installing) and
/// "Hide" (the service skips this version for the session). Dismiss is always there once work or a
/// failure is on the banner — it only tells the service; the engine decides what it means per state.
struct UpdateBanner: View {
    var update: EngineStatus.UpdateStatus
    var model: MainModel
    @Environment(MenuState.self) private var menus
    @State private var expanded = false

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: update.state == "error" ? "exclamationmark.triangle" : "arrow.down.circle.fill")
                .font(.system(size: 15))
                .foregroundStyle(update.state == "error" ? Palette.danger : Palette.accentStrong)
            VStack(alignment: .leading, spacing: 4) {
                Button { expanded.toggle() } label: {
                    HStack(alignment: .top, spacing: 5) {
                        if update.state == "error" { Image(systemName: expanded ? "chevron.down" : "chevron.right") }
                        Text(expanded ? L("err_title") : update.bannerTitle).lineLimit(2)
                    }
                }
                .buttonStyle(.plain)
                if expanded {
                    Text(update.bannerTitle).textSelection(.enabled)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .font(Fonts.body(12.5)).foregroundStyle(Palette.text)
            .layoutPriority(1)
            if update.waitsForUser {
                MacButton(title: L("update"), accent: true) { confirmInstall() }
            }
            if update.state == "ready" || update.state == "error" {
                MacButton(title: L("update_dismiss")) { model.backend?.runAction("update_dismiss") }
            }
        }
        .padding(.horizontal, 12).padding(.vertical, 8)
        .frame(maxWidth: 420)
        .background(RoundedRectangle(cornerRadius: Dims.cardRadius).fill(Palette.card))
        .overlay(RoundedRectangle(cornerRadius: Dims.cardRadius).strokeBorder(Palette.outline, lineWidth: 1))
        .shadow(color: .black.opacity(0.12), radius: 8, y: 2)
    }

    /// The install's confirmation (ruling R2: "install only after explicit user confirmation"): the
    /// banner's own Update doesn't install by itself — the card's second tap does, through
    /// `serviceAction "update_install"`.
    private func confirmInstall() {
        let version = update.version ?? "?"
        menus.show(dialog: AnyView(
            VStack(alignment: .leading, spacing: 8) {
                Text(L("update_to", version)).font(Fonts.body(13, bold: true)).foregroundStyle(Palette.text).textSelection(.enabled)
                Text(macText("mac.update.install.q", "The service and the app are replaced and the tunnel reconnects — a moment without it."))
                    .font(Fonts.body(11)).foregroundStyle(Palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true).textSelection(.enabled)
                HStack(spacing: 6) {
                    Spacer()
                    MacButton(title: L("cancel")) { menus.closeDialog() }.keyboardShortcut(.cancelAction)
                    MacButton(title: L("update"), accent: true) {
                        menus.closeDialog()
                        model.backend?.runAction("update_install")
                    }.keyboardShortcut(.defaultAction)
                }
                .padding(.top, 4)
            }
            .padding(18)
            .frame(width: 320)
        ))
    }
}

extension MainModel {
    /// The banner's own state for the demo and the screenshots: the live one comes from the service
    /// (`AppModel.update`); this is the demo's stand-in — a staged build, so the banner's full form
    /// shows in `BORDERLESS_DEMO`.
    static let demoUpdate = EngineStatus.UpdateStatus(state: "ready", version: "0.0.0",
                                                      checkedAt: 0, readyAt: 0, error: nil)
}
