package org.example.kotlintest

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlintest.rex.RexLog

/**
 * A panel along the bottom of the window showing what the client has been doing: its
 * connections, the events servers send, the requests that change something, and the
 * actions run. The text can be selected and copied.
 */
@Composable
fun DebugLogPanel(log: RexLog, shortcut: String?, onClose: () -> Unit) {
    val entries by log.entries.collectAsState()
    val listState = rememberLazyListState()
    // Follow the newest entry.
    LaunchedEffect(entries.size) { if (entries.isNotEmpty()) listState.scrollToItem(entries.lastIndex) }

    Column(Modifier.fillMaxWidth().height(230.dp).background(MaterialTheme.colorScheme.surfaceContainerLowest)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Debug log", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            HintText("${entries.size} entries")
            TextButton(onClick = log::clear) { Text("Clear") }
            TextButton(onClick = onClose) { Text(if (shortcut != null) "Close ($shortcut)" else "Close") }
        }
        SelectionContainer(Modifier.fillMaxSize()) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                items(entries) { entry ->
                    Row {
                        Text(
                            clockTime(entry.timeMillis),
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.outline,
                        )
                        Text(
                            entry.source,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 10.dp),
                        )
                        Text(
                            entry.message,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = if (entry.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}
