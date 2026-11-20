import Foundation
import BorderlessShared
import Shared

/// SVC-DAEMON's checks: the parts of the daemon fixes that live in Shared ([ZoneFile.Parsed.declaresSections])
/// — the service-side parts (ExitMark, the metered watch, serviceDns) are not visible to this target
/// (Checks links Shared only; `ExitMark.take()`'s read-then-remove ordering in particular can't be
/// exercised here — it lives in the Service target).
func svcDaemonChecks() {
    check("declaresSections: only a […] header sets it") {
        expect(ZoneFile.parse("example.com\nexample.org").declaresSections == false, "a bare list declares nothing")
        expect(ZoneFile.parse("name: Mine\nexample.com").declaresSections == false, "name alone is no section")
        expect(ZoneFile.parse("[direct]\nexample.com").declaresSections == true, "a section header")
        expect(ZoneFile.parse("[server]\nexample.com\n[apps]\ncom.example.app").declaresSections == true,
               "several headers")
    }
}
