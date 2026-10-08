package app.borderless.ui.settings

import app.borderless.data.Errors
import app.borderless.ui.stats.SeriesColors
import android.content.Context
import app.borderless.R
import androidx.compose.ui.res.stringResource
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.borderless.BuildConfig
import app.borderless.core.CoreEnv
import app.borderless.data.AppLog
import app.borderless.data.Repo
import app.borderless.ui.TabPages
import app.borderless.ui.TabStrip
import app.borderless.ui.rememberTabs
import app.borderless.ui.theme.Palette
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun LogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    // Events / detailed: tap a tab or swipe sideways (as on the statistics screen).
    val tabs = rememberTabs(2)
    var confirmClear by remember { mutableStateOf(false) }

    ScreenScaffold(stringResource(R.string.log_title), onBack, actions = {
        IconButton({ share(context, detailed = tabs.currentPage == 1) }) { Icon(Icons.Rounded.Share, stringResource(R.string.share), tint = Palette.textSecondary) }
        IconButton({ confirmClear = true }) { Icon(Icons.Rounded.DeleteOutline, stringResource(R.string.clear), tint = Palette.textSecondary) }
    }) {
        TabStrip(listOf(stringResource(R.string.tab_events), stringResource(R.string.tab_detailed)), tabs)
        TabPages(tabs) { page -> if (page == 0) EventsList() else DebugList() }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = Palette.background,
            title = { Text(stringResource(R.string.clear_log_q)) },
            confirmButton = { TextButton({ AppLog.clear(); confirmClear = false }) { Text(stringResource(R.string.clear), color = Palette.danger) } },
            dismissButton = { TextButton({ confirmClear = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun EventsList() {
    val events by AppLog.events.collectAsStateWithLifecycle()
    if (events.isEmpty()) {
        Text(stringResource(R.string.log_empty), color = Palette.textSecondary, fontSize = 14.sp, modifier = Modifier.padding(24.dp))
        return
    }
    val reversed = remember(events) { events.asReversed() }
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp)) {
        items(reversed.size) { i ->
            val e = reversed[i]
            Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.Top) {
                Text(formatTime(e.time), fontSize = 12.5.sp, color = Palette.textMuted, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 1.dp, end = 10.dp))
                Box(
                    Modifier.padding(top = 6.dp).size(8.dp).clip(CircleShape).background(
                        when (e.kind) {
                            AppLog.Kind.GOOD -> Palette.ping(40)
                            AppLog.Kind.WARN -> Palette.butter
                            AppLog.Kind.ERROR -> Palette.rose
                            AppLog.Kind.INFO -> Palette.peach
                        }
                    ),
                )
                Spacer(Modifier.width(10.dp))
                Text(e.text.render(), fontSize = 14.sp, color = Palette.text, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun DebugList() {
    val lines by AppLog.debugLines.collectAsStateWithLifecycle()
    val settings by Repo.settings.collectAsStateWithLifecycle()
    val state = rememberLazyListState()
    // Follow the tail like `tail -f`.
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) state.scrollToItem(lines.size - 1) }
    if (!settings.verboseLog) {
        Text(
            stringResource(R.string.log_verbose_off),
            fontSize = 12.5.sp, color = Palette.textSecondary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
        )
    }
    LazyColumn(
        state = state,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 24.dp),
    ) {
        items(lines) { line -> DebugLine(line) }
    }
}

/** A parsed detailed-log line: time, source and message. */
private data class LogLine(val time: String?, val source: String?, val message: String)

private val lineRegex = Regex("""^(?:\d\d-\d\d )?(\d\d:\d\d:\d\d)(?:\.\d+)? \[([^\]]+)] (.*)$""")

private fun parse(line: String): LogLine {
    val m = lineRegex.find(line) ?: return LogLine(null, null, line)
    // The message is shown exactly as written; only time and source go to the header.
    return LogLine(m.groupValues[1], m.groupValues[2], m.groupValues[3])
}

/** Short label and colour per log source. */
private fun sourceStyle(source: String): Pair<String, Color> = when {
    source == "event" -> "EVENT" to SeriesColors[0]
    source == "Engine" -> "ENGINE" to SeriesColors[1]
    source == "probe" -> "PROBE" to SeriesColors[2]
    source == "core" -> "CORE" to SeriesColors[6]
    source == "xray" -> "XRAY" to SeriesColors[3]
    source == "Tunnel" -> "TUNNEL" to SeriesColors[5]
    source == "Importer" -> "SUBS" to SeriesColors[7]
    else -> source.uppercase().take(8) to Palette.textMuted
}

@Composable
private fun DebugLine(raw: String) {
    val line = remember(raw) { parse(raw) }
    val bad = "[Error]" in line.message || " failed" in line.message || ": timeout" in line.message
    val warn = "[Warning]" in line.message
    // A block per entry: time and source on top, the message below at full width.
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (line.time != null) {
                Text(line.time, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = Palette.textMuted)
                Spacer(Modifier.width(6.dp))
            }
            if (line.source != null) {
                val (label, color) = sourceStyle(line.source)
                Text(
                    label, fontSize = 9.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                    color = Palette.text,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(color.copy(alpha = 0.18f)).padding(horizontal = 4.dp),
                )
            }
        }
        Text(
            line.message, fontSize = 11.sp, lineHeight = 15.sp, fontFamily = FontFamily.Monospace,
            color = when {
                bad -> Palette.danger
                warn -> Palette.warningIcon
                else -> Palette.text
            },
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

private fun formatTime(t: Long): String {
    val today = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }.timeInMillis
    val pattern = if (t >= today) "HH:mm:ss" else "dd.MM HH:mm:ss"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(t))
}

private fun share(context: Context, detailed: Boolean) {
    val intent = Intent(Intent.ACTION_SEND).setType("text/plain")
    if (detailed) {
        val header = "Border(less) ${BuildConfig.VERSION_NAME}, ${CoreEnv.version().substringBefore(" (")}, Android ${Build.VERSION.RELEASE} (${Build.MANUFACTURER} ${Build.MODEL})"
        val file = File(context.cacheDir, "share/borderless-log.txt").apply { parentFile?.mkdirs(); writeText(AppLog.exportDebug(header)) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        intent.putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    } else {
        intent.putExtra(Intent.EXTRA_TEXT, AppLog.exportEvents())
    }
    Errors.guard("sharing the log", Unit) { context.startActivity(Intent.createChooser(intent, context.getString(R.string.log_share_title))) }
}
