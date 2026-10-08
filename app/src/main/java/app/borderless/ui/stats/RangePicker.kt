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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
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

    AlertDialog(
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
            DatePickerDialog(
                onDismissRequest = { editing = null },
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
            ) { DatePicker(state) }
        }

        "fromTime", "toTime" -> {
            val current = if (editing == "fromTime") from else to
            val cal = Calendar.getInstance().apply { timeInMillis = current }
            val state = rememberTimePickerState(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), is24Hour = true)
            AlertDialog(
                onDismissRequest = { editing = null },
                containerColor = Palette.background,
                text = { TimePicker(state) },
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
