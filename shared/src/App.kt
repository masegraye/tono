package org.example.kotlintest

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.jetbrains.compose.reload.DevelopmentEntryPoint

private val SECTION_TITLES = listOf(
    "Components", "Inputs", "Lists", "Motion", "Canvas", "Theming", "Platform",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Preview
@DevelopmentEntryPoint
fun App() {
    // Live Omarchy palette. On desktop this is the colors.toml of the theme Omarchy has
    // applied; on Android/iOS/web it is null and a bundled stock palette is used instead.
    // awaitOmarchyAppearanceChange() is a filesystem watch, so `omarchy theme set ...` or
    // `omarchy font set ...` (or the Omarchy menu) restyles the running app immediately.
    var systemPalette by remember { mutableStateOf(readOmarchyPalette()) }
    var systemFont by remember { mutableStateOf(readOmarchyFont()) }
    var selectedSlug by remember { mutableStateOf<String?>(null) } // null = follow the system
    var darkOverride by remember { mutableStateOf<Boolean?>(null) } // null = follow the palette
    LaunchedEffect(Unit) {
        while (true) {
            awaitOmarchyAppearanceChange()
            systemPalette = readOmarchyPalette()
            val font = readOmarchyFont()
            if (font?.name != systemFont?.name) systemFont = font
            // A desktop theme change wins over a manual light/dark flip made in the app.
            darkOverride = null
        }
    }

    val palette = remember(systemPalette, selectedSlug) {
        selectedSlug?.let { slug -> bundledOmarchyPalettes.firstOrNull { it.slug == slug } }
            ?: systemPalette
            ?: bundledOmarchyPalettes.first()
    }
    val dark = darkOverride ?: palette.isDark
    val live = selectedSlug == null && systemPalette != null

    val typography = remember(systemFont) { omarchyTypography(systemFont?.family) }

    MaterialTheme(
        colorScheme = omarchyColorScheme(palette, dark),
        shapes = omarchyShapes(),
        typography = typography,
    ) {
        val snackbarHostState = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        var section by remember { mutableStateOf(0) }
        val platformName = remember { getPlatform().name }

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                "KMP UI Showcase",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "$platformName · ${if (live) "Omarchy: " else ""}${palette.displayName}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    actions = {
                        Text("Dark", style = MaterialTheme.typography.labelMedium)
                        Switch(
                            checked = dark,
                            onCheckedChange = { darkOverride = it },
                            modifier = Modifier.padding(start = 8.dp, end = 16.dp),
                        )
                    },
                )
            },
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = {
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                message = "Snackbars work on every platform",
                                actionLabel = "OK",
                                withDismissAction = true,
                            )
                        }
                    },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("Snackbar") },
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                PrimaryScrollableTabRow(selectedTabIndex = section, edgePadding = 8.dp) {
                    SECTION_TITLES.forEachIndexed { index, title ->
                        Tab(
                            selected = section == index,
                            onClick = { section = index },
                            text = { Text(title) },
                        )
                    }
                }
                Crossfade(targetState = section, label = "section") { index ->
                    when (index) {
                        0 -> SectionBasics()
                        1 -> SectionInputs()
                        2 -> SectionLists()
                        3 -> SectionMotion()
                        4 -> SectionCanvas()
                        5 -> SectionTheming(
                            palette = palette,
                            fontName = systemFont?.name,
                            live = live,
                            selectedSlug = selectedSlug,
                            dark = dark,
                            onSelect = { selectedSlug = it },
                            onDarkToggle = { darkOverride = it },
                        )
                        else -> SectionPlatform()
                    }
                }
            }
        }
    }
}
