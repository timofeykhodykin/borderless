package app.borderless.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.borderless.ui.theme.Palette
import kotlinx.coroutines.launch

/**
 * Screens built of a few panels (statistics, the log, the exceptions) share one switch: a row of tabs
 * at the top and a sideways swipe between the panels. [rememberTabs] holds which one is open,
 * [TabStrip] draws the row, [TabPages] the panels.
 */
@Composable
fun rememberTabs(count: Int, initial: Int = 0): PagerState = rememberPagerState(initialPage = initial) { count }

/** The row of tabs: tapping one slides its panel in. */
@Composable
fun TabStrip(labels: List<String>, tabs: PagerState, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    Row(
        modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        labels.forEachIndexed { i, label ->
            val selected = tabs.currentPage == i
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (selected) Palette.selected else Palette.card)
                    .clickable { scope.launch { tabs.animateScrollToPage(i) } }
                    .padding(vertical = 11.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                    color = if (selected) Palette.accent else Palette.text,
                )
            }
        }
    }
}

/** The panels themselves: swiping sideways moves between them (charts keep their own gestures). */
@Composable
fun TabPages(tabs: PagerState, modifier: Modifier = Modifier, page: @Composable (Int) -> Unit) {
    HorizontalPager(state = tabs, modifier = modifier.fillMaxSize(), verticalAlignment = Alignment.Top) { i -> page(i) }
}
