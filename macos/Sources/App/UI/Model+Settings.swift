import AppKit
import ObjectiveC
import Shared
import SwiftUI

// APP-SETTINGS additions that can't live in Model.swift (APP-LIST's file): the live language switch
// (R10) and the danger-styled button the settings' and zone dialogs share.

/// The language override behind [MainModel.applyLanguage]: `Bundle.main` resolves its localizations once —
/// AppleLanguages is read at the first lookup — so a language picked in the running app couldn't reach
/// any text. Once, `localizedString(forKey:value:table:)` is re-pointed at the chosen `<tag>.lproj`
/// bundle: every lookup funnels through that one method (`L`, `Ls`, `P`, `macText` and
/// `String(localized:)` alike), so the next render speaks the picked language. Nothing outside this
/// process is touched.
enum AppLanguage {
    /// Guards `override` and `installed`: the swizzled `localizedString` reads the override on
    /// whatever thread asked for the string, and `apply`'s check-then-set isn't atomic without it.
    private static let lock = NSLock()
    /// The resource bundle lookups resolve against; nil = Bundle.main's own resolution ("System
    /// language"). Read through [overrideBundle] — `localizedString` calls come on any thread.
    private nonisolated(unsafe) static var override: Bundle?
    private nonisolated(unsafe) static var installed = false

    /// The override the swizzled lookup resolves against (see `install`).
    static var overrideBundle: Bundle? {
        lock.lock()
        defer { lock.unlock() }
        return override
    }

    /// Apply a UI language tag ("en", "ru" or "" = the system's) to the running process.
    static func apply(_ tag: String) {
        lock.lock()
        defer { lock.unlock() }
        install()
        override = tag.isEmpty ? nil
            : Bundle.main.path(forResource: tag, ofType: "lproj").flatMap { Bundle(path: $0) }
    }

    /// The one-time method replacement; `apply` calls it holding `lock`.
    private static func install() {
        guard !installed else { return }
        installed = true
        let selector = #selector(Bundle.localizedString(forKey:value:table:))
        guard let method = class_getInstanceMethod(Bundle.self, selector) else { return }
        typealias Impl = @convention(c) (AnyObject, Selector, NSString, NSString?, NSString?) -> NSString
        let original = unsafeBitCast(method_getImplementation(method), to: Impl.self)
        // The chosen .lproj directory answered as a bundle resolves the strings at its root.
        let replacement: @convention(block) (Bundle, NSString, NSString?, NSString?) -> NSString = { this, key, value, table in
            original(AppLanguage.overrideBundle ?? this, selector, key, value, table)
        }
        _ = method_setImplementation(method, imp_implementationWithBlock(replacement))
    }
}

extension MainModel {
    /// A pick in the language row (R10, as on Android: the switch is immediate, not next-launch):
    /// the setting is stored and sent as usual, AppleLanguages keeps the pick for the next start,
    /// the override bundle answers `localizedString` calls from now on, and the palette's re-select
    /// repaints the screens whose bodies observe nothing else of the model — every `L(...)` then
    /// resolves anew. The shared bridge's `Host.language` follows too (its supplier reads the same
    /// AppleLanguages write, `SharedCode.currentLanguage`), so shared-code country names and number
    /// formats switch with the pick; `Locale.current`-based formatting (relative dates) still
    /// follows the launch language — a full swap needs the root view's `.id()` (SEAM).
    func applyLanguage(_ tag: String) {
        settings.language = tag
        UserDefaults.standard.set(tag.isEmpty ? nil : [tag], forKey: "AppleLanguages")
        AppLanguage.apply(tag)
        // Two writes in one pass: every `Palette.*` read is a read of `live`, so its change is the
        // broadest repaint reachable from here — the tree redraws once, texts resolved in the new language.
        let id = PaletteStore.shared.id
        PaletteStore.shared.select(Schemes.all.first { $0.id != id }?.id ?? id)
        PaletteStore.shared.select(id)
    }
}

/// The committing half of a destructive confirm (R4): `MacButton` has no danger face, so the same look
/// is spelled out here with the danger fill where the accent's would be. Used by the settings' and the
/// zone dialog's destructive questions.
struct DangerButton: View {
    var title: String
    var action: () -> Void

    var body: some View {
        Button(action: action) { Text(title).lineLimit(1).padding(.horizontal, 9).frame(minWidth: 26) }
            .buttonStyle(DangerButtonStyle())
    }
}

struct DangerButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(Fonts.body(12.5))
            .foregroundStyle(Palette.onAccent)
            .frame(height: Dims.buttonH)
            .background(RoundedRectangle(cornerRadius: Dims.cornerRadius).fill(Palette.danger))
            .overlay(RoundedRectangle(cornerRadius: Dims.cornerRadius).strokeBorder(Palette.danger.opacity(0.35), lineWidth: 1))
            .shadow(color: .black.opacity(0.05), radius: 0.5, y: 0.5)
            .brightness(configuration.isPressed ? -0.05 : 0)
            .contentShape(RoundedRectangle(cornerRadius: Dims.cornerRadius))
    }
}
