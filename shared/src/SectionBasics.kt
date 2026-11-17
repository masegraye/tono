package org.example.kotlintest

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SectionBasics() {
    SectionScroll {
        SectionCard(
            "Buttons",
            "Filled, tonal, outlined, text and elevated variants, plus icon buttons.",
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {}) { Text("Filled") }
                FilledTonalButton(onClick = {}) { Text("Tonal") }
                OutlinedButton(onClick = {}) { Text("Outlined") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {}) { Text("Text") }
                ElevatedButton(onClick = {}) { Text("Elevated") }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = {}) { Icon(Icons.Filled.Share, contentDescription = "Share") }
                IconToggleButton(checked = false, onCheckedChange = {}) {
                    Icon(Icons.Filled.FavoriteBorder, contentDescription = "Like")
                }
                BadgedBox(badge = { Badge { Text("3") } }) {
                    Icon(
                        Icons.Filled.Notifications,
                        contentDescription = "Notifications",
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }

        SectionCard(
            "Chips",
            "Assist, filter and suggestion chips share one widget family.",
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = {}, label = { Text("Assist") })
                var filters by remember {
                    mutableStateOf(setOf("Kotlin", "Compose", "Wasm"))
                }
                listOf("Kotlin", "Compose", "Wasm", "Swift").forEach { name ->
                    FilterChip(
                        selected = name in filters,
                        onClick = {
                            filters = if (name in filters) filters - name else filters + name
                        },
                        label = { Text(name) },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SuggestionChip(onClick = {}, label = { Text("Suggestion") })
            }
        }

        SectionCard(
            "Cards",
            "Filled, elevated and outlined card elevations, all using the Omarchy shapes.",
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Card(Modifier.weight(1f), colors = wellCardColors()) {
                    Text("Filled", Modifier.padding(12.dp))
                }
                ElevatedCard(Modifier.weight(1f)) {
                    Text("Elevated", Modifier.padding(12.dp))
                }
                OutlinedCard(Modifier.weight(1f)) {
                    Text("Outlined", Modifier.padding(12.dp))
                }
            }
        }

        SectionCard(
            "Progress indicators",
            "Linear and circular, determinate and indeterminate.",
        ) {
            val infinite = rememberInfiniteTransition(label = "progress")
            val progress by infinite.animateFloat(
                0f, 1f,
                infiniteRepeatable(tween(1400)),
                label = "value",
            )
            LinearProgressIndicator(progress = { progress })
            LinearProgressIndicator()
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(progress = { progress })
                CircularProgressIndicator()
            }
        }

        SectionCard(
            "Dialogs & sheets",
            "Alert dialogs and modal bottom sheets with scrim, on every platform.",
        ) {
            var showDialog by remember { mutableStateOf(false) }
            var showSheet by remember { mutableStateOf(false) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { showDialog = true }) { Text("Open dialog") }
                OutlinedButton(onClick = { showSheet = true }) { Text("Open bottom sheet") }
            }
            if (showDialog) {
                AlertDialog(
                    onDismissRequest = { showDialog = false },
                    confirmButton = {
                        TextButton(onClick = { showDialog = false }) { Text("Sounds good") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDialog = false }) { Text("Dismiss") }
                    },
                    icon = { Icon(Icons.Filled.Star, contentDescription = null) },
                    title = { Text("This is an AlertDialog") },
                    text = {
                        Text("Dialogs, their scrims and animations render identically on Android, iOS, desktop and web.")
                    },
                )
            }
            if (showSheet) {
                ModalBottomSheet(
                    onDismissRequest = { showSheet = false },
                ) {
                    Column(Modifier.padding(24.dp)) {
                        Text("Modal bottom sheet", style = MaterialTheme.typography.titleMedium)
                        HintText(
                            "Swipe down or tap the scrim to dismiss — gesture handling is shared code too.",
                            Modifier.padding(top = 8.dp),
                        )
                        Button(
                            onClick = { showSheet = false },
                            modifier = Modifier.padding(top = 16.dp),
                        ) { Text("Close") }
                    }
                }
            }
        }

        SectionCard(
            "List items & dividers",
            "Material list rows with leading/trailing slots.",
        ) {
            val rowColors = ListItemDefaults.colors(containerColor = Color.Transparent)
            ListItem(
                colors = rowColors,
                headlineContent = { Text("Headline") },
                supportingContent = { Text("Supporting text explains the row") },
                leadingContent = { Icon(Icons.Filled.CheckCircle, contentDescription = null) },
                trailingContent = { Text("24m") },
            )
            HorizontalDivider()
            ListItem(
                colors = rowColors,
                headlineContent = { Text("Another row") },
                supportingContent = { Text("Leading icons, trailing meta") },
                leadingContent = { Icon(Icons.Filled.Favorite, contentDescription = null) },
            )
        }
    }
}
