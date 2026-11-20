package app.borderless.core

import app.borderless.data.AppSettings
import app.borderless.data.Countries
import app.borderless.data.Repo
import app.borderless.data.Strategy

/**
 * Servers of chosen countries only ([AppSettings.countries]). Every change goes through [set], in one settings
 * update with what it implies for the strategy, so the engine sees them together:
 *
 * - The strategy in effect keeps working inside the countries' servers (Stable / Fastest optimise among them,
 *   On failure stays until a failure).
 * - Manual whose hand-picked server is outside the countries can't be followed: the default strategy (an automatic
 *   one) stands in while the filter is on, and Manual with its server comes back when it ends — unless the user
 *   picked a server by hand meanwhile (that pick is the new Manual).
 * - The server in use when the filter starts is remembered: On failure goes back to it when the filter ends.
 */
object CountryFilter {
    fun set(countries: Set<String>) {
        Repo.updateSettings { s -> apply(s, countries) }
        // Any country again: auto-visibility takes over with a fresh pool.
        if (countries.isEmpty()) AutoPool.refresh()
    }

    fun toggle(code: String) = Repo.settings.value.countries.let { set(if (code in it) it - code else it + code) }

    /** Pure part: the settings after choosing [countries]. */
    fun apply(s: AppSettings, countries: Set<String>): AppSettings {
        if (s.countries == countries) return s
        if (countries.isEmpty()) {
            // Manual comes back only if the stand-in is still in effect (the user didn't choose another strategy).
            val back = s.strategyBeforeCountry?.takeIf { s.strategy == standIn(s) }
            return s.copy(countries = countries, strategy = back ?: s.strategy, strategyBeforeCountry = null)
        }
        var n = s.copy(countries = countries)
        if (s.countries.isEmpty()) n = n.copy(serverBeforeCountry = s.lastServerId)
        val manual = Repo.server(s.manualServerId)
        if (n.strategy == Strategy.MANUAL && (manual == null || Countries.of(manual) !in countries)) {
            n = n.copy(strategy = standIn(s), strategyBeforeCountry = s.strategyBeforeCountry ?: Strategy.MANUAL)
        }
        return n
    }

    /** What stands in for Manual: the default strategy, or Stable if that is Manual too. */
    private fun standIn(s: AppSettings) = s.defaultStrategy.takeIf { it != Strategy.MANUAL } ?: Strategy.STABLE
}
