# Adding a language

Everything the app shows comes from string resources; nothing else needs to change in the code.

1. Copy `app/src/main/res/values/strings.xml` (English, the default) to
   `app/src/main/res/values-<code>/strings.xml`, e.g. `values-de` (German) or `values-pt-rBR` (Portuguese, Brazil).
2. Translate the texts. Keep the placeholders (`%1$s`, `%d`, `%%`) and the `name` attributes as they are; plurals
   (`<plurals>`) need the quantities your language uses (`one`, `few`, `many`, `other`…).
3. `country_short_names` (a string array) lists shorter country names your language prefers to the system's
   (`US=USA`); leave it empty if there are none. Other country names come from Android itself.
4. Build. The language appears by itself: in the APK, in Android's per-app language list and in
   Settings → Interface → Language (the build reads the `values-*` folders, see `uiLanguages` in
   `app/build.gradle.kts`).

Dates, numbers and the log follow the chosen language too: events in the log are stored without a language and
shown in the current one.
