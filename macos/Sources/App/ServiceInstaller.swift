import AppKit
import Foundation

/// Installs and removes the system service with the scripts inside the app bundle (`Contents/Resources`), run with
/// administrator privileges through the system's own password dialog. The scripts take their paths from the bundle,
/// so the app passes nothing but its own location; AppleScript's `quoted form of` does the shell quoting.
@MainActor
enum ServiceInstaller {
    enum Outcome: Equatable {
        case done
        /// The user closed the password dialog.
        case cancelled
        case failed(String)
    }

    static func install() async -> Outcome {
        await run(script: "install-service.sh", arguments: [Bundle.main.bundlePath])
    }

    /// The reset that works without the service (Support/reset-network.sh): for when it can't be asked.
    static func resetNetwork() async -> Outcome {
        await run(script: "reset-network.sh", arguments: [])
    }

    static func remove(withData: Bool) async -> Outcome {
        await run(script: "uninstall-service.sh", arguments: withData ? ["with-data"] : [])
    }

    private static func run(script: String, arguments: [String]) async -> Outcome {
        guard let path = Bundle.main.path(forResource: script, ofType: nil) else {
            return .failed("\(script) is missing from the app")
        }
        let quoted = ([path] + arguments).map { "quoted form of \"\(appleScriptString($0))\"" }
        let command = "do shell script \"/bin/sh \" & " + quoted.joined(separator: " & \" \" & ")
            + " with administrator privileges"
        // executeAndReturnError blocks for the whole system password dialog, so it runs off the main
        // actor: on it, the app — and the install button's just-drawn spinner — froze behind the prompt.
        return await Task.detached { () -> Outcome in
            // A nil script object is a compile error — nothing ran, so this can't be `.done`.
            guard let compiled = NSAppleScript(source: command) else {
                return .failed("the command for \(script) wouldn't compile as AppleScript")
            }
            var error: NSDictionary?
            _ = compiled.executeAndReturnError(&error)
            guard let error else { return .done }
            // -128: "User canceled."
            if (error[NSAppleScript.errorNumber] as? Int) == -128 { return .cancelled }
            // The system's AppleScript error comes in English and reads like a crash log: a friendly
            // lead for the eye, the raw detail kept after it for the log and for copying.
            let detail = error[NSAppleScript.errorMessage] as? String ?? "\(error)"
            return .failed(macText("mac.script.error", "Something went wrong while macOS ran the script:") + " " + detail)
        }.value
    }

    /// A string literal for AppleScript source: backslashes and quotes escaped.
    private static func appleScriptString(_ s: String) -> String {
        s.replacingOccurrences(of: "\\", with: "\\\\").replacingOccurrences(of: "\"", with: "\\\"")
    }
}
