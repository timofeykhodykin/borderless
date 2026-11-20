import Foundation

/// The timing rule of the menu bar item's click fix (App `MenuBarClickFix`), kept here so `Checks` can test it.
public enum MenuBarClick {
    /// The dropdown is closed, and it closed right as this press started: the press is what closed it, so it mustn't
    /// also count as a click that opens it again.
    public static func closedWithPress(pressAt: TimeInterval, closedAt: TimeInterval, dropdownVisible: Bool) -> Bool {
        !dropdownVisible && closedAt >= pressAt - 0.05 && closedAt <= pressAt + 0.5
    }
}
