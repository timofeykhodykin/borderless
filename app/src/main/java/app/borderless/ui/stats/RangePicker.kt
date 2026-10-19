package app.borderless.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import app.borderless.ui.AppAlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.borderless.R
import app.borderless.Res
import app.borderless.ui.theme.Palette
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.TimeZone

fun formatRange(from: Long, to: Long): String {
    val day = SimpleDateFormat("d MMM", Res.locale)
    val time = SimpleDateFormat("HH:mm", Res.locale)
    val sameDay = day.format(Date(from)) == day.format(Date(to))
    return if (sameDay) "${day.format(Date(from))}, ${time.format(Date(from))}–${time.format(Date(to))}"
    else "${day.format(Date(from))} ${time.format(Date(from))} – ${day.format(Date(to))} ${time.format(Date(to))}"
}

/** Start and end of a custom statistics period: a date and a time for each. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RangeDialog(initialFrom: Long, initialTo: Long, onDismiss: () -> Unit, onApply: (Long, Long) -> Unit) {
    var from by remember { mutableLongStateOf(initialFrom) }
    var to by remember { mutableLongStateOf(initialTo) }
    // Which value is being edited: "fromDate", "fromTime", "toDate", "toTime".
    var editing by remember { mutableStateOf<String?>(null) }
    val valid = from < to

    AppAlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.background,
        title = { Text(stringResource(R.string.period_custom)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Endpoint(stringResource(R.string.range_from), from, { editing = "fromDate" }, { editing = "fromTime" })
                Endpoint(stringResource(R.string.range_to), to, { editing = "toDate" }, { editing = "toTime" })
                if (!valid) Text(stringResource(R.string.range_invalid), color = Palette.danger, fontSize = 13.sp)
            }
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onApply(from, to); onDismiss() }) { Text(stringResource(R.string.done)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )

    when (editing) {
        "fromDate", "toDate" -> {
            val current = if (editing == "fromDate") from else to
            // The date picker works in UTC midnight; keep the local time of day.
            val state = rememberDatePickerState(initialSelectedDateMillis = localDateAsUtc(current), selectableDates = PastOrToday)
            // The app's own dialog around Material's calendar, coloured like the rest of the app.
            AppAlertDialog(
                onDismissRequest = { editing = null },
                wide = true,
                title = { Text(stringResource(if (editing == "fromDate") R.string.range_from else R.string.range_to)) },
                text = {
                    InAppFont {
                        DatePicker(
                            state, title = null, headline = null, showModeToggle = false, colors = calendarColors(),
                        )
                    }
                },
                confirmButton = {
                    TextButton({
                        state.selectedDateMillis?.let { utc ->
                            val v = withDate(current, utc)
                            if (editing == "fromDate") from = v else to = v
                        }
                        editing = null
                    }) { Text(stringResource(R.string.done)) }
                },
                dismissButton = { TextButton({ editing = null }) { Text(stringResource(R.string.cancel)) } },
            )
        }

        "fromTime", "toTime" -> {
            val current = if (editing == "fromTime") from else to
            val cal = Calendar.getInstance().apply { timeInMillis = current }
            val state = rememberTimePickerState(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), is24Hour = true)
            AppAlertDialog(
                onDismissRequest = { editing = null },
                containerColor = Palette.background,
                text = { InAppFont { TimePicker(state, colors = clockColors()) } },
                confirmButton = {
                    TextButton({
                        val v = Calendar.getInstance().apply {
                            timeInMillis = current
                            set(Calendar.HOUR_OF_DAY, state.hour); set(Calendar.MINUTE, state.minute)
                            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                        }.timeInMillis
                        if (editing == "fromTime") from = v else to = v
                        editing = null
                    }) { Text(stringResource(R.string.done)) }
                },
                dismissButton = { TextButton({ editing = null }) { Text(stringResource(R.string.cancel)) } },
            )
        }
    }
}

@Composable
private fun Endpoint(label: String, value: Long, onDate: () -> Unit, onTime: () -> Unit) {
    Column {
        Text(label, fontSize = 13.sp, color = Palette.textSecondary)
        Spacer(Modifier.padding(top = 4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Field(SimpleDateFormat("d MMM yyyy", Res.locale).format(Date(value)), Modifier.weight(1f), onDate)
            Spacer(Modifier.width(8.dp))
            Field(SimpleDateFormat("HH:mm", Res.locale).format(Date(value)), Modifier, onTime)
        }
    }
}

@Composable
private fun Field(text: String, modifier: Modifier, onClick: () -> Unit) {
    Text(
        text, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Palette.text,
        modifier = modifier.clip(RoundedCornerShape(12.dp)).background(Palette.card).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    )
}

/** Material's pickers in the app's font (their numbers and labels come from the theme's typography). */
@Composable
private fun InAppFont(content: @Composable () -> Unit) {
    val t = androidx.compose.material3.MaterialTheme.typography
    val f = app.borderless.ui.theme.Body
    androidx.compose.material3.MaterialTheme(
        typography = androidx.compose.material3.Typography(
            displayLarge = t.displayLarge.copy(fontFamily = f), displayMedium = t.displayMedium.copy(fontFamily = f),
            displaySmall = t.displaySmall.copy(fontFamily = f), headlineLarge = t.headlineLarge.copy(fontFamily = f),
            headlineMedium = t.headlineMedium.copy(fontFamily = f), headlineSmall = t.headlineSmall.copy(fontFamily = f),
            titleLarge = t.titleLarge.copy(fontFamily = f), titleMedium = t.titleMedium.copy(fontFamily = f),
            titleSmall = t.titleSmall.copy(fontFamily = f), bodyLarge = t.bodyLarge.copy(fontFamily = f),
            bodyMedium = t.bodyMedium.copy(fontFamily = f), bodySmall = t.bodySmall.copy(fontFamily = f),
            labelLarge = t.labelLarge.copy(fontFamily = f), labelMedium = t.labelMedium.copy(fontFamily = f),
            labelSmall = t.labelSmall.copy(fontFamily = f),
        ),
        content = content,
    )
}

/** The calendar in the app's colours: the accent for the chosen day, soft accent for today, warm greys elsewhere. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun calendarColors() = androidx.compose.material3.DatePickerDefaults.colors(
    containerColor = Palette.background,
    subheadContentColor = Palette.textSecondary,
    navigationContentColor = Palette.textSecondary,
    weekdayContentColor = Palette.textMuted,
    yearContentColor = Palette.text,
    currentYearContentColor = Palette.accentStrong,
    selectedYearContentColor = Palette.onAccent,
    selectedYearContainerColor = Palette.accent,
    dayContentColor = Palette.text,
    disabledDayContentColor = Palette.textMuted.copy(alpha = 0.5f),
    selectedDayContentColor = Palette.onAccent,
    selectedDayContainerColor = Palette.accent,
    todayContentColor = Palette.accentStrong,
    todayDateBorderColor = Palette.accent,
    dividerColor = Palette.outline,
)

/** The clock in the app's colours. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun clockColors() = androidx.compose.material3.TimePickerDefaults.colors(
    clockDialColor = Palette.card,
    clockDialSelectedContentColor = Palette.onAccent,
    clockDialUnselectedContentColor = Palette.text,
    selectorColor = Palette.accent,
    containerColor = Palette.background,
    periodSelectorBorderColor = Palette.outline,
    timeSelectorSelectedContainerColor = Palette.selected,
    timeSelectorUnselectedContainerColor = Palette.card,
    timeSelectorSelectedContentColor = Palette.accentStrong,
    timeSelectorUnselectedContentColor = Palette.text,
)

@OptIn(ExperimentalMaterial3Api::class)
private object PastOrToday : androidx.compose.material3.SelectableDates {
    override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= System.currentTimeMillis() + 86_400_000L
}

/** Local calendar date of [t] as UTC midnight (what DatePicker expects). */
private fun localDateAsUtc(t: Long): Long {
    val local = Calendar.getInstance().apply { timeInMillis = t }
    return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear(); set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH))
    }.timeInMillis
}

/** [t] moved to the calendar date picked in the DatePicker ([utcDate] = UTC midnight). */
private fun withDate(t: Long, utcDate: Long): Long {
    val d = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcDate }
    return Calendar.getInstance().apply {
        timeInMillis = t
        set(d.get(Calendar.YEAR), d.get(Calendar.MONTH), d.get(Calendar.DAY_OF_MONTH))
    }.timeInMillis
}
