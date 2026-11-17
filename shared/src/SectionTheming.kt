package org.example.kotlintest

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * @param palette the palette the app is showing
 * @param desktop how the desktop looks, or null where that is unknown
 * @param desktopFontApplied whether text is being drawn in the desktop's font
 * @param previewing true when [palette] was picked here rather than taken from the desktop
 * @param onPreview show the given palette, or go back to following the desktop with null
 */
@Composable
fun SectionTheming(
    palette: OmarchyPalette,
    desktop: OmarchyAppearance?,
    desktopFontApplied: Boolean,
    previewing: Boolean,
    dark: Boolean,
    onPreview: (OmarchyPalette?) -> Unit,
    onDarkToggle: (Boolean) -> Unit,
) {
    // Every theme installed on this machine where that can be listed (the desktop app);
    // elsewhere, the few stock palettes bundled with the app.
    val installed = remember { readInstalledOmarchyPalettes() }
    val choices = installed.ifEmpty { bundledOmarchyPalettes }

    SectionScroll {
        SectionCard(
            "Active palette",
            when {
                previewing && desktop != null ->
                    "Preview. The desktop is on ${desktop.palette.displayName}; this app goes back to " +
                        "following it when you stop the preview or switch the desktop's theme."
                previewing ->
                    "Preview of a palette bundled with the app."
                desktop != null ->
                    "Live. These are the colors Omarchy has applied to the desktop. Switch to any " +
                        "theme and this app restyles itself; nothing is rebuilt or reloaded."
                else ->
                    "Bundled fallback. Nothing here can see an Omarchy desktop, so the app is " +
                        "using a stock palette that ships inside it."
            },
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Text(
                        palette.displayName,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
                Text(
                    if (dark) "dark mode" else "light mode",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (previewing && desktop != null) {
                Button(onClick = { onPreview(null) }) {
                    Text("Follow the desktop (${desktop.palette.displayName})")
                }
            }

            Text(
                when (val font = desktop?.fontName) {
                    null -> "Font: platform default"
                    else if desktopFontApplied -> "Font: $font, the desktop's Omarchy font"
                    else -> "Font: platform default. The desktop uses $font, which this platform cannot load."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Dark scheme", Modifier.weight(1f))
                Switch(checked = dark, onCheckedChange = onDarkToggle)
            }
        }

        SectionCard(
            "Preview another theme",
            if (installed.isNotEmpty())
                "All ${installed.size} themes installed on this machine, read from their colors.toml. " +
                    "Picking one only changes this app, not the desktop."
            else
                "The stock palettes bundled with the app.",
        ) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                choices.forEach { choice ->
                    FilterChip(
                        selected = choice.slug == palette.slug,
                        onClick = {
                            // Picking the desktop's own theme is the same as following it.
                            onPreview(choice.takeUnless { it == desktop?.palette })
                        },
                        label = { Text(choice.displayName) },
                    )
                }
            }
        }

        SectionCard(
            "Swatches",
            "Every color in the active palette. The whole Material 3 color scheme is derived from these.",
        ) {
            palette.colors.entries.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pair.forEach { (name, hex) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(palette.color(name))
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp)),
                            )
                            Column {
                                Text(name, style = MaterialTheme.typography.labelMedium)
                                HintText(hex)
                            }
                        }
                    }
                    if (pair.size == 1) Box(Modifier.weight(1f))
                }
            }
        }

        SectionCard(
            "Typography scale",
            "Material's type scale in the font of the theme in effect.",
        ) {
            Text("Display Medium", style = MaterialTheme.typography.displayMedium)
            Text("Headline Small", style = MaterialTheme.typography.headlineSmall)
            Text("Title Large", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text("Body Large — running text uses this style by default in most places.", style = MaterialTheme.typography.bodyLarge)
            Text("Label Small", style = MaterialTheme.typography.labelSmall)
            Text(
                "Italic body text is a one-liner too.",
                style = MaterialTheme.typography.bodyMedium,
                fontStyle = FontStyle.Italic,
            )
        }

        SectionCard("How the Omarchy integration works") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "1. Every Omarchy theme ships a colors.toml: a mode plus named colors (accent, " +
                        "backgrounds, foregrounds, red, green, ...).",
                    "2. Omarchy keeps the applied theme in ~/.local/state/omarchy/current. The app " +
                        "reads the palette from there and watches the directory, so it works for " +
                        "any theme, including ones installed later.",
                    "3. The desktop app reads those files itself. A web page cannot, so the server " +
                        "module reads them and pushes each change to the page over Server-Sent Events.",
                    "4. The palette is mapped onto a Material 3 ColorScheme and handed to MaterialTheme, " +
                        "with Omarchy's font and rounded shapes. It is plain runtime state, so a " +
                        "change restyles every widget immediately.",
                ).forEach { step ->
                    Text(step, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
