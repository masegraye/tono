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
    // How the desktop looks. On the desktop app this is read from the files Omarchy writes;
    // on web it is pushed by the server; on Android/iOS it stays null. Whatever theme the
    // user applies, the flow emits its palette and the whole app restyles at runtime.
    var desktop by remember { mutableStateOf(readOmarchyAppearance()) }
    // A palette picked in the Theming tab, shown instead of the desktop's until either the
    // user dismisses it or the desktop's theme changes.
    var preview by remember { mutableStateOf<OmarchyPalette?>(null) }
    var darkOverride by remember { mutableStateOf<Boolean?>(null) } // null = the palette's own mode
    LaunchedEffect(Unit) {
        omarchyAppearanceFlow().collect { latest ->
            if (latest != desktop) {
                desktop = latest
                // The desktop changed: follow it, dropping any choices made in the app.
                preview = null
                darkOverride = null
            }
        }
    }

    val palette = preview ?: desktop?.palette ?: bundledOmarchyPalettes.first()
    val dark = darkOverride ?: palette.isDark
    val fontName = desktop?.fontName
    val fontFamily = remember(fontName) { fontName?.let(::systemFontFamily) }
    val typography = remember(fontFamily) { omarchyTypography(fontFamily) }
    val source = when {
        preview != null -> "Preview: "
        desktop != null -> "Omarchy: "
        else -> ""
    }

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
                                "$platformName · $source${palette.displayName}",
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
                            desktop = desktop,
                            desktopFontApplied = fontFamily != null,
                            previewing = preview != null,
                            dark = dark,
                            onPreview = { preview = it; darkOverride = null },
                            onDarkToggle = { darkOverride = it },
                        )
                        else -> SectionPlatform()
                    }
                }
            }
        }
    }
}
