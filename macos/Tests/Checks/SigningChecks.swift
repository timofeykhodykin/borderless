import Foundation
import Security
import Shared

/// [Signing.cdhash]: the code-directory hash the install script stamps into daemon paths and `sameServiceBuild`
/// compares (added to `Shared` alongside the service's own `PeerCheck.cdhash`, which stays service-side).
func signingChecks() {
    check("a signed binary has a cdhash, an unsigned file none") {
        let ls = Signing.cdhash(atPath: "/bin/ls") ?? ""
        expect(ls.count >= 40 && ls.allSatisfy(\.isHexDigit), "/bin/ls is platform-signed: \(ls)")
        let tmp = NSTemporaryDirectory() + "borderless-checks-\(UUID().uuidString)"
        try Data("unsigned".utf8).write(to: URL(fileURLWithPath: tmp))
        defer { try? FileManager.default.removeItem(atPath: tmp) }
        expect(Signing.cdhash(atPath: tmp) == nil, "a plain file has no signing info")
        expect(Signing.cdhash(atPath: "/nonexistent-path") == nil, "a missing file has none either")
    }
}
