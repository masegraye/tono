package org.example.kotlintest

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import org.example.kotlintest.ghostty.SelectedCells
import org.example.kotlintest.rex.TerminalSearch

/**
 * The bar a terminal's search is typed into, which floats over a corner of the terminal and
 * never changes its size: the text, the selected match's place among them all, and buttons
 * for the next match, the previous one, and closing.
 *
 * As in Rex Rune, "next" is the next older match, further up, so its arrow points up; Enter
 * in the field does the same, Shift+Enter goes the other way, and Escape closes.
 *
 * @param field asked for the keyboard when the search is opened
 * @param onQuery the text to look for changed
 * @param onStep the next match was asked for, or the previous
 * @param onTyping the field took the keyboard, or gave it up
 */
@Composable
internal fun SearchBar(
    search: TerminalSearch,
    field: FocusRequester,
    onQuery: (String) -> Unit,
    onStep: (forward: Boolean) -> Unit,
    onClose: () -> Unit,
    onTyping: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.small
    var text by remember { mutableStateOf(TextFieldValue(search.query, TextRange(0, search.query.length))) }
    // Asked for again: what is there is selected, so that typing replaces it.
    LaunchedEffect(search.opened) { text = text.copy(selection = TextRange(0, text.text.length)) }
    val canStep = search.total > 0
    Row(
        modifier
            .padding(SEARCH_BAR_MARGIN)
            .widthIn(max = 380.dp)
            .shadow(6.dp, shape)
            .background(colors.surfaceContainerHigh, shape)
            .border(1.dp, colors.outlineVariant, shape)
            // The pointer over the bar is the bar's, not the terminal's under it.
            .pointerInput(Unit) { detectTapGestures { field.requestFocus() } }
            .padding(start = 10.dp, end = 4.dp, top = 3.dp, bottom = 3.dp)
            .testTag("search-bar"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f, fill = false).widthIn(min = 120.dp), contentAlignment = Alignment.CenterStart) {
            if (text.text.isEmpty()) Text("Find", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant.copy(alpha = 0.6f))
            BasicTextField(
                value = text,
                onValueChange = { changed ->
                    val before = text.text
                    text = changed
                    if (changed.text != before) onQuery(changed.text)
                },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
                cursorBrush = SolidColor(colors.primary),
                modifier = Modifier
                    .widthIn(min = 120.dp)
                    .focusRequester(field)
                    .onFocusChanged { onTyping(it.isFocused) }
                    .semantics { contentDescription = "Find in terminal" }
                    .onPreviewKeyEvent { event ->
                        when (event.key) {
                            Key.Escape -> if (event.type == KeyEventType.KeyDown) onClose()
                            Key.Enter, Key.NumPadEnter -> if (event.type == KeyEventType.KeyDown) onStep(!event.isShiftPressed)
                            else -> return@onPreviewKeyEvent false
                        }
                        true
                    },
            )
        }
        Text(
            search.count,
            style = MaterialTheme.typography.labelMedium,
            color = if (search.complete && search.total == 0) colors.error else colors.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(start = 10.dp, end = 6.dp).testTag("search-count"),
        )
        ToolbarButton("Find Next", "Enter", onClick = { onStep(true) }, enabled = canStep, size = STATUS_BUTTON) {
            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = null, tint = it, modifier = Modifier.padding(2.dp))
        }
        ToolbarButton("Find Previous", "Shift+Enter", onClick = { onStep(false) }, enabled = canStep, size = STATUS_BUTTON) {
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = it, modifier = Modifier.padding(2.dp))
        }
        ToolbarButton("Close", "Escape", onClick = onClose, size = STATUS_BUTTON) {
            Icon(Icons.Filled.Close, contentDescription = null, tint = it, modifier = Modifier.padding(4.dp))
        }
    }
}

/** Between the search bar and the edges of the terminal it floats over. */
internal val SEARCH_BAR_MARGIN = 4.dp

/**
 * Which corner the search bar should be in so that it doesn't hide the selected match: the
 * top right, unless the match is under it there, and then the bottom right. As in Rex Rune,
 * once moved it stays until the selected match is under it again, rather than going back as
 * soon as it could. Where a match is under it in both corners, it stays where it is.
 *
 * Sizes are in pixels. [topInset] is how far down from the top the bar is kept, for
 * something else in that corner.
 *
 * @param atBottom where the bar is now
 * @param selected the selected match's cells on screen
 * @return whether the bar should be at the bottom
 */
internal fun searchBarAtBottom(
    atBottom: Boolean,
    selected: List<SelectedCells>,
    cellWidth: Float, cellHeight: Float,
    viewWidth: Int, viewHeight: Int,
    barWidth: Int, barHeight: Int,
    topInset: Int = 0,
): Boolean {
    if (selected.isEmpty() || barWidth <= 0 || barHeight <= 0) return atBottom
    val barLeft = (viewWidth - barWidth).toFloat()
    fun hidden(barTop: Float): Boolean = selected.any { cells ->
        val top = cells.row * cellHeight
        (cells.lastColumn + 1) * cellWidth > barLeft && cells.firstColumn * cellWidth < viewWidth &&
            top + cellHeight > barTop && top < barTop + barHeight
    }
    val hiddenAtTop = hidden(topInset.toFloat())
    val hiddenAtBottom = hidden((viewHeight - barHeight).toFloat())
    return when {
        atBottom && hiddenAtBottom && !hiddenAtTop -> false
        !atBottom && hiddenAtTop && !hiddenAtBottom -> true
        else -> atBottom
    }
}
