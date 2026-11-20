import CryptoKit
import Foundation
import Shared

/// The updater's pure parts (`Shared/Service.swift`: UpdateCheck / UpdateTrust / UpdateState): the version
/// comparison (Android `Updater.codeOf`), the Mac asset's name (its `isApk`), the sums file's line, the
/// Ed25519 verify, the wire's state word and the staged-state file's round trip. The lead registers
/// `updateChecks()` in `main.swift` — not edited here.
func updateChecks() {
    check("update: the version code is Android's") {
        expect(UpdateCheck.code(of: "3.1.0") == 3_010_099, "3.1.0 → 3010099 (its release's versionCode)")
        expect(UpdateCheck.code(of: "0.2.0") == 20_099, "0.2.0 → 20099")
        expect(UpdateCheck.code(of: "10.20.30") == 10_203_099, "10.20.30 → 10203099")
        expect(UpdateCheck.code(of: "4.0.0-beta.1") == nil, "a beta never compares")
        expect(UpdateCheck.code(of: "v3.1.0") == nil, "the tag's v is stripped before, not inside")
        expect(UpdateCheck.code(of: "1.2.3.4") == nil, "four parts")
        expect(UpdateCheck.code(of: "1.2") == nil, "two parts")
        expect(UpdateCheck.code(of: "1.2.x") == nil, "not numeric")
        expect(UpdateCheck.code(of: "1.2.3.") == nil, "trailing dot")
        expect(UpdateCheck.code(of: "") == nil, "empty")
    }

    check("update: the Mac asset's name") {
        expect(UpdateCheck.isMacAsset("borderless-4.0.0-macos-arm64.zip", arch: "arm64"),
               "the release's own name")
        expect(!UpdateCheck.isMacAsset("borderless-4.0.0-macos-x86_64.zip", arch: "arm64"),
               "the other processor's build isn't taken")
        expect(!UpdateCheck.isMacAsset("borderless-4.0.0-arm64-v8a.apk", arch: "arm64"),
               "the Android build isn't a Mac one")
        expect(!UpdateCheck.isMacAsset("SHA256SUMS-4.0.0.txt", arch: "arm64"), "the sums aren't a payload")
        expect(!UpdateCheck.isMacAsset("../../etc/passwd", arch: "arm64"), "a path is never an asset name")
        expect(!UpdateCheck.isMacAsset("borderless-4.0.0-macos-arm64.zip.sig", arch: "arm64"),
               "a signature isn't a payload")
        expect(UpdateCheck.isMacAsset("borderless-4.0.0-macos-x86_64.zip", arch: "x86_64"),
               "x86_64 names its own")
    }

    check("update: the sums file's lines") {
        let sums = """
        4d7f1901f4be0d6641e56bf621cd8f2e4f98684804bd7ba4833945ed985283f6  borderless-4.0.0-arm64-v8a.apk
        46845fc51a84a37ec2b3dccf9460636c31f972cb098216a32994df3fef85ecb5 *borderless-4.0.0-macos-arm64.zip
        92a20704baefea352b25c96da036a5a9cbc0c30cda50edeedafa001686ad1436  borderless-4.0.0-macos-x86_64.zip

        """
        expect(UpdateCheck.checksum(for: "borderless-4.0.0-macos-arm64.zip", in: sums)
            == "46845fc51a84a37ec2b3dccf9460636c31f972cb098216a32994df3fef85ecb5",
               "the binary-mode line, '*' stripped")
        expect(UpdateCheck.checksum(for: "borderless-4.0.0-arm64-v8a.apk", in: sums)
            == "4d7f1901f4be0d6641e56bf621cd8f2e4f98684804bd7ba4833945ed985283f6",
               "the two-space line")
        expect(UpdateCheck.checksum(for: "borderless-4.0.0-macos-arm64.zip",
                                    in: "not-a-sum  borderless-4.0.0-macos-arm64.zip\n") == nil,
               "a line without a hash isn't one")
        expect(UpdateCheck.checksum(for: "borderless-9.9.9-macos-arm64.zip", in: sums) == nil,
               "an asset not listed")
    }

    check("update: the Ed25519 signature") {
        // A throwaway pair — the real public half can't be tested without its private half, which is
        // deliberately not in the repo.
        let key = Curve25519.Signing.PrivateKey()
        let pub = key.publicKey.rawRepresentation.base64EncodedString()
        let payload = Data("sums file contents".utf8)
        let sig = try! key.signature(for: payload).base64EncodedString()
        expect(UpdateTrust.verify(payload: payload, signatureB64: sig, key: pub), "a real signature passes")
        expect(!UpdateTrust.verify(payload: Data("other".utf8), signatureB64: sig, key: pub),
               "signed over something else fails")
        expect(!UpdateTrust.verify(payload: payload, signatureB64: "AAAA", key: pub), "garbage fails")
        expect(!UpdateTrust.verify(payload: payload, signatureB64: sig,
                                   key: Curve25519.Signing.PrivateKey().publicKey.rawRepresentation
                                       .base64EncodedString()),
               "another key fails")
        expect(UpdateTrust.publicKey.count > 40,
               "the pinned public half is set (the private half never leaves the developer's machine)")
    }

    check("update: the wire's state word") {
        var s = UpdateState()
        expect(UpdateCheck.wireState(running: nil, lastError: nil, s) == "idle", "nothing → idle")
        s.code = 40099; s.version = "4.0.0"
        expect(UpdateCheck.wireState(running: nil, lastError: nil, s) == "available",
               "an offer → available")
        expect(UpdateCheck.wireState(running: "checking", lastError: nil, s) == "checking",
               "the running op wins")
        s.dismissed = 40099
        expect(UpdateCheck.wireState(running: nil, lastError: nil, s) == "idle",
               "a dismissed offer is hidden")
        s.dismissed = 0; s.staged = true
        expect(UpdateCheck.wireState(running: nil, lastError: nil, s) == "ready",
               "staged → ready")
        s.dismissed = 40099
        expect(UpdateCheck.wireState(running: nil, lastError: nil, s) == "idle",
               "staged but dismissed stays hidden")
        s.dismissed = 0; s.approved = true
        expect(UpdateCheck.wireState(running: nil, lastError: nil, s) == "installing",
               "approved → installing until the next start")
        s.approved = false; s.staged = false; s.code = 0; s.version = ""
        expect(UpdateCheck.wireState(running: nil, lastError: "HTTP 404", s) == "error",
               "a kept failure → error")
        s.code = 40099
        expect(UpdateCheck.wireState(running: nil, lastError: "HTTP 404", s) == "error",
               "a failure outranks an offer")
    }

    check("update: the kept state reads back, tolerantly") {
        var s = UpdateState()
        s.version = "4.0.0"; s.code = 40099; s.asset = "borderless-4.0.0-macos-arm64.zip"
        s.zipUrl = "https://example.com/z"; s.sumsUrl = "https://example.com/s"; s.sigUrl = "https://example.com/g"
        s.checkedAt = 1_700_000_000_000; s.foundAt = 1_600_000_000_000; s.readyAt = 1_700_000_000_100
        s.sha256 = "aa"; s.staged = true; s.approved = true; s.dismissed = 30099
        s.lastCode = 3010099; s.applyError = "boom"
        let data = try! JSONEncoder().encode(s)
        expect(try! JSONDecoder().decode(UpdateState.self, from: data) == s, "a full round trip")
        // An older file (fewer fields) decodes with defaults instead of failing.
        let partial = Data(#"{"version":"3.1.0","code":3010099}"#.utf8)
        let old = try! JSONDecoder().decode(UpdateState.self, from: partial)
        expect(old.version == "3.1.0" && old.code == 3_010_099 && !old.staged && old.dismissed == 0,
               "missing fields read as defaults")
        // And a file that isn't the state at all must not crash the boot — callers fall back to empty.
        expect((try? JSONDecoder().decode(UpdateState.self, from: Data("junk".utf8))) == nil,
               "a corrupt file doesn't decode")
    }
}
