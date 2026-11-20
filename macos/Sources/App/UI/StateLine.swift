import SwiftUI

extension MainModel {
    /// The state line's dot (sidebar, menu bar panel).
    var stateDot: Color {
        switch phase {
        case .connected: Palette.accent
        case .searching: Palette.accentContainer
        case .off: Palette.textMuted
        case .direct, .paused, .noNetwork, .limited: phase.tone!.strong
        }
    }

    /// The state line: the server in use when connected (as the screens name it — the generic-names
    /// setting hides its own name here too), else the state. The variants Android's title carries —
    /// a hand-picked server that stopped answering (`EngineStatus.unanswered`), and no servers left
    /// to work (`noServers`) — come off the last engine answer, as does the banner's wording.
    var stateText: String {
        switch phase {
        case .connected:
            if lastEngine?.unanswered == true { return L("status_unanswered") }
            return current.map { displayName($0) } ?? ""
        case .off: return L("status_off")
        case .searching: return L("status_searching")
        case .direct: return lastEngine?.noServers == true ? L("notif_no_servers") : L("notif_direct")
        case .paused: return lastEngine?.noServers == true ? L("notif_no_servers_paused") : L("notif_paused")
        case .noNetwork: return L("status_no_network")
        case .limited: return lastEngine?.paused == true ? L("notif_limited_paused") : L("status_limited")
        }
    }
}
