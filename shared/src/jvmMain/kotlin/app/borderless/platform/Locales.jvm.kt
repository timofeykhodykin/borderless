package app.borderless.platform

import java.util.Locale

actual object Locales {
    actual fun isoCountries(): Set<String> = Locale.getISOCountries().toSet()

    actual fun countryName(code: String, language: String): String =
        Locale.Builder().setRegion(code).build().getDisplayCountry(Locale.forLanguageTag(language))

    actual fun sorted(items: List<String>, language: String): List<String> =
        items.sortedWith(java.text.Collator.getInstance(Locale.forLanguageTag(language)))

    actual fun integer(n: Long, language: String): String =
        java.text.NumberFormat.getIntegerInstance(Locale.forLanguageTag(language)).format(n)

    actual fun decimal(value: Double, digits: Int, language: String): String =
        String.format(Locale.forLanguageTag(language), "%.${digits}f", value)
}
