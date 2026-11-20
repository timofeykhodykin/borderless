package app.borderless.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import app.borderless.ui.AppAlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.borderless.BuildConfig
import app.borderless.R
import app.borderless.core.Updater
import app.borderless.data.Errors
import app.borderless.ui.theme.Palette

/**
 * Shows queued [Errors] one at a time: what went wrong in plain words, with a button that copies
 * the full report (version, device, place, stack trace) for sending to the developer. A failure that points
 * at a bug in the app (one kept for "Errors to report") also asks the user to report it on the project's page:
 * the button copies the details and opens a new issue with the short text filled in.
 */
@Composable
fun ErrorDialog() {
    val pending by Errors.pending.collectAsStateWithLifecycle()
    val report = pending.firstOrNull() ?: return
    val context = LocalContext.current
    AppAlertDialog(
        onDismissRequest = { Errors.dismiss(report.id) },
        containerColor = Palette.background,
        title = { Text(stringResource(if (report.crash) R.string.err_crash_title else R.string.err_title)) },
        text = {
            Column {
                Text(
                    report.message, fontSize = 14.sp, color = Palette.textSecondary,
                    modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                )
                if (report.bug) {
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.err_report_please), fontSize = 14.sp, color = Palette.textSecondary)
                    Spacer(Modifier.height(2.dp))
                    TextButton(
                        onClick = { reportOnGithub(context, report) },
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 6.dp),
                    ) { Text(stringResource(R.string.err_report_github), fontWeight = FontWeight.Bold) }
                }
            }
        },
        confirmButton = { TextButton({ Errors.dismiss(report.id) }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton({ copy(context, report.details) }) { Text(stringResource(R.string.err_copy)) } },
    )
}

/** The details go to the clipboard, the browser opens a new issue with the short text filled in. */
private fun reportOnGithub(context: Context, report: Errors.Report) {
    copy(context, report.details)
    val title = context.getString(
        R.string.err_report_title, BuildConfig.VERSION_NAME,
        report.message.lineSequence().firstOrNull().orEmpty().take(70),
    )
    val body = context.getString(R.string.err_report_body, report.message.take(2000))
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Updater.issuesUrl(title, body))))
    }.onFailure {
        Toast.makeText(context, R.string.err_no_app, Toast.LENGTH_SHORT).show()
    }
    Errors.dismiss(report.id)
}

private fun copy(context: Context, text: String) {
    runCatching {
        copySensitive(context, "Border(less) error", text)
        // Android 13+ confirms copying itself.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
    }
}
