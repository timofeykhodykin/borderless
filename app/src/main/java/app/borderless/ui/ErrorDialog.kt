package app.borderless.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.borderless.R
import app.borderless.data.Errors
import app.borderless.ui.theme.Palette

/**
 * Shows queued [Errors] one at a time: what went wrong in plain words, with a button that copies
 * the full report (version, device, place, stack trace) for sending to the developer.
 */
@Composable
fun ErrorDialog() {
    val pending by Errors.pending.collectAsStateWithLifecycle()
    val report = pending.firstOrNull() ?: return
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = { Errors.dismiss(report.id) },
        containerColor = Palette.background,
        title = { Text(stringResource(if (report.crash) R.string.err_crash_title else R.string.err_title)) },
        text = {
            Text(
                report.message, fontSize = 14.sp, color = Palette.textSecondary,
                modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = { TextButton({ Errors.dismiss(report.id) }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton({ copy(context, report.details) }) { Text(stringResource(R.string.err_copy)) } },
    )
}

private fun copy(context: Context, text: String) {
    runCatching {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Border(less) error", text))
        // Android 13+ confirms copying itself.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
    }
}
