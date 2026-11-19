package org.example.kotlintest

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import org.example.kotlintest.rex.TerminalLink
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.example.kotlintest.rex.nowMillis
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.IntOffset
import org.example.kotlintest.ghostty.TerminalImage
import org.example.kotlintest.ghostty.TerminalImagePlacement
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Image as SkiaImage
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.areAnyPressed
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import org.example.kotlintest.ghostty.MouseAction
import org.example.kotlintest.ghostty.MouseButton
import org.example.kotlintest.ghostty.MouseInput
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.example.kotlintest.ghostty.SelectedCells
import org.example.kotlintest.ghostty.TerminalCursor
import org.example.kotlintest.ghostty.TerminalFrame
import org.example.kotlintest.ghostty.TerminalRow
import org.example.kotlintest.ghostty.TerminalRun
import org.example.kotlintest.rex.GridSize
import org.example.kotlintest.rex.RexKey
import org.example.kotlintest.rex.TerminalSession
import org.example.kotlintest.ghostty.SearchHighlights
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Draws a live terminal and takes its keyboard input.
 *
 * The terminal is drawn at the size the server says it is. [onDesiredGrid] reports the size
 * that would fill this view instead, which the caller applies if it controls the terminal.
 *
 * @param focused whether this is the terminal the user is typing into
 * @param onKey a key was pressed, released, or is repeating while this view had focus
 * @param onText text was typed that no key press stands for, as a keyboard on a screen does
 * @param focusRequests change it to hand the keyboard back to this view, if it is the focused one
 * @param controlHeld whether Control is down, which marks the link under the pointer, to be opened with a click
 * @param onOpenLink a link was clicked with Control held; the address is one of the kinds that are opened
 * @param onMenu the right button asked for the pane's menu, at this point of the view
 * @param topEndInset how far down the top right corner is taken by something drawn over this view, which the search bar keeps clear of
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TerminalView(
    terminal: TerminalSession,
    focused: Boolean,
    onFocusRequested: () -> Unit,
    onKey: (RexKey) -> Unit,
    onDesiredGrid: (GridSize) -> Unit,
    modifier: Modifier = Modifier,
    onText: (String) -> Unit = {},
    onPointerOver: () -> Unit = {},
    fontSize: TextUnit = 13.sp,
    focusRequests: Int = 0,
    controlHeld: Boolean = false,
    onOpenLink: (url: String) -> Unit = {},
    topEndInset: Dp = 0.dp,
    middlePastes: Boolean = false,
    onSelected: () -> Unit = {},
    onMiddlePaste: () -> Unit = {},
    onMenu: (at: Offset) -> Unit = {},
) {
    val frame by terminal.frame.collectAsState()
    val fontFamily = MaterialTheme.typography.bodyMedium.fontFamily ?: FontFamily.Monospace
    val style = remember(fontFamily, fontSize) { TextStyle(fontFamily = fontFamily, fontSize = fontSize) }
    val measurer = rememberTextMeasurer(cacheSize = 0) // rows are cached below, by identity
    val cell = remember(style, measurer) { measureCell(measurer, style) }
    val layouts = remember(style, cell) { RowLayouts(cell) }
    // Where fonts arrive after the text that needs them (see fontsArriveLater), rows drawn
    // without one are laid out again once it has had time to come, and this says to redraw.
    var laterFonts by remember { mutableIntStateOf(0) }
    if (fontsArriveLater) {
        LaunchedEffect(layouts) {
            while (true) {
                delay(LATER_FONT_CHECK_MILLIS)
                if (layouts.forgetRowsAwaitingFonts(nowMillis())) laterFonts++
            }
        }
    }
    val pictures = remember(terminal) { Pictures() }

    var size by remember { mutableStateOf(IntSize.Zero) }
    // Wait for a resize to settle before asking for a new grid. Keyed on the terminal too:
    // going to another tab puts a different terminal in the same space, and its size has to
    // be said even though the space didn't change.
    LaunchedEffect(terminal, size, cell) {
        if (size.width <= 0 || size.height <= 0) return@LaunchedEffect
        delay(150)
        val columns = (size.width / cell.width).toInt()
        val rows = (size.height / cell.height).toInt()
        if (columns >= 2 && rows >= 1) onDesiredGrid(GridSize(columns, rows, cell.width.roundToInt(), cell.height.roundToInt()))
    }

    fun input(position: Offset, action: MouseAction, button: MouseButton?, event: PointerEvent? = null): MouseInput {
        // The encoder counts in whole pixels and cells are drawn a fraction wide, so the
        // position is restated on a whole-pixel grid, where it falls in the same cell.
        val cellWidth = cell.width.roundToInt().coerceAtLeast(1)
        val cellHeight = cell.height.roundToInt().coerceAtLeast(1)
        val modifiers = event?.keyboardModifiers
        return MouseInput(
            action, button,
            x = position.x / cell.width * cellWidth,
            y = position.y / cell.height * cellHeight,
            cellWidth = cellWidth, cellHeight = cellHeight,
            shift = modifiers?.isShiftPressed == true, control = modifiers?.isCtrlPressed == true, alt = modifiers?.isAltPressed == true,
            anyButtonDown = event?.buttons?.areAnyPressed == true,
        )
    }

    fun report(event: PointerEvent, action: MouseAction, button: MouseButton?, scrollRows: Int = 0) {
        // Wherever it is: the terminal knows its own edges, and a selection is dragged past them.
        val position = event.changes.firstOrNull()?.position ?: return
        terminal.mouse(input(position, action, button, event), scrollRows, middlePastes)
    }

    // A selection the pointer finished, and a middle click that asks for a paste: what
    // either does is a setting's to say (see SelectionCopy.kt).
    val selected by rememberUpdatedState(onSelected)
    val middlePaste by rememberUpdatedState(onMiddlePaste)
    // And a press of the right button that asks for the pane's menu, which opens where the press was.
    val menu by rememberUpdatedState(onMenu)
    val rightPress = remember(terminal) { RightPress() }
    LaunchedEffect(terminal) {
        terminal.gestures.collect { gesture ->
            when (gesture) {
                TerminalSession.Gesture.Selected -> selected()
                TerminalSession.Gesture.Paste -> middlePaste()
                TerminalSession.Gesture.Menu -> menu(rightPress.position)
            }
        }
    }

    // Links. With Control held the terminal is told where the pointer is, and says which
    // link is there, if any: it is underlined, and where it leads is shown beside it, since
    // the text of a link can say anything. A click then opens it (see TerminalPointer).
    val link by terminal.link.collectAsState()
    val pointing = remember(terminal) { Pointing() }
    fun point(position: Offset?, control: Boolean) {
        pointing.position = position
        val at = position?.takeIf { control }
        if (at != null) terminal.pointAt(input(at, MouseAction.Motion, null)) else if (pointing.told) terminal.pointAt(null)
        pointing.told = at != null
    }
    // Control going down or coming up with the pointer at rest, which no pointer event says.
    LaunchedEffect(terminal, controlHeld) { point(pointing.position, controlHeld) }
    DisposableEffect(terminal) { onDispose { terminal.pointAt(null) } }
    val openLink by rememberUpdatedState(onOpenLink)
    LaunchedEffect(terminal) { terminal.opened.collect { openLink(it) } }
    // Where a link leads is shown once the pointer has been on it a moment, as Rex Rune
    // does, so that Control pressed for a shortcut doesn't flash it.
    var linkTargetShown by remember { mutableStateOf(false) }
    LaunchedEffect(link?.url) {
        linkTargetShown = false
        if (link != null) {
            delay(LINK_TARGET_DELAY_MILLIS)
            linkTargetShown = true
        }
    }

    // Search. Its bar floats over a corner (SearchBar), and while its field is being typed
    // in, that is where the keyboard goes back to, not the terminal.
    val search by terminal.search.collectAsState()
    val searchField = remember { FocusRequester() }
    var searchTyping by remember(terminal) { mutableStateOf(false) }
    var searchOpenedSeen by remember(terminal) { mutableIntStateOf(terminal.search.value?.opened ?: 0) }
    var searchBarSize by remember { mutableStateOf(IntSize.Zero) }
    var searchBarAtBottom by remember(terminal) { mutableStateOf(false) }
    val matchColor = MaterialTheme.colorScheme.primary
    val topEndInsetPx = with(LocalDensity.current) { topEndInset.roundToPx() }

    val focusRequester = remember { FocusRequester() }
    var hasKeyboard by remember { mutableStateOf(false) }
    val searchOpened = search?.opened
    LaunchedEffect(focused, focusRequests, searchOpened) {
        when (searchOpened) {
            // Closed: the bar starts in its own corner again next time.
            null -> { searchTyping = false; searchBarAtBottom = false }
            // Asked for, by its key or from the palette: the field is what is typed in next.
            searchOpenedSeen -> {}
            else -> { searchOpenedSeen = searchOpened; searchTyping = true }
        }
        if (!focused) return@LaunchedEffect
        if (searchTyping) searchField.requestFocus() else focusRequester.requestFocus()
    }
    val keysDown = remember { HashSet<Long>() }
    // Preview, so that Tab and the arrow keys reach the terminal instead of moving focus.
    val keys = Modifier.onPreviewKeyEvent { event ->
        val repeat = when (event.type) {
            KeyEventType.KeyDown -> !keysDown.add(event.key.keyCode)
            KeyEventType.KeyUp -> { keysDown.remove(event.key.keyCode); false }
            else -> return@onPreviewKeyEvent false
        }
        val key = rexKey(event, repeat) ?: return@onPreviewKeyEvent false
        onKey(key)
        true
    }

    // On a touch screen there are no keys until a text field has the keyboard, so a terminal
    // that is touched gives the keyboard to a field of its own: see ScreenKeyboard.kt.
    val screenField = remember { FocusRequester() }
    val screenTyping = remember { ScreenTyping() }
    var touched by remember { mutableStateOf(false) } // whether the last press was a finger
    var screenKeyboard by remember { mutableStateOf(false) } // whether that field has the keyboard
    var controlHeld by remember { mutableStateOf(false) }
    fun press(name: String, control: Boolean = false) {
        for (pressed in listOf(true, false)) onKey(rexKeyNamed(name, typed = null, pressed = pressed, repeat = false, shift = false, control = control, alt = false, meta = false))
    }
    fun typed(edit: ScreenEdit) {
        repeat(edit.backspaces) { press("backspace") }
        var text = edit.text
        // Control, from the row of extra keys, goes with the next key typed.
        if (controlHeld && text.isNotEmpty()) {
            controlHeld = false
            controlKeyNamed(text[0])?.let { press(it, control = true); text = text.substring(1) }
        }
        // A new line from a keyboard is the Enter key; the rest is text as it stands.
        text.split('\n').forEachIndexed { index, line ->
            if (index > 0) press("enter")
            if (line.isNotEmpty()) onText(line)
        }
    }

    Column(modifier.fillMaxSize()) {
    Box(Modifier.weight(1f).fillMaxWidth()) {
    Canvas(
        Modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { size = it }
            .focusRequester(focusRequester)
            .onFocusChanged { hasKeyboard = it.isFocused }
            .focusable()
            .then(keys)
            .pointerHoverIcon(if (link != null) PointerIcon.Hand else PointerIcon.Default)
            .onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) { event -> touched = event.changes.any { it.type == PointerType.Touch } }
            .pointerInput(Unit) {
                detectTapGestures {
                    onFocusRequested()
                    searchTyping = false
                    if (touched) screenField.requestFocus() else focusRequester.requestFocus()
                }
            }
            // The pointer, for programs that track it (Neovim, htop, tmux). Each event is
            // offered to the terminal, which knows what its program asked to be told.
            .onPointerEvent(PointerEventType.Press) { event ->
                mouseButton(event.button)?.let { button ->
                    if (button == MouseButton.Right) event.changes.firstOrNull()?.let { rightPress.position = it.position }
                    report(event, MouseAction.Press, button)
                }
            }
            .onPointerEvent(PointerEventType.Release) { event -> mouseButton(event.button)?.let { report(event, MouseAction.Release, it) } }
            // With the button held, crossing the view's edge is one more step of a drag: a
            // selection is dragged past the edge, and the crossing itself arrives as one of these.
            .onPointerEvent(PointerEventType.Enter) { event ->
                onPointerOver()
                point(event.changes.firstOrNull()?.position, event.keyboardModifiers.isCtrlPressed)
                if (event.buttons.isPrimaryPressed) report(event, MouseAction.Motion, MouseButton.Left)
            }
            .onPointerEvent(PointerEventType.Exit) { event ->
                point(null, control = false)
                if (event.buttons.isPrimaryPressed) report(event, MouseAction.Motion, MouseButton.Left)
            }
            .onPointerEvent(PointerEventType.Move) { event ->
                onPointerOver()
                point(event.changes.firstOrNull()?.position, event.keyboardModifiers.isCtrlPressed)
                val held = when {
                    event.buttons.isPrimaryPressed -> MouseButton.Left
                    event.buttons.isSecondaryPressed -> MouseButton.Right
                    event.buttons.isTertiaryPressed -> MouseButton.Middle
                    else -> null
                }
                report(event, MouseAction.Motion, held)
            }
            .onPointerEvent(PointerEventType.Scroll) { event ->
                val delta = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                if (delta == 0f) return@onPointerEvent
                // A notch is a press of a wheel button to a program that tracks the mouse,
                // and otherwise scrolls this terminal's own history.
                val rows = (delta * SCROLL_ROWS_PER_NOTCH).roundToInt().coerceIn(-50, 50).let { if (it == 0) if (delta > 0) 1 else -1 else it }
                report(event, MouseAction.Press, if (delta < 0) MouseButton.WheelUp else MouseButton.WheelDown, scrollRows = rows)
            },
    ) {
        val current = frame
        if (current == null) return@Canvas
        laterFonts // read, so that a change draws again
        drawTerminal(
            current, cell, layouts, measurer, style, pictures,
            matches = search?.highlights, matchColor = matchColor, link = link?.cells.orEmpty(),
            cursorActive = focused && (hasKeyboard || screenKeyboard),
        )
    }
    link?.takeIf { linkTargetShown }?.let { LinkTarget(it, cell) }
    search?.let { current ->
        // Out of the selected match's way: see searchBarAtBottom.
        searchBarAtBottom = searchBarAtBottom(
            searchBarAtBottom, current.highlights.selected, cell.width, cell.height,
            size.width, size.height, searchBarSize.width, searchBarSize.height, topEndInsetPx,
        )
        SearchBar(
            search = current,
            field = searchField,
            onQuery = terminal::search,
            onStep = terminal::searchStep,
            onClose = terminal::closeSearch,
            onTyping = { typing ->
                if (typing) {
                    searchTyping = true
                    if (!focused) onFocusRequested()
                }
            },
            modifier = Modifier
                .align(if (searchBarAtBottom) Alignment.BottomEnd else Alignment.TopEnd)
                .padding(top = if (searchBarAtBottom) 0.dp else topEndInset)
                .onSizeChanged { searchBarSize = it },
        )
    }
    // A keyboard that has real keys as well (a tablet's) sends them here while this has the focus.
    ScreenKeyboardField(screenTyping, ::typed, Modifier.focusRequester(screenField).onFocusChanged { screenKeyboard = it.isFocused; if (!it.isFocused) controlHeld = false }.then(keys))
    }
    if (screenKeyboard) {
        ScreenKeyRow(controlHeld, onKey = { key ->
            when (key) {
                ScreenKey.Control -> controlHeld = !controlHeld
                // Giving the keyboard back to the terminal itself, which has no use for one on a screen, puts it away.
                ScreenKey.Hide -> focusRequester.requestFocus()
                else -> key.rexName?.let { press(it, control = controlHeld.also { controlHeld = false }) }
            }
        })
    }
    }
}

/** The size of one character cell, in pixels. */
internal class CellSize(val width: Float, val height: Float)

/**
 * Draws one frame of a terminal: its background, images and text, and over them what is
 * marked on it. Everything is placed by its cell, [cell] being the size of one.
 */
internal fun DrawScope.drawTerminal(
    frame: TerminalFrame,
    cell: CellSize,
    layouts: RowLayouts,
    measurer: TextMeasurer,
    style: TextStyle,
    pictures: Pictures,
    matches: SearchHighlights? = null,
    matchColor: Color = Color.Unspecified,
    link: List<TerminalLink.Cells> = emptyList(),
    cursorActive: Boolean = false,
) {
    drawRect(Color(0xFF000000 or frame.background.toLong()))
    layouts.retain(frame.rows)
    pictures.retain(frame.images)
    // Images a program asked to have behind its text go down first, the rest on top of it.
    frame.images.forEach { if (it.layer != TerminalImagePlacement.Layer.AboveText) drawImage(it, frame, cell, pictures) }
    frame.rows.forEachIndexed { rowIndex, row ->
        drawRow(row, rowIndex, cell, layouts.of(row, measurer, style))
    }
    frame.images.forEach { if (it.layer == TerminalImagePlacement.Layer.AboveText) drawImage(it, frame, cell, pictures) }
    matches?.let { found ->
        found.others.forEach { drawMatch(it, cell, matchColor, selected = false) }
        found.selected.forEach { drawMatch(it, cell, matchColor, selected = true) }
    }
    frame.selection.forEach { drawSelection(it, frame, cell) }
    link.forEach { drawLinkUnderline(it, frame, cell) }
    frame.cursor?.let { drawCursor(it, frame, cell, active = cursorActive) }
}

internal fun measureCell(measurer: TextMeasurer, style: TextStyle): CellSize {
    // How far the text itself runs, over a sample long enough to average out what the
    // layout does to each glyph. Not the size of the layout: that is the same width rounded
    // up to a whole pixel, which made every cell up to a twentieth of a pixel wider than
    // its glyph when the sample was twenty long, and text is drawn a run at a time, so by
    // column 120 it sat most of a cell to the left of the cell it belonged to.
    val sample = measurer.measure("M".repeat(CELL_SAMPLE), style, softWrap = false, maxLines = 1)
    return CellSize(sample.getHorizontalPosition(CELL_SAMPLE, usePrimaryDirection = true) / CELL_SAMPLE, ceil(sample.size.height.toFloat()))
}

private const val CELL_SAMPLE = 100

/** A piece of a row's text, laid out, and the column of the cell it starts in. */
internal class PlacedText(val column: Int, val layout: TextLayoutResult)

/**
 * The laid-out text of each row on screen, for cells of the size given. A row object is
 * reused while its content is unchanged, so its layout is too.
 */
internal class RowLayouts(private val cell: CellSize) {
    private var cache = HashMap<TerminalRow, List<PlacedText>>()

    /** Forgets rows that are no longer on screen. */
    fun retain(rows: List<TerminalRow>) {
        if (cache.size <= rows.size) return
        val kept = HashMap<TerminalRow, List<PlacedText>>()
        for (row in rows) cache[row]?.let { kept[row] = it }
        cache = kept
    }

    fun of(row: TerminalRow, measurer: TextMeasurer, style: TextStyle): List<PlacedText> =
        cache.getOrPut(row) {
            if (fontsArriveLater && row !in awaitingFonts && row.runs.any { run -> run.text.any(::mayNeedAnotherFont) }) awaitingFonts[row] = nowMillis()
            buildList { for (run in row.runs) place(run, measurer, style.merge(run)) }
        }

    /**
     * Lays out a run's text. A run of plain characters is one piece, which the font lays
     * out a cell to the character, and that is checked: where it comes out wider or narrower
     * than its cells (a style of the font with a width of its own, or a character taken
     * from another font), each character is laid out by itself and placed in its own cell,
     * so that the difference can't add up along the run.
     */
    private fun MutableList<PlacedText>.place(run: TerminalRun, measurer: TextMeasurer, style: TextStyle) {
        val text = run.text
        if (text.isBlank()) return
        val whole = measurer.measure(AnnotatedString(text), style, softWrap = false, maxLines = 1)
        // Going by the layout's size, which is its text's width rounded up to a whole pixel:
        // it is already worked out, and asking the layout where a character is costs enough
        // to show when a whole screen of runs is laid out at once.
        val over = whole.size.width - run.cells * cell.width
        if (!run.oneToACell || (over > -OFF_GRID_PIXELS && over < 1f + OFF_GRID_PIXELS)) {
            add(PlacedText(run.column, whole))
            return
        }
        val decorated = run.underline || run.strikethrough
        text.forEachIndexed { index, char ->
            if (decorated || !char.isWhitespace()) add(PlacedText(run.column + index, measurer.measure(AnnotatedString(char.toString()), style, softWrap = false, maxLines = 1)))
        }
    }

    /** When each row that may be waiting for a font was first laid out. */
    private val awaitingFonts = HashMap<TerminalRow, Long>()

    /**
     * Forgets the layout of rows whose font has had time to arrive since they were laid out,
     * so that they are laid out again with it. Each row is tried a few times, further and
     * further apart, and then left as it is. Returns whether any row was forgotten.
     */
    fun forgetRowsAwaitingFonts(now: Long): Boolean {
        if (awaitingFonts.isEmpty()) return false
        var forgot = false
        val rows = awaitingFonts.entries.iterator()
        while (rows.hasNext()) {
            val (row, since) = rows.next()
            val age = now - since
            when {
                row !in cache -> rows.remove() // gone from the screen
                age > LATER_FONT_TRIES.last() + LATER_FONT_CHECK_MILLIS -> rows.remove()
                LATER_FONT_TRIES.any { age >= it && age < it + LATER_FONT_CHECK_MILLIS } -> { cache.remove(row); forgot = true }
            }
        }
        return forgot
    }
}

/** How often rows waiting for a font are looked at, and how long after a row was laid out it is laid out again. */
private const val LATER_FONT_CHECK_MILLIS = 500L

/** How far from its cells the end of a run may come out, in pixels, before its characters are placed one by one. */
private const val OFF_GRID_PIXELS = 0.5f
private val LATER_FONT_TRIES = listOf(1_000L, 2_500L, 6_000L, 15_000L)

/**
 * Whether a character is one the desktop's font may not have, such as Chinese, Arabic or an
 * emoji. What a monospace font for terminals can be counted on for is left out: Latin,
 * Greek and Cyrillic, punctuation, arrows and mathematics, the line-drawing characters, and
 * the private-use symbols that prompts and status lines are full of.
 */
private fun mayNeedAnotherFont(char: Char): Boolean = char.isSurrogate() || CERTAIN_RANGES.none { char.code in it }

private val CERTAIN_RANGES = listOf(0x0000..0x052F, 0x1E00..0x206F, 0x2190..0x22FF, 0x2500..0x25FF, 0xE000..0xF8FF)

private fun TextStyle.merge(run: TerminalRun): TextStyle = copy(
    color = Color(0xFF000000 or run.foreground.toLong()),
    fontWeight = if (run.bold) FontWeight.Bold else FontWeight.Normal,
    fontStyle = if (run.italic) FontStyle.Italic else FontStyle.Normal,
    textDecoration = when {
        run.underline && run.strikethrough -> TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
        run.underline -> TextDecoration.Underline
        run.strikethrough -> TextDecoration.LineThrough
        else -> TextDecoration.None
    },
)

private fun DrawScope.drawRow(row: TerminalRow, rowIndex: Int, cell: CellSize, texts: List<PlacedText>) {
    val top = rowIndex * cell.height
    for (run in row.runs) {
        val background = run.background ?: continue
        // Whole pixels, and a pixel of overlap, so adjacent backgrounds leave no seams.
        drawRect(
            color = Color(0xFF000000 or background.toLong()),
            topLeft = Offset((run.column * cell.width).toInt().toFloat(), top),
            size = Size(ceil(run.cells * cell.width) + 1f, cell.height),
        )
    }
    // Over all the row's backgrounds, so that the next run's doesn't cut into a glyph that leans over its cell's edge.
    for (text in texts) drawText(text.layout, topLeft = Offset(text.column * cell.width, top))
}

/**
 * Marks selected cells with a wash of the foreground color over them, so that the text
 * keeps its own colors and stays readable whatever they are.
 */
private fun DrawScope.drawSelection(cells: SelectedCells, frame: TerminalFrame, cell: CellSize) {
    val left = (cells.firstColumn * cell.width).toInt().toFloat()
    // Whole pixels, and up to the next cell's edge, so that rows and neighbors meet without seams.
    val right = ceil((cells.lastColumn + 1) * cell.width)
    drawRect(
        color = Color(0xFF000000 or frame.foreground.toLong()).copy(alpha = SELECTION_ALPHA),
        topLeft = Offset(left, cells.row * cell.height),
        size = Size(right - left, cell.height),
    )
}

private const val SELECTION_ALPHA = 0.3f

/**
 * Marks cells a search matched, in the app's accent color: a wash over them, so the text
 * still reads, stronger and with a line round it for the selected match.
 */
private fun DrawScope.drawMatch(cells: SelectedCells, cell: CellSize, color: Color, selected: Boolean) {
    val left = (cells.firstColumn * cell.width).toInt().toFloat()
    val right = ceil((cells.lastColumn + 1) * cell.width)
    val topLeft = Offset(left, cells.row * cell.height)
    val size = Size(right - left, cell.height)
    drawRect(color.copy(alpha = if (selected) SELECTED_MATCH_ALPHA else MATCH_ALPHA), topLeft, size)
    if (selected) drawRect(color, topLeft + Offset(0.5f, 0.5f), Size(size.width - 1f, size.height - 1f), style = Stroke(width = 1.5f))
}

private const val MATCH_ALPHA = 0.3f
private const val SELECTED_MATCH_ALPHA = 0.5f

/** Where the pointer was last seen over a terminal's view, and whether the terminal was told, for finding the link under it. */
private class Pointing {
    var position: Offset? = null
    var told = false
}

/** Where the right button last went down over a terminal's view, which is where its menu opens if the terminal asks for one. */
private class RightPress {
    var position = Offset.Zero
}

/** Underlines the cells of a link that a click would open, in the terminal's foreground color whatever the text's own. */
private fun DrawScope.drawLinkUnderline(cells: TerminalLink.Cells, frame: TerminalFrame, cell: CellSize) {
    val left = (cells.firstColumn * cell.width).toInt().toFloat()
    val right = ceil((cells.lastColumn + 1) * cell.width)
    val thickness = (cell.height / 14f).roundToInt().coerceAtLeast(1).toFloat()
    drawRect(
        color = Color(0xFF000000 or frame.foreground.toLong()),
        topLeft = Offset(left, (cells.row + 1) * cell.height - thickness - 1f),
        size = Size(right - left, thickness),
    )
}

/**
 * Says where a link leads, under it, or over it when there is no room under. The address is
 * shown whole and as it will be opened, since that is the point of showing it; a very long
 * one loses its end.
 */
@Composable
private fun LinkTarget(link: TerminalLink, cell: CellSize) {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.extraSmall
    // Drawn without taking the pointer, which stays the terminal's.
    Box(
        Modifier
            .layout { measurable, constraints ->
                val label = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                val gap = 4.dp.roundToPx()
                val below = ((link.cells.last().row + 1) * cell.height).roundToInt() + gap
                val above = (link.cells.first().row * cell.height).roundToInt() - gap - label.height
                val x = (link.cells.last().firstColumn * cell.width).roundToInt().coerceAtMost(constraints.maxWidth - label.width).coerceAtLeast(0)
                val y = if (below + label.height <= constraints.maxHeight || above < 0) below else above
                layout(constraints.maxWidth, constraints.maxHeight) { label.place(x, y) }
            }
            .shadow(4.dp, shape)
            .background(colors.surfaceContainerHighest, shape)
            .padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        Text(link.url, style = MaterialTheme.typography.labelMedium, color = colors.onSurface, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

/** How long the pointer rests on a link before where it leads is shown. */
private const val LINK_TARGET_DELAY_MILLIS = 300L

private fun DrawScope.drawCursor(cursor: TerminalCursor, frame: TerminalFrame, cell: CellSize, active: Boolean) {
    val color = Color(0xFF000000 or (cursor.color ?: frame.foreground).toLong())
    val topLeft = Offset(cursor.column * cell.width, cursor.row * cell.height)
    val whole = Size(cell.width, cell.height)
    when {
        // An outline while the terminal isn't being typed into, whatever its shape.
        !active || cursor.shape == TerminalCursor.Shape.HollowBlock -> drawRect(color, topLeft, whole, style = Stroke(width = 1.5f))
        cursor.shape == TerminalCursor.Shape.Bar -> drawRect(color, topLeft, Size(2f, cell.height))
        cursor.shape == TerminalCursor.Shape.Underline -> drawRect(color, topLeft + Offset(0f, cell.height - 2f), Size(cell.width, 2f))
        else -> drawRect(color.copy(alpha = 0.55f), topLeft, whole)
    }
}

private const val SCROLL_ROWS_PER_NOTCH = 3f

/**
 * The bitmaps made from the images a terminal shows, kept for as long as each is on screen.
 * An image object stands for one version of one image, so it is the key.
 */
internal class Pictures {
    private var made = HashMap<TerminalImage, ImageBitmap>()

    fun of(image: TerminalImage): ImageBitmap = made.getOrPut(image) {
        val info = ImageInfo(image.width, image.height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL)
        SkiaImage.makeRaster(info, image.rgba, image.width * 4).toComposeImageBitmap()
    }

    /** Forgets the bitmaps of images that are no longer shown. */
    fun retain(shown: List<TerminalImagePlacement>) {
        if (made.isEmpty()) return
        val kept = HashMap<TerminalImage, ImageBitmap>()
        for (placement in shown) made[placement.image]?.let { kept[placement.image] = it }
        made = kept
    }
}

/**
 * Draws an image where its program put it. A program sizes an image in the pixels of the
 * terminal's own cells, which are whatever the terminal's owner draws them at; this client
 * may draw cells another size, so the image is scaled to cover the same cells here.
 */
private fun DrawScope.drawImage(placement: TerminalImagePlacement, frame: TerminalFrame, cell: CellSize, pictures: Pictures) {
    if (placement.sourceWidth <= 0 || placement.sourceHeight <= 0 || placement.pixelWidth <= 0 || placement.pixelHeight <= 0) return
    val scaleX = if (frame.cellWidthPx > 0) cell.width / frame.cellWidthPx else 1f
    val scaleY = if (frame.cellHeightPx > 0) cell.height / frame.cellHeightPx else 1f
    drawImage(
        image = pictures.of(placement.image),
        srcOffset = IntOffset(placement.sourceX, placement.sourceY),
        srcSize = IntSize(placement.sourceWidth, placement.sourceHeight),
        dstOffset = IntOffset(
            (placement.column * cell.width + placement.offsetX * scaleX).roundToInt(),
            (placement.row * cell.height + placement.offsetY * scaleY).roundToInt(),
        ),
        dstSize = IntSize((placement.pixelWidth * scaleX).roundToInt().coerceAtLeast(1), (placement.pixelHeight * scaleY).roundToInt().coerceAtLeast(1)),
        filterQuality = FilterQuality.Medium,
    )
}

/** The terminal's name for a pointer button, or null for one it has none for. */
private fun mouseButton(button: PointerButton?): MouseButton? = when (button) {
    PointerButton.Primary -> MouseButton.Left
    PointerButton.Secondary -> MouseButton.Right
    PointerButton.Tertiary -> MouseButton.Middle
    else -> null
}
