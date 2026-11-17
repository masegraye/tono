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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.mg.omarchy.theme.compose.OmarchyTheme
import dev.mg.omarchy.theme.compose.rememberOmarchyThemeState
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
    // OmarchyTheme (from the omarchy-theme-compose library) styles everything inside it like
    // the Omarchy desktop and follows the desktop's theme and font as they change. Holding
    // the state here lets the top bar and the Theming tab show and override it.
    val theme = rememberOmarchyThemeState()
    val source = when {
        theme.isPreviewing -> "Preview: "
        theme.desktop != null -> "Omarchy: "
        else -> ""
    }

    OmarchyTheme(theme) {
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
                                "$platformName · $source${theme.palette.displayName}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    actions = {
                        Text("Dark", style = MaterialTheme.typography.labelMedium)
                        Switch(
                            checked = theme.isDark,
                            onCheckedChange = { theme.darkOverride = it },
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
                            palette = theme.palette,
                            desktop = theme.desktop,
                            desktopFontApplied = theme.fontFamily != null,
                            previewing = theme.isPreviewing,
                            dark = theme.isDark,
                            onPreview = { theme.preview = it; theme.darkOverride = null },
                            onDarkToggle = { theme.darkOverride = it },
                        )
                        else -> SectionPlatform()
                    }
                }
            }
        }
    }
}
