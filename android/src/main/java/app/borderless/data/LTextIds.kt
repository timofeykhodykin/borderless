package app.borderless.data

import androidx.annotation.StringRes
import app.borderless.Res

/** [LText.of] by an Android string resource (the shared code names it, see [app.borderless.S]). */
fun LText.Companion.of(@StringRes id: Int, vararg args: Any): LText = of(Res.name(id), *args)
