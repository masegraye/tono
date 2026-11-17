package org.example.kotlintest

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.ExperimentalFoundationApi

private val FAQ = listOf(
    "What is Compose Multiplatform?" to
        "JetBrains' declarative UI framework. The same @Composable code renders into native widgets on Android, Skia on desktop and iOS, and Wasm + WebGL in the browser.",
    "How much UI code is shared?" to
        "All of it. This entire showcase — every tab, widget and animation — is one source set compiled for Android, iOS, desktop JVM and web (Kotlin/Wasm).",
    "What is the Kotlin Toolchain?" to
        "The build system that replaced Gradle for this project: project.yaml and module.yaml files, a checked-in ./kotlin wrapper, and automatic provisioning of JDK, Android SDK, node, pnpm and browsers.",
    "Do platform differences leak in?" to
        "Rarely. expect/actual handles the seams — e.g. this app reads your Omarchy theme files on desktop via a JVM actual, while other platforms fall back to bundled palettes.",
    "How do gestures work?" to
        "The Canvas tab demos drag, pinch-zoom and rotate. Pointer input is unified, so the same gesture code drives touch on mobile and mouse/trackpad on desktop and web.",
    "Can I hot reload?" to
        "Compose Hot Reload runs the desktop app and re-injects changed @Composable code without losing state — the running window you are looking at was reloaded while you read this list.",
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SectionLists() {
    var expanded by remember { mutableStateOf<Int?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 120.dp),
    ) {
        stickyHeader {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                Text(
                    "LazyColumn — tap rows to expand",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
        itemsIndexed(FAQ) { index, (question, answer) ->
            val open = expanded == index
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = if (open) null else index }
                    .animateContentSize()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        question,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = if (open) "Collapse" else "Expand",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (open) {
                    Text(
                        answer,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }

        item {
            SectionCard(
                "Horizontal pager",
                "Swipe or drag pages — shared gesture handling.",
            ) {
                val pagerState = rememberPagerState(pageCount = { 3 })
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    pageSpacing = 8.dp,
                ) { page ->
                    Card(Modifier.fillMaxSize(), colors = wellCardColors()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "Page ${page + 1} of 3",
                                style = MaterialTheme.typography.headlineSmall,
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    repeat(3) { page ->
                        val active = pagerState.currentPage == page
                        Box(
                            modifier = Modifier
                                .padding(4.dp)
                                .size(if (active) 10.dp else 8.dp)
                                .background(
                                    if (active) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outlineVariant,
                                    CircleShape,
                                ),
                        )
                    }
                }
            }
        }

        item {
            SectionCard(
                "Grids",
                "Lazy rows and manual grids compose into any layout.",
            ) {
                (1..9).chunked(3).forEach { rowNumbers ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowNumbers.forEach { number ->
                            Card(Modifier.weight(1f), colors = wellCardColors()) {
                                Box(
                                    modifier = Modifier.fillMaxWidth().height(48.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        "$number",
                                        style = MaterialTheme.typography.titleLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                HintText("The same foundation also offers LazyVerticalGrid, LazyHorizontalGrid and staggered grids.")
            }
        }
    }
}
