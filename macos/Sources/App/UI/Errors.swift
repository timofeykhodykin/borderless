import Foundation
import Shared

/// What the app surfaces for the failures the daemon can't queue into `EngineStatus.pendingErrors`
/// itself — SVC-DAEMON's seam: the shared `Errors.show`/`Errors.keep` aren't reachable from the
/// service's Swift side, so a dead previous run is told as `ev_crashed` + the details in the
/// detailed log + `Reply.problem` (kept until the next tunnel start clears it — `lastProblem`).
/// The app turns the sighting into the same report Android's
/// `Errors.show(crash = true, bug = true)` produces: the crash dialog (crash title, localized body,
/// the GitHub offer) and, through [Notifications], a banner for a user who isn't looking.
@MainActor
enum Errors {
    /// `noteServiceProblem`'s crash sentence (Service/main.swift), matched by prefix: the only
    /// `Reply.problem` that means "the previous run died" — start failures carry their own texts.
    static let crashProblemPrefix = "the previous service run ended without a clean shutdown"

    /// The synthesized record's id — below any engine-queued one (those start at 1), so it never
    /// shares an identity with a real `pendingErrors` entry for the notification dedup.
    static let crashId: Int64 = -1

    /// The record already shown (and posted) this run: `.problem` repeats it on every poll until a
    /// start clears it — one dialog and one banner per app run, like Android's queue. App restarts
    /// tell it again while the record stands, as Android's next start would.
    private static var shown: String?

    /// The crash `PendingError` for the status poll's [problem], or nil when it isn't the dead-run
    /// record or it was already shown. Called once per status answer (the window's refresh loop):
    /// the banner is posted here ([Notifications.crashes]); the returned entry is for `ErrorDialog`.
    /// Its dismissal needs no `error_dismiss` back to the service — the service doesn't know it.
    static func crashEntry(problem: String?) -> EngineStatus.PendingError? {
        guard let problem, problem.hasPrefix(crashProblemPrefix), problem != shown else { return nil }
        shown = problem
        let entry = EngineStatus.PendingError(id: crashId, message: problem,
                                              details: details(problem), crash: true, bug: true)
        Notifications.crashes([entry])
        return entry
    }

    /// The report body "Copy details" and the GitHub report carry (Android `Errors.details`: the
    /// platform header — like the service's own `MacErrors.header` — then what happened). The full
    /// record, the system's crash report included, is in the detailed log.
    private static func details(_ problem: String) -> String {
        var text = "Border(less) for Mac \(borderlessVersion)\n" +
            "macOS \(ProcessInfo.processInfo.operatingSystemVersionString)"
        let model = LogExport.machineModel()
        if !model.isEmpty { text += " (\(model))" }
        return text + "\nTime: \(LogExport.stamp(Date()))\nWhere: service\n\n\(problem)"
    }
}

/// A locally-made report uses the wire type's own memberwise init (Shared Service.swift): the app
/// synthesizes just the crash entry above — everything else is the service's to queue.
