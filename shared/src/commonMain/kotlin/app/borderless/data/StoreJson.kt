package app.borderless.data

import kotlinx.serialization.json.Json

/** How data files are read and written: unknown keys ignored, defaults left out, bad enum values → defaults. */
val StoreJson = Json { ignoreUnknownKeys = true; encodeDefaults = false; coerceInputValues = true }
