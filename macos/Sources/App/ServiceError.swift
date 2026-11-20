import Foundation

/// The service's internal error replies in words for the error line (UI-audit U1.3): "locked: palette",
/// "refused: no peer token", "bad request", "can't read the settings" used to reach the user verbatim —
/// English engineering notes, never localized. Each known reply maps to a text of ours (`macText`, so a
/// key not yet in Localizable.strings still reads as English rather than the raw reply); anything
/// unknown gets the generic refusal. The raw string stays available as [detail] for the log.
enum ServiceError {
    /// What the error line shows for the service's [raw] reply. nil or empty is a generic failure —
    /// a service that didn't answer at all is the callers' `main.no.answer`, not this.
    static func friendly(_ raw: String?) -> String {
        guard let raw, !raw.isEmpty else {
            return macText("mac.err.generic", "Something went wrong on the service's side.")
        }
        if raw.hasPrefix("Stored data is locked") {
            return macText("mac.err.storage_locked", "Secure storage could not be opened. Your servers and settings are preserved and writes are blocked. Restart the service when secure storage becomes available.")
        }
        if raw.hasPrefix("locked:") {
            // A field the daemon keeps for itself ("locked: palette" of a daemon older than the app).
            return macText("mac.err.locked", "The service keeps this setting itself; the change can't come from the app.")
        }
        if raw.hasPrefix("refused:") {
            return raw.contains("console user")
                ? macText("mac.err.console", "The service answers only the user logged in on this Mac.")
                : macText("mac.err.refused", "The service refused the change.")
        }
        if raw.hasPrefix("another tunnel") {
            // "another tunnel carries the traffic (utun4)": the interface's name goes into the known text.
            if let open = raw.firstIndex(of: "("),
               let close = raw[open...].firstIndex(of: ")"), raw.index(after: open) < close {
                let iface = String(raw[raw.index(after: open)..<close])
                return String(localized: "main.other.tunnel \(iface)")
            }
            return macText("mac.err.othertunnel", "Another tunnel is on — turn it off first.")
        }
        if raw.hasPrefix("can't read the ") || raw == "bad list" {
            // The service's parse of a settings/list/zone patch failed (its `Errors.guard` message).
            return macText("mac.err.unreadable", "The service couldn't read the change.")
        }
        if raw.hasPrefix("the core is missing") {
            return macText("mac.err.nocore", "The tunnel's core is missing — reinstall the service.")
        }
        switch raw {
        case "bad request":
            return macText("mac.err.request", "The service didn't understand the request.")
        case "request too large":
            return macText("mac.err.request.big", "The change was too large for the service.")
        case "reply too large":
            return macText("mac.err.reply.big", "The service's answer was too large to read.")
        case "no tunnel":
            return macText("mac.err.notunnel", "The tunnel is gone.")
        case "no network to go out by":
            return macText("mac.err.nonetwork", "There's no network the tunnel could use.")
        case "the zone is too big":
            return macText("mac.err.zone.big", "The zone is too big.")
        case "the name is too long":
            return macText("mac.err.name.long", "The name is too long.")
        case "too many entries":
            return macText("mac.err.zone.entries", "The zone has too many entries.")
        case "an entry is too long":
            return macText("mac.err.entry.long", "An entry in the zone is too long.")
        default:
            // An unknown reply is still a refusal — never shown raw.
            return macText("mac.err.refused", "The service refused the change.")
        }
    }

    /// [friendly] for a refusal that is about to be shown: the service's own words go to the log
    /// first — the user reads the translation, the log keeps the raw reply or it exists nowhere
    /// ([detail] is exactly that).
    static func friendlyLogged(_ raw: String?) -> String {
        if let raw = detail(raw), !raw.isEmpty {
            NSLog("Border(less): the service refused: \(raw)")
        }
        return friendly(raw)
    }

    /// The reply as the service sent it — for the log; [friendly] is for the user.
    static func detail(_ raw: String?) -> String? { raw }
}
