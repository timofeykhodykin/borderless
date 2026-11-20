import Foundation
import Shared

/// The Mac energy meter's pure Swift parts (ENERGY-IMPL): [ResourceTrack]'s pid-keyed accumulation
/// (restart folds, recycled pids, never-negative counters) and the lenient `ActivityOut` decode the
/// activity tab builds on. `proc_pid_rusage` itself is compile-only — its fields were verified
/// against the SDK headers; live readings need the installed service.
func energyChecks() {
    check("resource track: monotonic sums over changing pids") {
        var t = ResourceTrack()
        t.update([10: .init(start: 1, cpuMs: 100, nj: 1_000, fbKb: 500)])
        expect(t.cpuMs == 0 && t.nj == 0 && t.fbKb == 500, "a fresh pid starts at its own counters")
        t.update([10: .init(start: 1, cpuMs: 160, nj: 2_500, fbKb: 700)])
        expect(t.cpuMs == 60 && t.nj == 1_500 && t.fbKb == 700, "the delta lands in the role")
        // A second live pid adds its own.
        t.update([10: .init(start: 1, cpuMs: 200, nj: 3_000, fbKb: 700),
                  20: .init(start: 5, cpuMs: 40, nj: 0, fbKb: 300)])
        expect(t.cpuMs == 100 && t.nj == 2_000 && t.fbKb == 1_000, "both live pids count")
    }

    check("resource track: a dead pid folds into the total, never lost") {
        var t = ResourceTrack()
        t.update([10: .init(start: 1, cpuMs: 100, nj: 500, fbKb: 500)])
        t.update([10: .init(start: 1, cpuMs: 300, nj: 900, fbKb: 500)])
        t.update([:]) // gone
        expect(t.cpuMs == 200 && t.nj == 400 && t.fbKb == 0, "its delta stays counted")
        // A replacement with fresh counters adds on top — no decrease, no lost work.
        t.update([11: .init(start: 2, cpuMs: 50, nj: 100, fbKb: 600)])
        expect(t.cpuMs == 200 && t.nj == 400, "a fresh pid starts at its own counters")
        t.update([11: .init(start: 2, cpuMs: 130, nj: 300, fbKb: 600)])
        expect(t.cpuMs == 280 && t.nj == 600 && t.fbKb == 600, "the replacement's delta adds")
    }

    check("resource track: a recycled pid starts fresh, a counter reset reads as no change") {
        var t = ResourceTrack()
        t.update([10: .init(start: 1, cpuMs: 500, nj: 2_000, fbKb: 500)])
        // Same pid, new start stamp (the number was recycled): the old contribution folds away.
        t.update([10: .init(start: 9, cpuMs: 30, nj: 100, fbKb: 400)])
        expect(t.cpuMs == 0 && t.fbKb == 400, "the recycled pid doesn't inherit the old counters")
        // A backwards-reading counter is no change, never negative.
        t.update([10: .init(start: 9, cpuMs: 10, nj: 50, fbKb: 400)])
        expect(t.cpuMs == 0 && t.nj == 0, "a reset reads as zero, not negative")
    }

    check("activity reply: the lenient decode") {
        let full = """
        {"from":1000,"to":961000,"dataStart":500,
         "totals":{"CORE_BASE":{"n":1,"bytes":0,"radioMs":0,"cpuMs":5000,"mj":12000}},
         "groups":{"PING":12,"PROBE":40,"SCAN":3},"pingsRide":7,"wakeups":9,"skipped":2,
         "buckets":[{"t":2000,"group":"PING","n":5}],
         "power":[{"ts":2000,"cpuMs":100},{"ts":3000,"cpuMs":200}],
         "summary":{"hours":0.25,"cpuSeconds":10.0,"screenOff":0.1,"metered":0.2,"saver":0.0,
                    "charging":0.3,"ecoHours":0.05,"watts":1.4,"avgMemoryMb":74.0,"maxMemoryMb":96.0},
         "parts":[{"part":"TRAFFIC","label":"act_core_base","mj":12000,"share":0.9}],
         "energy":[{"t":2000,"coveredMs":60000,"mj":{"TRAFFIC":40.0,"CONNECTION":5.0}}],
         "measuredEnergy":true}
        """
        let out = try! JSONDecoder().decode(ActivityOut.self, from: Data(full.utf8))
        expect(!out.isEmpty, "a populated payload isn't empty")
        expect(out.totals["CORE_BASE"]?.cpuMs == 5000, "totals carry the rows")
        expect(out.groups["PING"] == 12 && out.pingsRide == 7 && out.skipped == 2, "the tiles' figures")
        expect(out.buckets.count == 1 && out.power.count == 2, "the charts' rows")
        expect(out.summary.watts == 1.4 && out.summary.hours == 0.25, "the summary")
        expect(out.partLabels["TRAFFIC"] == "act_core_base", "the part's strings key")
        expect(out.energy.first?.mj["TRAFFIC"] == 40.0 && out.measuredEnergy, "the energy buckets")

        // Every optional field may be missing (an older or newer service): only from/to/summary count.
        let sparse = try! JSONDecoder().decode(ActivityOut.self, from: Data(#"{"from":1,"to":2,"summary":{}}"#.utf8))
        expect(sparse.isEmpty, "an empty payload reads as empty — the tab's act_no_data")
        expect(sparse.dataStart == 2 && !sparse.measuredEnergy, "missing fields read as defaults")
        // The service's own "statistics failed" answer decodes nothing at all.
        expect((try? JSONDecoder().decode(ActivityOut.self, from: Data("{}".utf8))) == nil, "{} fails the decode")
    }
}
