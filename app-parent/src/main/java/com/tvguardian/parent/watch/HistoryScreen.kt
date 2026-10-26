package com.tvguardian.parent.watch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tvguardian.parent.devices.TvDevice
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/** Full watch history of one TV, newest first, grouped by day with each day's total. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(familyId: String, device: TvDevice, onBack: () -> Unit) {
    val history by remember(device.id) { WatchRepository.history(familyId, device.id) }.collectAsState(initial = null)
    val days = remember(history) { history.orEmpty().groupBy { dayStart(it.startedAtMillis ?: 0) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Watch history · ${device.name}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                history == null -> item { Text("Loading…") }
                days.isEmpty() -> item {
                    Text("Nothing yet. Videos appear here after they've played for at least 10 seconds.")
                }
            }
            days.forEach { (day, entries) ->
                item(key = "day-$day") {
                    Column(Modifier.padding(top = 8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(dayLabel(day), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${entries.size} videos · ${formatDuration(entries.sumOf { it.watchedSec })}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        HorizontalDivider(Modifier.padding(top = 4.dp))
                    }
                }
                items(entries, key = { it.id }) { HistoryRow(it) }
            }
        }
    }
}

@Composable
internal fun HistoryRow(entry: HistoryEntry) {
    Column(Modifier.fillMaxWidth()) {
        Text(entry.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            listOfNotNull(entry.channel, entry.appName, entry.startedAtMillis?.let(::formatTime), formatDuration(entry.watchedSec))
                .joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Midnight (local time) of the day [millis] falls on. */
internal fun dayStart(millis: Long): Long = Calendar.getInstance().apply {
    timeInMillis = millis
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

private fun dayLabel(dayStartMillis: Long): String {
    val today = dayStart(System.currentTimeMillis())
    val yesterday = dayStart(today - 12 * 60 * 60 * 1000) // any time yesterday
    return when (dayStartMillis) {
        today -> "Today"
        yesterday -> "Yesterday"
        else -> DateFormat.getDateInstance(DateFormat.FULL).format(Date(dayStartMillis))
    }
}

private fun formatTime(millis: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(millis))

internal fun formatDuration(seconds: Long): String = when {
    seconds < 60 -> "${seconds}s"
    seconds < 3600 -> "${seconds / 60} min"
    else -> "${seconds / 3600} h ${seconds % 3600 / 60} min"
}
