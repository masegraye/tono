package org.example.kotlintest

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mg.omarchy.theme.OmarchyPalette
import dev.mg.omarchy.theme.compose.color
import dev.mg.omarchy.theme.compose.hexToColor
import dev.mg.omarchy.theme.terminalColors

/*
 * The Appearance part of the settings: the theme, and the terminals' font. It is written to
 * go in the settings as a section of its own (Settings.kt lists them), and draws nothing
 * around itself: no heading, no scrolling, no panel. Like every setting, what is chosen here
 * applies at once and is kept in the workspace, and what is chosen elsewhere (the Theme…
 * and Terminal Font… commands, another window) shows here as promptly.
 *
 * It has to be drawn inside TonoTheme, which is also what keeps the choice in step.
 */

/** The theme, and the terminals' font, for choosing. As wide as it is given; as tall as it needs. */
@Composable
fun AppearanceSettings(ui: RexUiState, modifier: Modifier = Modifier) {
    val look = ui.appearance
    Column(modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SettingLabel("Theme")
        ThemeChoices(look)
        Spacer(Modifier.height(6.dp))
        SettingLabel("Terminal font")
        FontChoices(look)
    }
}

@Composable
private fun SettingLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThemeChoices(look: AppearanceState) {
    val options = look.themes
    val follow = options.first { it.slug == null }
    val colors = MaterialTheme.colorScheme
    // Following the desktop is a line of its own: it isn't one more theme, it is not choosing one.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .border(if (follow.current) 2.dp else 1.dp, if (follow.current) colors.primary else colors.outlineVariant, MaterialTheme.shapes.small)
            .clickable(enabled = follow.available, role = Role.RadioButton) { look.chooseTheme(null) }
            .semantics { selected = follow.current }
            .alpha(if (follow.available) 1f else 0.55f)
            .heightIn(min = 48.dp)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(follow.title, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
            Text(
                if (follow.available) "The desktop's theme, ${follow.detail}, and whatever it changes to" else "${follow.detail}, so there is no theme to follow",
                style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (follow.available) ThemeSwatch(follow.palette, Modifier.padding(start = 12.dp).size(width = 72.dp, height = 16.dp))
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (option in options) {
            if (option.slug == null) continue
            ThemeCard(option, onClick = { look.chooseTheme(option.slug) })
        }
    }
}

/** A theme as a small picture of itself: its own background, a few lines in its own colors, and its name. */
@Composable
private fun ThemeCard(option: ThemeOption, onClick: () -> Unit) {
    val palette = option.palette
    val shape = RoundedCornerShape(10.dp)
    val outline = if (option.current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Column(
        Modifier.width(THEME_CARD_WIDTH)
            .clip(shape)
            .background(palette.color("background"))
            .border(if (option.current) 2.dp else 1.dp, outline, shape)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { selected = option.current }
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // What a prompt and some output look like in it, without the letters.
        val terminal = remember(palette) { palette.terminalColors() }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Bar(hexToColor(terminal.ansi[2]), 18.dp)
            Bar(hexToColor(terminal.ansi[4]), 34.dp)
            Bar(hexToColor(terminal.foreground), 46.dp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Bar(hexToColor(terminal.ansi[1]), 26.dp)
            Bar(hexToColor(terminal.ansi[3]), 22.dp)
            Bar(hexToColor(terminal.ansi[5]), 30.dp)
            Bar(hexToColor(terminal.ansi[6]), 14.dp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                option.title, style = MaterialTheme.typography.labelLarge, color = palette.color("foreground"),
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            Box(Modifier.size(10.dp).clip(CircleShape).background(palette.color("accent")))
        }
    }
}

private val THEME_CARD_WIDTH = 150.dp

@Composable
private fun Bar(color: Color, width: androidx.compose.ui.unit.Dp) {
    Box(Modifier.size(width = width, height = 5.dp).clip(RoundedCornerShape(2.dp)).background(color))
}

/** A theme in one line: its background, with its foreground, its accent and four of its colors on it. */
@Composable
internal fun ThemeSwatch(palette: OmarchyPalette, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(5.dp)
    Row(
        modifier.clip(shape).background(palette.color("background")).border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        for (name in listOf("foreground", "accent", "red", "green", "yellow", "blue")) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(palette.color(name)))
        }
    }
}

@Composable
private fun FontChoices(look: AppearanceState) {
    val colors = MaterialTheme.colorScheme
    val options = look.fonts
    val current = options.firstOrNull { it.current }
    var open by remember { mutableStateOf(false) }

    SettingLine("Family") {
        Box {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.widthIn(min = 200.dp)
                    .clip(MaterialTheme.shapes.small)
                    .border(1.dp, colors.outlineVariant, MaterialTheme.shapes.small)
                    .clickable(role = Role.DropdownList) { open = true }
                    .heightIn(min = 40.dp)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Column(Modifier.weight(1f, fill = false)) {
                    Text(current?.title.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    current?.detail?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                Text("▾", color = colors.onSurfaceVariant, modifier = Modifier.padding(start = 10.dp))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                for (option in options) {
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(option.title, color = if (option.current) colors.primary else colors.onSurface)
                                Text(option.detail, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                            }
                        },
                        onClick = { open = false; look.chooseFont(option.family) },
                    )
                }
            }
        }
    }

    val weights = look.weights
    if (weights.size > 1) {
        SettingLine("Weight") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (weight in weights) Choice(fontWeightName(weight), selected = weight == look.fontWeight) { look.chooseFontWeight(weight) }
            }
        }
    }

    SettingLine("Size") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Choice("−", selected = false, enabled = look.fontSize > RexUiState.FONT_SIZES.first) { look.chooseFontSize(look.fontSize - 1) }
            Text(look.fontSize.toString(), style = MaterialTheme.typography.bodyMedium, color = colors.onSurface, modifier = Modifier.widthIn(min = 28.dp).padding(horizontal = 4.dp))
            Choice("+", selected = false, enabled = look.fontSize < RexUiState.FONT_SIZES.last) { look.chooseFontSize(look.fontSize + 1) }
            if (look.fontSize != RexUiState.DEFAULT_FONT_SIZE) Choice("Reset", selected = false) { look.chooseFontSize(RexUiState.DEFAULT_FONT_SIZE) }
        }
    }

    FontSample(look)
}

/** A few lines as a terminal would draw them: in its font, size and weight, on the theme's terminal colors. */
@Composable
private fun FontSample(look: AppearanceState) {
    val palette = look.palette
    val terminal = remember(palette) { palette.terminalColors() }
    val font = LocalTerminalFont.current
    val shape = RoundedCornerShape(8.dp)
    val style = TextStyle(
        fontFamily = font?.family ?: MaterialTheme.typography.bodyMedium.fontFamily,
        fontWeight = font?.weight ?: FontWeight.Normal,
        fontSize = look.fontSize.sp,
        color = hexToColor(terminal.foreground),
    )
    val bold = FontWeight(boldWeightFor(look.fontWeight))
    Column(Modifier.fillMaxWidth().clip(shape).background(hexToColor(terminal.background)).border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape).padding(12.dp)) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = hexToColor(terminal.ansi[2]))) { append("~/dev/tono") }
                append(" ")
                withStyle(SpanStyle(color = hexToColor(terminal.ansi[4]))) { append("main") }
                append(" \$ ls -l 0O 1lI {}[] => !=")
            },
            style = style, maxLines = 1, softWrap = false,
        )
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = bold)) { append("bold ") }
                for ((index, name) in listOf("red", "green", "yellow", "blue", "magenta", "cyan").withIndex()) {
                    withStyle(SpanStyle(color = hexToColor(terminal.ansi[index + 1]))) { append("$name ") }
                }
            },
            style = style, maxLines = 1, softWrap = false,
        )
    }
}

/** A setting's name, and beside it what sets it. */
@Composable
private fun SettingLine(name: String, control: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(72.dp))
        control()
    }
}

/** One of a few things to choose between, or a small button. */
@Composable
private fun Choice(text: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier.clip(MaterialTheme.shapes.small)
            .background(if (selected) colors.secondaryContainer else Color.Transparent)
            .border(1.dp, if (selected) colors.primary else colors.outlineVariant, MaterialTheme.shapes.small)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { this.selected = selected }
            .alpha(if (enabled) 1f else 0.4f)
            .heightIn(min = 40.dp).widthIn(min = 40.dp)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (selected) colors.onSecondaryContainer else colors.onSurface)
    }
}
