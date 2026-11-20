package app.borderless.platform

import platform.Foundation.NSLocale
import platform.Foundation.NSMakeRange
import platform.Foundation.NSNumber
import platform.Foundation.NSNumberFormatter
import platform.Foundation.NSNumberFormatterDecimalStyle
import platform.Foundation.NSNumberFormatterRoundHalfUp
import platform.Foundation.NSString
import platform.Foundation.ISOCountryCodes
import platform.Foundation.compare
import platform.Foundation.create
import platform.Foundation.localizedStringForCountryCode

@OptIn(kotlinx.cinterop.BetaInteropApi::class, kotlinx.cinterop.ExperimentalForeignApi::class)
actual object Locales {
    private val codes: Set<String> by lazy { NSLocale.ISOCountryCodes.map { it as String }.toSet() }
    private val locales = LockedMap<String, NSLocale>()

    private fun locale(language: String) = locales.getOrPut(language) { NSLocale(localeIdentifier = language) }

    actual fun isoCountries(): Set<String> = codes

    actual fun countryName(code: String, language: String): String =
        locale(language).localizedStringForCountryCode(code) ?: ""

    actual fun sorted(items: List<String>, language: String): List<String> =
        // compare(_:options:range:locale:) collates by the given locale (localizedCompare would take the
        // system's, ignoring [language] — e.g. the service sorting names for a different UI language).
        items.sortedWith { a, b ->
            val s = NSString.create(string = a)
            s.compare(b, options = 0uL, range = NSMakeRange(0uL, s.length), locale = locale(language)).toInt()
        }

    private fun formatter(language: String, digits: Int) = NSNumberFormatter().apply {
        locale = locale(language)
        numberStyle = NSNumberFormatterDecimalStyle
        roundingMode = NSNumberFormatterRoundHalfUp
        minimumFractionDigits = digits.toULong()
        maximumFractionDigits = digits.toULong()
        usesGroupingSeparator = digits == 0
    }

    actual fun integer(n: Long, language: String): String =
        formatter(language, 0).stringFromNumber(NSNumber(longLong = n)) ?: n.toString()

    actual fun decimal(value: Double, digits: Int, language: String): String =
        formatter(language, digits).stringFromNumber(NSNumber(double = value)) ?: fixed(value, digits)
}
