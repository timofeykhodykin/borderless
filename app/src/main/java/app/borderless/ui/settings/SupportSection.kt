package app.borderless.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.borderless.R
import app.borderless.data.Repo
import app.borderless.data.SupportStats
import app.borderless.ui.stats.SeriesColors
import app.borderless.ui.theme.Display
import app.borderless.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Protocols and formats": what the app can run. Protocols, transports and security carry the number of the
 * user's configs using each (0 dimmed). Each category in a dashed frame of its own colour.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SupportSection() {
    val servers by Repo.servers.collectAsStateWithLifecycle()
    val counts by produceState(emptyMap<String, Int>(), servers) { value = withContext(Dispatchers.Default) { SupportStats.count(servers) } }
    Text(
        stringResource(R.string.sup_title), fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = Palette.accentStrong,
        modifier = Modifier.padding(start = 20.dp, top = 24.dp),
    )
    Text(
        stringResource(R.string.sup_legend), fontSize = 13.sp, color = Palette.textSecondary,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 10.dp),
    )
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Frame(stringResource(R.string.sup_protocols), SeriesColors[0], SupportStats.PROTOCOLS.map { (k, name) -> name to counts["p:$k"] })
        Frame(stringResource(R.string.sup_transports), SeriesColors[2], SupportStats.TRANSPORTS.map { (k, name) -> name to counts["t:$k"] })
        Frame(stringResource(R.string.sup_security), SeriesColors[6], SupportStats.SECURITY.map { (k, name) -> name to counts["s:$k"] })
    }
}

/**
 * One category: a dashed frame in [color] with its title and items; an item's number is how many
 * configs use it (null = a capability without a count). [countAll]: every item has a number (0 shown dimmed).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Frame(title: String, color: Color, items: List<Pair<String, Int?>>, countAll: Boolean = true) {
    Column(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                val w = 1.5.dp.toPx()
                // The frame is a pastel of the category colour (half way to the background); text keeps the colour.
                drawRoundRect(
                    lerp(color, Palette.background, 0.5f), topLeft = androidx.compose.ui.geometry.Offset(w / 2, w / 2),
                    size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w),
                    cornerRadius = CornerRadius(16.dp.toPx()),
                    style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(9f, 7f))),
                )
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = color)
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items.forEach { (name, n) ->
                val count = n ?: if (countAll) 0 else null
                val dim = count == 0
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name, fontSize = 14.sp, color = if (dim) Palette.textMuted else Palette.text)
                    if (count != null) {
                        Spacer(Modifier.width(5.dp))
                        Text(
                            count.toString(), fontSize = 14.sp, fontFamily = Display, fontWeight = FontWeight.Bold,
                            color = if (dim) Palette.textMuted else color,
                        )
                    }
                }
            }
        }
    }
}
