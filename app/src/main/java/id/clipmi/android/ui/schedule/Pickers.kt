package id.clipmi.android.ui.schedule

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.clipmi.android.ui.components.GhostButton
import id.clipmi.android.ui.theme.ClipMi
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Pemilih jam 24 jam (slot rutin, jam jadwal). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickerDialog(hour: Int, minute: Int, onDismiss: () -> Unit, onPick: (Int, Int) -> Unit) {
    val c = ClipMi.colors
    val state = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.card,
        shape = RoundedCornerShape(28.dp),
        title = { Text("Pilih jam", style = MaterialTheme.typography.titleLarge, color = c.text) },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.Center) {
                TimePicker(
                    state = state,
                    colors = TimePickerDefaults.colors(
                        clockDialColor = c.panel,
                        selectorColor = c.accent,
                        timeSelectorSelectedContainerColor = c.accentSoft,
                        timeSelectorSelectedContentColor = c.accent,
                        timeSelectorUnselectedContainerColor = c.panel,
                        timeSelectorUnselectedContentColor = c.text,
                        clockDialSelectedContentColor = c.onAccent,
                        clockDialUnselectedContentColor = c.text,
                    ),
                )
            }
        },
        confirmButton = { GhostButton("Pakai", { onPick(state.hour, state.minute) }) },
        dismissButton = { GhostButton("Batal", onDismiss) },
    )
}

/** Pemilih tanggal; tanggal sebelum [notBefore] tidak bisa dipilih. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatePickerSheet(date: LocalDate, notBefore: LocalDate?, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    val c = ClipMi.colors
    // DatePicker memakai milidetik tengah malam UTC.
    val state = rememberDatePickerState(
        initialSelectedDateMillis = date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
        selectableDates = object : androidx.compose.material3.SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                notBefore == null || !Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate().isBefore(notBefore)
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            GhostButton("Pakai", {
                state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) } ?: onDismiss()
            })
        },
        dismissButton = { GhostButton("Batal", onDismiss) },
        colors = DatePickerDefaults.colors(containerColor = c.card),
    ) {
        DatePicker(
            state = state,
            showModeToggle = false,
            title = { Text("Pilih tanggal", style = MaterialTheme.typography.titleMedium, color = c.textMuted, modifier = Modifier.padding(start = 24.dp, top = 16.dp)) },
            colors = DatePickerDefaults.colors(
                containerColor = c.card,
                selectedDayContainerColor = c.accent,
                selectedDayContentColor = c.onAccent,
                todayDateBorderColor = c.accent,
                todayContentColor = c.accent,
            ),
        )
    }
}

/** Isian pilihan yang membuka dialog (tanggal, jam): label kecil + nilai dalam kotak. */
@Composable
fun PickerField(label: String, value: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = ClipMi.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), color = c.textBody)
        id.clipmi.android.ui.components.SecondaryButton(value, onClick, modifier = Modifier.fillMaxWidth(), height = 52.dp)
    }
}
