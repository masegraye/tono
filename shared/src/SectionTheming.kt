package org.example.kotlintest

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun SectionTheming(
    palette: OmarchyPalette,
    fontName: String?,
    live: Boolean,
    selectedSlug: String?,
    dark: Boolean,
    onSelect: (String?) -> Unit,
    onDarkToggle: (Boolean) -> Unit,
) {
    SectionScroll {
        SectionCard(
            "Active palette",
            if (live)
                "Live — read from your Omarchy theme's colors.toml. Run " +
                    "`omarchy theme set <name>` and watch this app re-theme itself."
            else
                "Bundled fallback — Omarchy isn't present on this platform, so a stock " +
                    "palette is embedded instead.",
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

            Text(
                if (fontName != null) "Font: $fontName — the desktop's Omarchy font, via fontconfig"
                else "Font: platform default — the Omarchy font is only readable on the desktop",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Dark scheme", Modifier.weight(1f))
                Switch(checked = dark, onCheckedChange = onDarkToggle)
            }
        }

        SectionCard(
            "Palette source",
            "Auto follows the desktop's Omarchy theme; the others are bundled stock themes.",
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = selectedSlug == null,
                    onClick = { onSelect(null) },
                    label = { Text("Auto (Omarchy)") },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                bundledOmarchyPalettes.forEach { p ->
                    FilterChip(
                        selected = selectedSlug == p.slug,
                        onClick = { onSelect(p.slug) },
                        label = { Text(p.displayName) },
                    )
                }
            }
            val installed = rememberInstalledThemeCount()
            if (installed > 0) {
                HintText(
                    "$installed themes installed on this machine " +
                        "(~/.config/omarchy/themes + /usr/share/omarchy/themes). " +
                        "Omarchy can also notify apps on change via its theme-set hook.",
                )
            }
        }

        SectionCard(
            "Swatches",
            "Every key in the active colors.toml, mapped straight onto Material 3 roles.",
        ) {
            palette.colorKeys.chunked(2).forEach { keys ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    keys.forEach { key ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(palette.color(key))
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp)),
                            )
                            Column {
                                Text(key, style = MaterialTheme.typography.labelMedium)
                                HintText(palette.raw[key] ?: "")
                            }
                        }
                    }
                    if (keys.size == 1) Box(Modifier.weight(1f))
                }
            }
        }

        SectionCard(
            "Typography scale",
            "Material's type scale, restyled by the theme in effect.",
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

        SectionCard(
            "How the Omarchy integration works",
            null,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "1. Every Omarchy theme ships a machine-readable colors.toml " +
                        "(mode, accent, backgrounds, foregrounds, semantic colors).",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "2. A JVM expect/actual reads ~/.local/state/omarchy/current/theme.name and " +
                        "resolves the palette from your theme directories.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "3. The palette is mapped onto a Material 3 ColorScheme and handed to " +
                        "MaterialTheme together with Omarchy-style rounded shapes — every widget " +
                        "in this app picks it up automatically.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "4. The app polls the theme file, so changing your desktop theme re-skins " +
                        "the running app. For push instead of pull, Omarchy's theme-set hook can " +
                        "notify apps directly.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun rememberInstalledThemeCount(): Int =
    androidx.compose.runtime.remember { listOmarchyThemeSlugs().size }
