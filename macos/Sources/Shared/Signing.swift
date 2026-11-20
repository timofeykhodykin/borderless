import Foundation
import Security

/// The code-directory hash of signed code, hex — the identity the install script stamps into daemon paths
/// (`install-service.sh`'s `cdhash()`), [StaleApp] compares the running image against the bundle on disk by,
/// and `ServiceClient.sameServiceBuild` matches the installed daemon's name against.
///
/// The service's `PeerCheck.cdhash` (Sources/Service/ControlSocket.swift) reaches the same hash through an
/// audit token and validates the peer's code first — its tail calls [cdhash(of:)], so the read is written once.
public enum Signing {
    /// The code-directory hash (hex) of [code], nil when its signing info can't be read.
    public static func cdhash(of code: SecStaticCode) -> String? {
        var info: CFDictionary?
        guard SecCodeCopySigningInformation(code, [], &info) == errSecSuccess,
              let dict = info as? [String: Any],
              let unique = dict[kSecCodeInfoUnique as String] as? Data else { return nil }
        return unique.map { String(format: "%02x", $0) }.joined()
    }

    /// The code-directory hash (hex) of the file or bundle at [path], nil when unsigned or unreadable.
    public static func cdhash(atPath path: String) -> String? {
        var staticCode: SecStaticCode?
        guard SecStaticCodeCreateWithPath(URL(fileURLWithPath: path) as CFURL, [], &staticCode) == errSecSuccess,
              let staticCode else { return nil }
        return cdhash(of: staticCode)
    }
}
