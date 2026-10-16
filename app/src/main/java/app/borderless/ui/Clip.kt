package app.borderless.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle

/**
 * Copies [text] marked as sensitive (SECURITY.md S14): Android 13+ hides it in the copy preview, keyboards with a
 * clipboard history (Samsung, Gboard) don't keep or show it. Links and subscription addresses are accounts.
 */
fun copySensitive(context: Context, label: String, text: String) {
    val clip = ClipData.newPlainText(label, text)
    clip.description.extras = PersistableBundle().apply {
        putBoolean(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) ClipDescription.EXTRA_IS_SENSITIVE else "android.content.extra.IS_SENSITIVE", true)
    }
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
}
