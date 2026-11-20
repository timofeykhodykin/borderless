import CryptoKit
import Foundation
import Security
import Shared

/// Updates the service and the app from the project's releases — Android's `Updater` for the Mac
/// (ruling R2; the design is `local/macos/PLAN.md` §1.0.3/§2 "the root service updates both").
///
/// Once a day (the engine's due-work tick and the settings' "Check" button, [checkIfDue]) the latest
/// GitHub release — never a pre-release, the endpoint leaves them out — is looked up and a newer one is
/// offered through `EngineStatus.update` ("available"). With the automatic-updates setting on, the Mac
/// build is downloaded over an unmetered network, signature-checked ([UpdateTrust]) and unpacked into
/// the staging dir — "ready": the app posts its notification and asks. Only the explicit `update_install`
/// then marks the build [UpdateState.approved]: nothing is ever applied while the tunnel is up or on its
/// own — the service hands itself over to the new build at the next start (a disconnect, a launchd
/// restart, a reboot), where [applyStaged] runs before anything of the old build does.
///
/// Downloads go direct, bound to the physical interface like all of the service's own traffic, else
/// through a working server (the engine's throwaway SOCKS proxy — Android `Updater.anyRoute`).
enum Updater {
    // MARK: where things live (all root-only, inside dataDir)

    /// The staging dir: the downloaded payload, the signed sums, the unpacked build.
    private static let dir = ServicePaths.dataDir + "/update"
    /// The kept state (the offer, the staged build, the answers) — outside the staging dir so wiping it
    /// can never lose the user's answers.
    private static let statePath = ServicePaths.dataDir + "/update-state.json"
    private static let zipPath = dir + "/payload.zip"
    private static let sumsPath = dir + "/SHA256SUMS.txt"
    private static let sigPath = dir + "/SHA256SUMS.txt.sig"
    private static let unpackDir = dir + "/unpacked"
    private static let stagedAppPath = dir + "/Border(less).app"
    /// Where the installed app bundle was last seen ([noteAppPath]) — the apply replaces it there.
    private static let appPathFile = ServicePaths.dataDir + "/app-path"
    /// The one-shot job that swaps this run for the new build ([reloadIntoNewBuild]).
    private static let reloadPlist = dir + "/reload.plist"
    private static let appName = "Border(less).app"

    // MARK: the tunables (Android `Updater`'s)

    /// Once a day, like Android.
    private static let checkEveryMs: Int64 = 24 * 3_600_000
    /// A download doesn't wait forever for an unmetered network (the offer stays regardless).
    private static let meteredWaitMs: Int64 = 3 * 24 * 3_600_000
    /// The payload's hard bound — Android's MAX_APK.
    private static let maxPayload: Int64 = 150 * 1024 * 1024

    // MARK: the live state

    /// [s], [running], [lastError] — the kept state and this run's work. Held for field changes only.
    private static let lock = NSLock()
    /// One op at a time: checks, downloads, installs and dismissals all run on it.
    private static let queue = DispatchQueue(label: "update")
    nonisolated(unsafe) private static var s = UpdateState()
    /// The wire word for the op in flight ("checking" / "downloading"), nil while idle.
    nonisolated(unsafe) private static var running: String?
    /// This run's last failure (Android keeps the same in memory).
    nonisolated(unsafe) private static var lastError: String?
    nonisolated(unsafe) private static var tunnel: TunnelController?
    /// Watches for the moment an approved build can take over ([watchApply]).
    nonisolated(unsafe) private static var applyWatch: DispatchSourceTimer?
    /// The app bundle path last seen from a trusted peer ([noteAppPath]); the file backs it.
    private static let peerLock = NSLock()
    nonisolated(unsafe) private static var peerPath: String?
    /// Negative ids — the helper matches replies by id and the engine's own ids count up from 1.
    nonisolated(unsafe) private static var helperId: Int64 = 0

    // MARK: engine entry points (the hooks MacUpdater installs)

    /// The engine's due-work ([force] = false) and `update_check` (true) — returns at once, the work
    /// runs on [queue].
    static func check(force: Bool) { queue.async { checkIfDue(force: force) } }
    /// `update_install` — the explicit confirmation ruling R2 wants before anything is applied.
    static func install() { queue.async { installNow() } }
    /// `update_dismiss` — the offer for this version is hidden (Android `dismiss`).
    static func dismiss() { queue.async { dismissNow() } }

    /// The updater's state for `EngineStatus.update` (the Kotlin side decodes the JSON).
    static func status() -> String {
        lock.lock()
        let state = UpdateCheck.wireState(running: running, lastError: lastError, s)
        let u = EngineStatus.UpdateStatus(
            state: state,
            version: s.version.isEmpty ? nil : s.version,
            checkedAt: s.checkedAt > 0 ? s.checkedAt : nil,
            readyAt: s.readyAt > 0 ? s.readyAt : nil,
            error: state == "error" ? lastError : nil)
        lock.unlock()
        guard let data = try? JSONEncoder().encode(u) else { return "" }
        return String(decoding: data, as: UTF8.self)
    }

    // MARK: boot

    /// Once, as the service starts and before the engine or the tunnel: an approved staged build is put
    /// in place now — the swap runs only while the service isn't carrying the tunnel, which a fresh
    /// start guarantees (ruling R2's "apply on reboot / service start"). On success it never returns:
    /// the service hands itself over to the new build through the reload job.
    static func applyStaged() {
        cleanLeftovers()
        let loaded = loadState()
        lock.lock()
        s = loaded
        lock.unlock()
        guard loaded.approved, loaded.staged else { return }
        // Never apply what isn't newer — a stale state file must not downgrade the install.
        guard loaded.code > (UpdateCheck.code(of: borderlessVersion) ?? 0) else {
            log("update: staged build \(loaded.version) is not newer than this one — dropped")
            lock.lock()
            s.staged = false; s.approved = false; s.asset = ""; s.sha256 = ""
            saveLocked()
            lock.unlock()
            cleanPayload()
            return
        }
        if let problem = apply() {
            log("update: could not apply \(loaded.version): \(problem) — staying on this build")
            lock.lock()
            s.applyError = problem
            s.approved = false
            // A payload that failed the apply isn't trusted for a retry — the offer stands and a new
            // "Update" downloads it again.
            s.staged = false; s.asset = ""; s.sha256 = ""
            saveLocked()
            lock.unlock()
            return
        }
        log("update: \(loaded.version) applied; handing over to the new build")
        reloadIntoNewBuild()
    }

    /// Once, after `EngineBridge.setUp` (the events and the detailed log are the engine's): the kept
    /// state is loaded, a version change and a kept apply failure are told (`ev_updated` /
    /// `ev_update_not_installed` — Android's `init`), the hooks plug in, a stale payload is cleaned and
    /// the first due check is queued. An install approved before a stop that never got to apply gets
    /// its watcher back.
    static func setUp(tunnel t: TunnelController) {
        tunnel = t
        lock.lock()
        var changed = false
        let now = UpdateCheck.code(of: borderlessVersion) ?? 0
        if s.lastCode != now {
            if s.lastCode > 0, s.lastCode < now {
                EngineBridge.event("ev_updated", args: [borderlessVersion])
            }
            s.lastCode = now
            changed = true
        }
        // An offer that's no longer newer than this build is gone — staged build, approval, everything
        // (Android `init` drops the kept download the same way).
        if s.code > 0, s.code <= now {
            s.version = ""; s.code = 0; s.asset = ""
            s.zipUrl = ""; s.sumsUrl = ""; s.sigUrl = ""
            s.staged = false; s.approved = false; s.sha256 = ""
            changed = true
        }
        if !s.applyError.isEmpty {
            EngineBridge.event("ev_update_not_installed", args: [s.applyError], kind: "WARN")
            lastError = s.applyError
            s.applyError = ""
            changed = true
        }
        if changed { saveLocked() }
        let resume = s.approved && s.staged
        lock.unlock()
        if !resume { cleanPayload() }
        cleanHelpers()
        EngineBridge.installUpdater(
            check: { force in Updater.check(force: force) },
            install: { Updater.install() },
            dismiss: { Updater.dismiss() },
            status: { Updater.status() })
        if resume { watchApply() }
        queue.async { checkIfDue(force: false) }
    }

    /// The bundle a trusted peer runs from (`PeerCheck`, per connection): the apply replaces the app
    /// where it actually is — /Applications or wherever the user keeps it — so the path is remembered
    /// (dataDir/app-path; written only on a change, connections are frequent).
    static func noteAppPath(pid: pid_t) {
        var buf = [CChar](repeating: 0, count: Int(MAXPATHLEN))
        guard proc_pidpath(pid, &buf, UInt32(buf.count)) > 0 else { return }
        let exe = String(decoding: buf.prefix { $0 != 0 }.map { UInt8(bitPattern: $0) }, as: UTF8.self)
        guard let m = exe.range(of: "/Contents/MacOS/") else { return }
        let path = String(exe[..<m.lowerBound])
        guard path.hasSuffix(".app") else { return }
        peerLock.lock()
        defer { peerLock.unlock() }
        guard peerPath != path else { return }
        peerPath = path
        try? AtomicFile.save(Data((path + "\n").utf8), to: appPathFile)
    }

    // MARK: the check

    /// Android's `checkIfDue`: a check when the last is a day old (or [force]d), then whatever the
    /// offer calls for ([proceed] — the persisted offer proceeds even after a failed check, like
    /// Android's `_available`).
    private static func checkIfDue(force: Bool) {
        if force || nowMs() - s.checkedAt >= checkEveryMs {
            runCheck()
        }
        if let offer = offerFromState() {
            proceed(offer)
        }
    }

    /// The network part of a check (Android `Updater.check`): records what the latest release offers —
    /// or the failure — into [s].
    private static func runCheck() {
        guard physical() != nil else {
            EngineBridge.debug("update", "check skipped: no network")
            return
        }
        lock.lock()
        running = "checking"
        lastError = nil
        lock.unlock()
        defer { lock.lock(); running = nil; lock.unlock() }
        do {
            let release = try anyRoute { proxy in try latest(proxy) }
            let now = nowMs()
            let newer = release.flatMap { $0.code > ownCode() ? $0 : nil }
            lock.lock()
            s.checkedAt = now
            if let newer {
                if s.code != newer.code {
                    EngineBridge.event("ev_update_found", args: [newer.version])
                    s.foundAt = now
                    // A different build is offered now: an older one's staged build and approval are gone.
                    s.staged = false; s.approved = false; s.sha256 = ""
                    cleanPayload()
                }
                s.version = newer.version; s.code = newer.code; s.asset = newer.asset
                s.zipUrl = newer.zipUrl; s.sumsUrl = newer.sumsUrl; s.sigUrl = newer.sigUrl
            } else if s.code != 0 {
                // Nothing newer anymore (the release went away, or this build overtook it).
                s.version = ""; s.code = 0; s.asset = ""
                s.zipUrl = ""; s.sumsUrl = ""; s.sigUrl = ""
                s.staged = false; s.approved = false; s.sha256 = ""
                cleanPayload()
            }
            saveLocked()
            lock.unlock()
            EngineBridge.debug("update", "latest release: \(release?.version ?? "none")"
                + (newer == nil ? " (this build is up to date)" : ""))
        } catch {
            let msg = (error as? UErr)?.text ?? error.localizedDescription
            lock.lock(); lastError = msg; lock.unlock()
            EngineBridge.debug("update", "check failed: \(msg)")
        }
    }

    /// A newer release is known (Android `proceed`): already staged stands as "ready"; with automatic
    /// updates it downloads — unmetered networks only — to "ready"; else it just stays offered
    /// ("available") for the user to take.
    private static func proceed(_ r: Release) {
        lock.lock()
        let stagedForIt = s.staged && s.code == r.code
        lock.unlock()
        if stagedForIt { return }
        guard autoUpdate() else { return }
        if metered() {
            let found = s.foundAt
            EngineBridge.debug("update",
                found > 0 && nowMs() - found > meteredWaitMs
                    ? "update \(r.version): still only a metered network after days — the offer stands"
                    : "update \(r.version) waits for an unmetered network")
            return
        }
        do {
            try obtain(r)
        } catch {
            let msg = (error as? UErr)?.text ?? error.localizedDescription
            lock.lock(); lastError = msg; lock.unlock()
            EngineBridge.event("ev_update_failed", args: [msg], kind: "WARN")
        }
    }

    /// `update_install`: the offer is staged if it isn't yet — "downloading" while it comes — then
    /// marked approved. The swap itself never runs here: the service hands over at the next start, or
    /// once the tunnel is off ([watchApply]).
    private static func installNow() {
        lock.lock(); let staged = s.staged; lock.unlock()
        if !staged {
            guard let r = offerFromState() else { return }
            do {
                try obtain(r)
            } catch {
                let msg = (error as? UErr)?.text ?? error.localizedDescription
                lock.lock(); lastError = msg; lock.unlock()
                EngineBridge.event("ev_update_failed", args: [msg], kind: "WARN")
                return
            }
        }
        lock.lock()
        s.approved = true
        let version = s.version
        saveLocked()
        lock.unlock()
        EngineBridge.event("ev_update_installing", args: [version])
        watchApply()
    }

    /// `update_dismiss`: the offer for this version is hidden (Android `dismiss` — the version's own
    /// code is remembered, a different one still shows); a kept failure is forgotten with it.
    private static func dismissNow() {
        lock.lock()
        if s.code > 0 { s.dismissed = s.code }
        lastError = nil
        saveLocked()
        lock.unlock()
    }

    // MARK: the release

    /// What a release offers: its version and code and where its Mac build, sums and signature live.
    private struct Release {
        var version: String, code: Int
        var asset: String, zipUrl: String, sumsUrl: String, sigUrl: String
    }

    /// The kept offer as a [Release], or nil (nothing offered, or not newer than this build).
    private static func offerFromState() -> Release? {
        lock.lock()
        defer { lock.unlock() }
        guard s.code > 0, s.code > ownCode(), !s.zipUrl.isEmpty, !s.asset.isEmpty else { return nil }
        return Release(version: s.version, code: s.code, asset: s.asset,
                       zipUrl: s.zipUrl, sumsUrl: s.sumsUrl, sigUrl: s.sigUrl)
    }

    /// This build's release code (Android's `BuildConfig.VERSION_CODE` role).
    private static func ownCode() -> Int { UpdateCheck.code(of: borderlessVersion) ?? 0 }

    /// The latest release (GitHub leaves pre-releases out of "latest") with a Mac build for this
    /// processor, else nil. A release without a signed checksum file is not offered ([UpdateTrust]).
    private static func latest(_ proxy: Proxy?) throws -> Release? {
        let url = "https://api.github.com/repos/\(EngineBridge.projectSlug())/releases/latest"
        let text = try httpGet(url, proxy: proxy, max: 1 << 20)
        guard let obj = try? JSONSerialization.jsonObject(with: Data(text.utf8)) as? [String: Any],
              let tag = obj["tag_name"] as? String else { throw UErr("no release answer") }
        let version = tag.hasPrefix("v") ? String(tag.dropFirst()) : tag
        guard let code = UpdateCheck.code(of: version) else { return nil }
        var assets: [String: String] = [:]
        for a in obj["assets"] as? [[String: Any]] ?? [] {
            if let name = a["name"] as? String, let url = a["browser_download_url"] as? String {
                assets[name] = url
            }
        }
        guard let (asset, zipUrl) = assets.first(where: {
            UpdateCheck.isMacAsset($0.key, arch: UpdateCheck.arch)
        }) else { return nil }
        guard let (sumsName, sumsUrl) = assets.first(where: {
            $0.key.hasPrefix("SHA256SUMS") && !$0.key.hasSuffix(".sig")
        }) else {
            EngineBridge.debug("update", "release \(version) has no checksum file")
            return nil
        }
        guard let sigUrl = assets[sumsName + ".sig"] else {
            EngineBridge.debug("update", "release \(version)'s checksums aren't signed")
            return nil
        }
        return Release(version: version, code: code, asset: asset,
                       zipUrl: zipUrl, sumsUrl: sumsUrl, sigUrl: sigUrl)
    }

    // MARK: download and stage

    /// The release's Mac build downloaded, checksum- and signature-checked and unpacked into the
    /// staging dir — afterwards the state is "ready" for it (Android `obtain`).
    private static func obtain(_ r: Release) throws {
        lock.lock()
        let have = s.staged && s.code == r.code
        lock.unlock()
        guard !have else { return }
        lock.lock(); running = "downloading"; lock.unlock()
        defer { lock.lock(); running = nil; lock.unlock() }
        try FileManager.default.createDirectory(atPath: dir, withIntermediateDirectories: true,
                                                attributes: [.posixPermissions: 0o700])
        // The trust decision: the sums' signature vouches for every hash in the file (UpdateTrust).
        let sums = try anyRoute { try httpGet(r.sumsUrl, proxy: $0, max: 256 << 10) }
        let sig = try anyRoute { try httpGet(r.sigUrl, proxy: $0, max: 8 << 10) }
        guard UpdateTrust.verify(payload: Data(sums.utf8), signatureB64: sig) else {
            throw UErr("the update's signature doesn't check out")
        }
        guard let expected = UpdateCheck.checksum(for: r.asset, in: sums) else {
            throw UErr("no checksum for \(r.asset)")
        }
        // The payload itself: a JSON reply can't hold ~50 MB, curl streams it to disk.
        let part = zipPath + ".part"
        try anyRoute { proxy in try curl(r.zipUrl, to: part, proxy: proxy) }
        guard sha256File(part) == expected else { throw UErr("checksum does not match") }
        try stage(part: part, release: r, sha256: expected, sums: sums, sig: sig)
        EngineBridge.debug("update", "downloaded \(r.asset), signature and checksum match")
    }

    /// The downloaded zip is unpacked and the bundle inside is checked against the release it claims
    /// to be — its own version, a whole code seal, every binary the apply names — then it sits as the
    /// staged build with what the apply needs to re-check it.
    private static func stage(part: String, release r: Release, sha256: String, sums: String, sig: String) throws {
        let fm = FileManager.default
        try? fm.removeItem(atPath: unpackDir)
        try fm.createDirectory(atPath: unpackDir, withIntermediateDirectories: true)
        if let problem = run("/usr/bin/ditto", ["-x", "-k", part, unpackDir]) {
            throw UErr("unzip: \(problem)")
        }
        let unpacked = unpackDir + "/" + appName
        let inside = ["Contents/Library/Service/\(ServicePaths.label)",
                      "Contents/Library/Service/borderless-core",
                      "Contents/Library/Service/borderless-limited",
                      "Contents/Library/Service/\(ServicePaths.label).plist",
                      "Contents/Resources/reset-network.sh",
                      "Contents/Resources/regions.json",
                      "Contents/Resources/countries.json"]
        for f in inside where !fm.fileExists(atPath: unpacked + "/" + f) {
            throw UErr("the payload lacks \(f)")
        }
        // The bundle's own version must be the release's — a renamed zip doesn't pass as another build.
        let plist = NSDictionary(contentsOfFile: unpacked + "/Contents/Info.plist")
        guard (plist?["CFBundleShortVersionString"] as? String) == r.version else {
            throw UErr("the build inside is another version")
        }
        guard sealed(unpacked) else { throw UErr("the build's signature is broken") }
        for f in Array(inside.prefix(3)) + ["Contents/MacOS/Border(less)"] {
            guard Signing.cdhash(atPath: unpacked + "/" + f) != nil else { throw UErr("\(f) isn't signed") }
            chmod(unpacked + "/" + f, 0o755)
        }
        chmod(unpacked + "/Contents/Resources/reset-network.sh", 0o755)
        // Kept for the apply's own re-check: the payload, the signed sums and the signature.
        try fm.moveItem(atPath: part, toPath: zipPath)
        try Data(sums.utf8).write(to: URL(fileURLWithPath: sumsPath), options: .atomic)
        try Data(sig.utf8).write(to: URL(fileURLWithPath: sigPath), options: .atomic)
        try? fm.removeItem(atPath: stagedAppPath)
        try fm.moveItem(atPath: unpacked, toPath: stagedAppPath)
        try? fm.removeItem(atPath: unpackDir)
        lock.lock()
        s.staged = true; s.readyAt = nowMs(); s.sha256 = sha256
        saveLocked()
        lock.unlock()
    }

    // MARK: the apply (boot-time; the service is not carrying the tunnel then)

    /// Put the staged build in place — the service binary, the cores, the reset script and assets, the
    /// app bundle, then the launchd plist last of all: a failure anywhere earlier leaves the running
    /// setup whole. nil = done; else what went wrong — kept for the app log, the run stays on this build.
    private static func apply() -> String? {
        let fm = FileManager.default
        // The staged payload is re-checked before anything moves — the whole trust chain again, not
        // just the flag that said it passed once (the staging dir is root-only, but sure is sure).
        guard let sumsData = fm.contents(atPath: sumsPath),
              let sigData = fm.contents(atPath: sigPath),
              let sums = String(data: sumsData, encoding: .utf8), !s.asset.isEmpty else {
            return "the staged signature is missing"
        }
        guard UpdateTrust.verify(payload: sumsData,
                                 signatureB64: String(decoding: sigData, as: UTF8.self)) else {
            return "the staged signature doesn't check out"
        }
        guard let expected = UpdateCheck.checksum(for: s.asset, in: sums),
              sha256File(zipPath) == expected else {
            return "the staged payload changed"
        }
        let app = stagedAppPath
        guard sealed(app) else { return "the staged build's signature is broken" }
        guard let svcHash = Signing.cdhash(atPath: "\(app)/Contents/Library/Service/\(ServicePaths.label)"),
              let coreHash = Signing.cdhash(atPath: "\(app)/Contents/Library/Service/borderless-core"),
              let limHash = Signing.cdhash(atPath: "\(app)/Contents/Library/Service/borderless-limited"),
              let appHash = Signing.cdhash(atPath: app)
        else { return "a staged signature can't be read" }
        // The hash-named paths, like install-service.sh's.
        let bin = ServicePaths.binaryPrefix + String(svcHash.prefix(16))
        let core = ServicePaths.corePrefix + String(coreHash.prefix(16))
        let limited = ServicePaths.limitedPrefix + String(limHash.prefix(16))
        if let e = copy("\(app)/Contents/Library/Service/\(ServicePaths.label)", to: bin, mode: 0o755) { return e }
        if let e = copy("\(app)/Contents/Library/Service/borderless-core", to: core, mode: 0o755) { return e }
        if let e = copy("\(app)/Contents/Library/Service/borderless-limited", to: limited, mode: 0o755) { return e }
        if let e = copy("\(app)/Contents/Resources/reset-network.sh", to: ServicePaths.resetScript, mode: 0o755) { return e }
        try? fm.createDirectory(atPath: EngineBridge.assetsDir, withIntermediateDirectories: true,
                                attributes: [.posixPermissions: 0o700])
        for f in ["regions.json", "countries.json"] {
            if let e = copy("\(app)/Contents/Resources/\(f)", to: EngineBridge.assetsDir + "/\(f)", mode: 0o600) { return e }
        }
        // The app, where it actually lives (learned from its connections; the documented spot as a guess).
        if let target = appTarget() {
            if let e = swapApp(staged: app, to: target) { return e }
            // The new build is the trusted client — recorded only once the bundle sits there: an app
            // the service never saw keeps the old hash, and the old app stays the trusted one.
            if let e = write(appHash + "\n", to: ServicePaths.trustedClient) { return e }
        } else {
            logd("update", "the app's location isn't known: updating the service side only")
        }
        if let e = write(core + "\n", to: ServicePaths.dataDir + "/core") { return e }
        if let e = write(limited + "\n", to: ServicePaths.dataDir + "/limited") { return e }
        return writeLaunchdPlist(program: bin)
    }

    /// The loaded launchd job holds the old binary's path — a plain restart would run the same build.
    /// A one-shot job bootouts this one and bootstraps it back from the plist just written (the install
    /// script's own mechanism: launchd's children carry no app provenance tag). Never returns: the job
    /// ends the run within moments — and if it never comes, the fallback exits anyway: the on-disk
    /// state is already the new build's, the next launchd restart runs it.
    private static func reloadIntoNewBuild() -> Never {
        let job: [String: Any] = [
            "Label": "app.borderless.service-reload",
            "ProgramArguments": ["/bin/sh", "-c",
                "/bin/launchctl bootout \"system/$1\" && /bin/launchctl bootstrap system \"$2\"",
                "reload", ServicePaths.label, ServicePaths.launchdPlist],
            "RunAtLoad": true,
        ]
        if let data = try? PropertyListSerialization.data(fromPropertyList: job, format: .xml, options: 0),
           (try? AtomicFile.save(data, to: reloadPlist)) != nil {
            chmod(reloadPlist, 0o644)
            _ = try? Process.run(URL(fileURLWithPath: "/bin/launchctl"),
                                 arguments: ["bootout", "system/app.borderless.service-reload"])
            // A beat for a stale reload job to leave before this one loads under the same label.
            Thread.sleep(forTimeInterval: 0.3)
            _ = try? Process.run(URL(fileURLWithPath: "/bin/launchctl"),
                                 arguments: ["bootstrap", "system", reloadPlist])
        } else {
            log("update: couldn't schedule the handover; the next service start applies the new build")
        }
        lock.lock()
        s.staged = false; s.approved = false; s.asset = ""; s.sha256 = ""; s.readyAt = 0
        saveLocked()
        lock.unlock()
        // The clean mark first: a bootout landing mid-cleanup must not read as a crash.
        ExitMark.mark()
        cleanPayload()
        // The reload job's bootout ends this run; wait for it rather than racing it into more work.
        DispatchQueue.global().asyncAfter(deadline: .now() + 20) { exit(0) }
        while true { pause() }
    }

    /// The approved build waits for the moment the service can hand over — the tunnel off, nothing
    /// wanted (ruling R2: never a restart while connected; a start does it too, [applyStaged]).
    private static func watchApply() {
        applyWatch?.cancel()
        let t = DispatchSource.makeTimerSource(queue: .global())
        t.schedule(deadline: .now() + 5, repeating: 30)
        t.setEventHandler {
            lock.lock()
            let apply = s.approved && s.staged
            lock.unlock()
            guard apply, let tunnel, tunnel.state == .off, !TunnelController.wanted else { return }
            // The clean exit path (signal handlers do the whole shutdown), then KeepAlive restarts
            // and the next boot's applyStaged() swaps the builds.
            kill(getpid(), SIGTERM)
        }
        t.resume()
        applyWatch = t
    }

    // MARK: the network legs

    /// The via-server leg's SOCKS proxy, parsed out of the engine's "port user pass" answer.
    private struct Proxy {
        var port: Int, user: String, pass: String
        init?(_ creds: String) {
            let f = creds.split(separator: " ")
            guard f.count == 3, let p = Int(f[0]) else { return nil }
            port = p; user = String(f[1]); pass = String(f[2])
        }
    }

    /// [use] directly, else through a working server (Android `Updater.anyRoute`): the engine's
    /// throwaway SOCKS proxy to the next untried candidate, stopped when the round ends.
    private static func anyRoute<T>(_ use: (Proxy?) throws -> T) throws -> T {
        do { return try use(nil) } catch {
            var last = error
            EngineBridge.debug("update", "direct: \(last.localizedDescription)")
            var first = true
            while true {
                guard let proxy = Proxy(EngineBridge.updateProxyStart(reset: first)) else { break }
                first = false
                do {
                    let out = try use(proxy)
                    EngineBridge.updateProxyStop()
                    return out
                } catch { last = error }
            }
            EngineBridge.updateProxyStop()
            throw last
        }
    }

    /// A small GET through the helper — its sockets are bound to the physical interface and it speaks
    /// the via-server SOCKS (Android's `text(url, proxy)`); replies cap at [max] bytes.
    private static func httpGet(_ url: String, proxy: Proxy?, max: Int) throws -> String {
        guard url.hasPrefix("https://") else { throw UErr("not an https address") }
        lock.lock()
        helperId -= 1
        let id = helperId
        lock.unlock()
        var req: [String: Any] = [
            "id": id, "op": "http", "url": url,
            "headers": ["User-Agent": "Border(less)/\(borderlessVersion)",
                        "Accept": "application/vnd.github+json"],
            "connectMs": 15_000, "readMs": 30_000, "follow": true, "max": max,
        ]
        if let proxy { req["socks"] = ["port": proxy.port, "user": proxy.user, "pass": proxy.pass] }
        let data = try JSONSerialization.data(withJSONObject: req)
        let reply = EngineBridge.helperRequest(String(decoding: data, as: UTF8.self))
        guard let obj = try? JSONSerialization.jsonObject(with: Data(reply.utf8)) as? [String: Any] else {
            throw UErr("the helper's answer can't be read")
        }
        if let e = obj["error"] as? String { throw UErr(e) }
        guard let code = obj["code"] as? Int, (200..<300).contains(code) else {
            throw UErr("HTTP \(obj["code"] ?? "?")")
        }
        guard let b64 = obj["body"] as? String, let raw = Data(base64Encoded: b64) else {
            throw UErr("no answer")
        }
        return String(decoding: raw, as: UTF8.self)
    }

    /// The payload: too big for the helper's JSON replies (~10 MB cap, the build is ~50 MB), so curl
    /// streams it to disk — bound to the physical interface like the helper's own sockets, through the
    /// via-server SOCKS when [proxy] is the second leg (`--socks5-hostname`: the name resolves at the
    /// proxy, as it should).
    private static func curl(_ url: String, to path: String, proxy: Proxy?) throws {
        guard url.hasPrefix("https://") else { throw UErr("not an https address") }
        var args = ["-fsSL", "--connect-timeout", "15", "--max-time", "900", "--max-redirs", "5",
                    "--retry", "1", "--proto", "=https", "-A",
                    "Border(less)/\(borderlessVersion)", "-o", path]
        if let proxy {
            args += ["--socks5-hostname", "\(proxy.user):\(proxy.pass)@127.0.0.1:\(proxy.port)"]
        } else if let iface = physical() {
            args += ["--interface", iface]
        }
        args.append(url)
        if let problem = run("/usr/bin/curl", args) { throw UErr(problem) }
        let size = (try? FileManager.default.attributesOfItem(atPath: path)[.size] as? NSNumber)?.int64Value ?? 0
        guard size > 0 else { throw UErr("an empty answer") }
        guard size <= maxPayload else { throw UErr("too big: \(size) bytes") }
    }

    /// The physical interface the tunnel would run over ("en0"…) — the direct leg leaves through it
    /// even while the tunnel is up (all of the service's own traffic does); nil = no usable network.
    private static func physical() -> String? {
        guard let name = tunnel?.network().split(separator: " ").first.map(String.init),
              Interface.isPhysical(name) else { return nil }
        return name
    }

    // MARK: apply's file work

    /// A file into the helpers dir or beside it: copy to `.new`, root:wheel + mode, then an atomic
    /// rename — the install script's rule (never a moment without a binary). The service's own children
    /// are launchd's, so nothing written here carries the app's provenance tag; it is stripped anyway.
    private static func copy(_ src: String, to dest: String, mode: mode_t) -> String? {
        let tmp = dest + ".new"
        unlink(tmp)
        do { try FileManager.default.copyItem(atPath: src, toPath: tmp) } catch {
            return "can't copy \(src): \(error.localizedDescription)"
        }
        chmod(tmp, mode)
        chown(tmp, 0, 0)
        removexattr(tmp, "com.apple.provenance", 0)
        guard rename(tmp, dest) == 0 else {
            let e = errno
            try? FileManager.default.removeItem(atPath: tmp)
            return "can't place \(dest): errno \(e)"
        }
        return nil
    }

    /// The app bundle replaced by the staged one where it lives: the old build moves aside first so a
    /// failed copy puts it back (a retried apply finds the new build already there and skips it);
    /// ditto keeps the bundle's bits — the client-signature xattr among them.
    private static func swapApp(staged: String, to target: String) -> String? {
        guard target.hasSuffix(".app") else { return "the app's location is not a bundle: \(target)" }
        if let have = Signing.cdhash(atPath: target), let want = Signing.cdhash(atPath: staged), have == want {
            return nil
        }
        let backup = target + ".old"
        try? FileManager.default.removeItem(atPath: backup)
        if FileManager.default.fileExists(atPath: target), rename(target, backup) != 0 {
            return "can't move the old app aside: errno \(errno)"
        }
        if let problem = run("/usr/bin/ditto", [staged, target]) {
            _ = rename(backup, target)
            return "can't copy the app into place: \(problem)"
        }
        // The client-signature xattr sits on the bundle root itself — ditto copies the contents, not
        // the container's own attributes, so it is set by hand ([ClientTrust]: a rebuilt app keeps
        // proving itself without a service reinstall).
        var sig = [UInt8](repeating: 0, count: 1024)
        let n = getxattr(staged, ClientTrust.signatureXattr, &sig, sig.count, 0, 0)
        if n > 0 {
            _ = sig.withUnsafeBytes { setxattr(target, ClientTrust.signatureXattr, $0.baseAddress, n, 0, 0) }
        }
        try? FileManager.default.removeItem(atPath: backup)
        return nil
    }

    /// The daemon's launchd entry pointed at the new binary — the staged bundle's own plist, rewritten
    /// the way install-service.sh's `plutil -replace ProgramArguments` does; last of the apply's writes.
    private static func writeLaunchdPlist(program: String) -> String? {
        let src = stagedAppPath + "/Contents/Library/Service/\(ServicePaths.label).plist"
        guard let data = FileManager.default.contents(atPath: src),
              var plist = (try? PropertyListSerialization.propertyList(from: data, format: nil)) as? [String: Any]
        else { return "the staged launchd plist can't be read" }
        plist["ProgramArguments"] = [program]
        plist["ExitTimeOut"] = 60
        guard let out = try? PropertyListSerialization.data(
            fromPropertyList: plist, format: .xml, options: 0)
        else { return "the launchd plist can't be built" }
        do { try AtomicFile.save(out, to: ServicePaths.launchdPlist) } catch {
            return "can't write the launchd plist: \(error.localizedDescription)"
        }
        chmod(ServicePaths.launchdPlist, 0o644)
        chown(ServicePaths.launchdPlist, 0, 0)
        return nil
    }

    /// The bundle the apply should replace: the path the trusted peers keep reporting ([noteAppPath]),
    /// else the documented install spot — trusted only while a `app.borderless.mac` bundle sits there.
    private static func appTarget() -> String? {
        peerLock.lock()
        var path = peerPath
        if path == nil {
            path = (try? String(contentsOfFile: appPathFile, encoding: .utf8))?
                .trimmingCharacters(in: .whitespacesAndNewlines)
            peerPath = path?.isEmpty == false ? path : nil
        }
        peerLock.unlock()
        for candidate in [path, "/Applications/" + appName] {
            guard let candidate, !candidate.isEmpty, candidate.hasSuffix(".app") else { continue }
            let plist = NSDictionary(contentsOfFile: candidate + "/Contents/Info.plist")
            if (plist?["CFBundleIdentifier"] as? String) == "app.borderless.mac" { return candidate }
        }
        return nil
    }

    // MARK: small helpers

    /// A bundle's code seal is whole (`SecStaticCodeCheckValidity` — integrity, not trust: ad-hoc
    /// signatures pass, a broken seal or an unsigned bundle fails).
    private static func sealed(_ path: String) -> Bool {
        var code: SecStaticCode?
        guard SecStaticCodeCreateWithPath(URL(fileURLWithPath: path) as CFURL, [], &code) == errSecSuccess,
              let code else { return false }
        return SecStaticCodeCheckValidity(code, [], nil) == errSecSuccess
    }

    /// The file's SHA-256, lowercase hex — streamed, the payload never sits in memory whole.
    private static func sha256File(_ path: String) -> String? {
        guard let h = FileHandle(forReadingAtPath: path) else { return nil }
        defer { try? h.close() }
        var hasher = SHA256()
        while true {
            let data = h.readData(ofLength: 1 << 20)
            if data.isEmpty { break }
            hasher.update(data: data)
        }
        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }

    /// A short-lived tool — nil on exit 0, else its stderr's first line or the status.
    @discardableResult
    private static func run(_ path: String, _ args: [String]) -> String? {
        /// The stderr the handler drains into (a @Sendable handler may not capture a var).
        final class Collected: @unchecked Sendable {
            let lock = NSLock(); var data = Data()
            func add(_ d: Data) { lock.lock(); data.append(d); lock.unlock() }
        }
        let p = Process()
        let err = Pipe()
        let collected = Collected()
        err.fileHandleForReading.readabilityHandler = { h in collected.add(h.availableData) }
        p.executableURL = URL(fileURLWithPath: path)
        p.arguments = args
        p.standardError = err
        p.standardOutput = FileHandle.nullDevice
        do { try p.run() } catch { return "can't start \(path): \(error.localizedDescription)" }
        p.waitUntilExit()
        err.fileHandleForReading.readabilityHandler = nil
        collected.add(err.fileHandleForReading.readDataToEndOfFile())
        collected.lock.lock()
        let errData = collected.data
        collected.lock.unlock()
        guard p.terminationStatus == 0 else {
            let name = (path as NSString).lastPathComponent
            let why = String(decoding: errData, as: UTF8.self).trimmingCharacters(in: .whitespacesAndNewlines)
            return why.isEmpty ? "\(name) failed (status \(p.terminationStatus))"
                             : "\(name): \(String(why.prefix(200)))"
        }
        return nil
    }

    /// Leftovers of an interrupted previous round (a `.part` download, an unpack half-done, a stale
    /// reload job) — the staged build itself stays: a needed one lives there.
    private static func cleanLeftovers() {
        for f in [zipPath + ".part", unpackDir, reloadPlist] {
            try? FileManager.default.removeItem(atPath: f)
        }
        _ = try? Process.run(URL(fileURLWithPath: "/bin/launchctl"),
                             arguments: ["bootout", "system/app.borderless.service-reload"])
    }

    /// Everything the staging dir holds — the payload, the signature files, the unpacked build — when
    /// the round is over or a different build took its place.
    private static func cleanPayload() {
        for f in [zipPath, zipPath + ".part", sumsPath, sigPath, unpackDir, stagedAppPath, reloadPlist] {
            try? FileManager.default.removeItem(atPath: f)
        }
    }

    /// Helpers of older builds (hash-named, like the install script's own sweep): everything matching
    /// the prefixes with a hex tail is gone except the paths this run and the cores still name — the
    /// reset script's name shares the prefix but is no hash, and non-hex names are left alone. Called at
    /// start, when the running one's own path is known.
    static func cleanHelpers() {
        var keep: Set<String> = [CommandLine.arguments[0]]
        // The launchd plist's own program too: an apply that wrote it but never got to reload must not
        // leave the job pointing at a binary this sweep removes.
        if let data = FileManager.default.contents(atPath: ServicePaths.launchdPlist),
           let plist = (try? PropertyListSerialization.propertyList(from: data, format: nil)) as? [String: Any],
           let argv = plist["ProgramArguments"] as? [String], let program = argv.first {
            keep.insert(program)
        }
        for f in ["core", "limited"] {
            if let p = try? String(contentsOfFile: ServicePaths.dataDir + "/\(f)", encoding: .utf8) {
                keep.insert(p.trimmingCharacters(in: .whitespacesAndNewlines))
            }
        }
        let dir = "/Library/PrivilegedHelperTools"
        guard let names = try? FileManager.default.contentsOfDirectory(atPath: dir) else { return }
        for n in names {
            let p = dir + "/" + n
            for prefix in [ServicePaths.binaryPrefix, ServicePaths.corePrefix, ServicePaths.limitedPrefix]
            where p.hasPrefix(prefix) && !keep.contains(p) {
                let tail = p.dropFirst(prefix.count)
                guard tail.count >= 8, tail.allSatisfy(\.isHexDigit) else { break }
                try? FileManager.default.removeItem(atPath: p)
                break
            }
        }
    }

    private static func loadState() -> UpdateState {
        guard let data = FileManager.default.contents(atPath: statePath),
              let s = try? JSONDecoder().decode(UpdateState.self, from: data) else { return UpdateState() }
        return s
    }

    /// [s] to disk — under [lock]; tmp+fsync+rename, a crash leaves the old file or the new one.
    private static func saveLocked() {
        guard let data = try? JSONEncoder().encode(s) else { return }
        try? AtomicFile.save(data, to: statePath)
    }

    /// The small one-line files ([AtomicFile] like everything else kept); nil on success, the problem else.
    private static func write(_ text: String, to path: String) -> String? {
        do { try AtomicFile.save(Data(text.utf8), to: path); return nil } catch {
            return "can't write \(path): \(error.localizedDescription)"
        }
    }

    /// The automatic-updates switch as the service keeps it (AppSettings' default when unreadable).
    private static func autoUpdate() -> Bool {
        guard let obj = try? JSONSerialization.jsonObject(
            with: Data(EngineBridge.settings().utf8)) as? [String: Any],
              let v = obj["autoUpdate"] as? Bool else { return true }
        return v
    }

    /// The network underneath is metered (the engine's `Status.metered` — the service's own watcher).
    private static func metered() -> Bool {
        guard let obj = try? JSONSerialization.jsonObject(
            with: Data(EngineBridge.status().utf8)) as? [String: Any] else { return false }
        return obj["metered"] as? Bool == true
    }

    private static func nowMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    /// An updater failure with the short text the app shows.
    private struct UErr: Error {
        let text: String
        init(_ t: String) { text = t }
    }
}
