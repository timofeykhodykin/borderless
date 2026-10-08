# Third-party components

Border(less)'s own code is licensed under the [PolyForm Noncommercial License 1.0.0](LICENSE). The components
below are not: each keeps its own license, and nothing in Border(less)'s license limits the rights those licenses
give you.

| Component | Used for | License | Source |
|---|---|---|---|
| Xray-core | the network core (inside libv2ray) | MPL-2.0 | <https://github.com/XTLS/Xray-core> |
| AndroidLibXrayLite (libv2ray) | Xray-core packaged for Android, downloaded at build time | LGPL-3.0 | <https://github.com/2dust/AndroidLibXrayLite> |
| Routing lists (`app/src/main/assets/geoip.dat`, `geosite.dat`) | categories of sites and addresses, trimmed copies | GPL-3.0 | the sources named in `app/src/main/assets/regions.json` |
| PT Serif, PT Sans | fonts | SIL Open Font License 1.1 ([docs/licenses/OFL-1.1.txt](docs/licenses/OFL-1.1.txt)) | ParaType |
| Flag images | country flags | public domain | <https://flagcdn.com> |
| ZXing, zxing-android-embedded | QR codes | Apache-2.0 | <https://github.com/zxing/zxing>, <https://github.com/journeyapps/zxing-android-embedded> |
| AndroidX, Jetpack Compose, Kotlin, kotlinx libraries | the app framework | Apache-2.0 | <https://developer.android.com/jetpack>, <https://kotlinlang.org> |
| Heron silhouette (app icon, logo) | by Mathieu Pélissié, recoloured and mirrored | CC BY 4.0 | <https://www.phylopic.org/images/5d6a2703-c087-4f58-a14d-0bbc55131100> |

**libv2ray (LGPL-3.0).** It is a separate library in the APK (`libgojni.so` with its Java bindings). You may
replace it with your own build of the same interface, modify it, and reverse-engineer the app as far as needed to
debug such changes; Border(less)'s license does not restrict this.

**Xray-core (MPL-2.0).** The source of the exact version used is published by its authors at the address above
(the version is shown in the app's settings).

**Routing lists (GPL-3.0).** The bundled files are subsets of the lists published by their sources; the full
lists, their history and their license are available there. The app reads them as data.
