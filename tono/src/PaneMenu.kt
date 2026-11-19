package org.example.kotlintest

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import org.example.kotlintest.rex.Keymap
import org.example.kotlintest.rex.RexAction
import org.example.kotlintest.rex.RexHost
import kotlin.math.roundToInt

/*
 * The menu a pane gets from the right button, and from Control+Shift+Space.
 *
 * What is in it follows the menu Rex.app shows on a terminal, in its order and with its
 * words: Copy and Paste; the four splits and Zoom Pane; Close Pane and Reset Terminal. What
 * Rex.app's doesn't have and Rex Rune's does goes where Rex Rune has it (Select All after
 * Paste, Rename Pane after the layout, Clear Terminal beside Reset), and the rest of what
 * this app can do to a pane is put with what it is most like. Float Pane (Rex.app), and
 * Copy Pane ID, Download File and Reconnect Terminal (Rex Rune), are not here because this
 * app can't do them.
 *
 * Left out on purpose, and still on their keys and in the palette: focusing, moving and
 * resizing a pane by direction, scrolling and jumping between prompts, and Find Next and
 * Previous. Each is one step of something done several times running, which is what a key is
 * for, and a menu that closes after every step isn't.
 *
 * A row that can't be used just now stays in its place, dimmed, as in both references:
 * Copy with nothing selected, Balance Splits with one pane. The menu is the same every time
 * it opens, so where a thing is can be learned. The only rows that come and go are the two
 * the server performs, which an older server may not offer.
 */

/** A pane's menu while it is open. */
class PaneMenu(
    val host: RexHost,
    val blockId: String,
    /** Where the pointer asked for it, in pixels from the pane's corner. Null when a key did, and then it opens in that corner. */
    val at: Offset?,
)

/**
 * A row of the menu as it is written down.
 *
 * @param action the action it runs, one of this app's or one the server offers
 * @param title what it says. Rex.app's menu drops "Pane" where the menu itself says whose it is.
 * @param needsSelection whether it has something to do only while text is selected
 * @param server whether the action is the server's, and the row is there only if the server offers it
 */
internal class PaneMenuItem(val action: String, val title: String, val needsSelection: Boolean = false, val server: Boolean = false)

/** The menu: its rows in order, in the groups that lines part. */
internal val PANE_MENU: List<List<PaneMenuItem>> = listOf(
    listOf(
        PaneMenuItem("pane.copy", "Copy", needsSelection = true),
        PaneMenuItem("pane.paste", "Paste"),
        PaneMenuItem("pane.select_all", "Select All"),
    ),
    listOf(
        PaneMenuItem("client.find.open", "Find…"),
        PaneMenuItem("pane.insert_path", "Insert Path…"),
        PaneMenuItem("pane.go_to_directory", "Go to Directory…"),
    ),
    listOf(
        PaneMenuItem("pane.split.right", "Split Right"),
        PaneMenuItem("pane.split.left", "Split Left"),
        PaneMenuItem("pane.split.down", "Split Down"),
        PaneMenuItem("pane.split.up", "Split Up"),
        PaneMenuItem("pane.zoom", "Zoom Pane"),
        PaneMenuItem("pane.balance", "Balance Splits"),
    ),
    listOf(
        PaneMenuItem("pane.move_to_tab", "Move Pane to Tab…"),
        PaneMenuItem("pane.move_to_new_tab", "Move Pane to New Tab"),
        PaneMenuItem("pane.rename", "Rename Pane…"),
    ),
    listOf(
        PaneMenuItem("pane.close", "Close Pane"),
        PaneMenuItem(CLEAR_TERMINAL, "Clear Terminal", server = true),
        PaneMenuItem(RESET_TERMINAL, "Reset Terminal", server = true),
    ),
)

/** The server's own actions on a terminal, by the names it lists them under. */
internal const val CLEAR_TERMINAL = "com.superlogical.terminal.clear"
internal const val RESET_TERMINAL = "com.superlogical.terminal.reset"

/** A row of the menu as it is shown. */
internal data class PaneMenuRow(val action: String, val title: String, val shortcut: String?, val enabled: Boolean)

/**
 * The menu as it stands for one pane at one moment: [PANE_MENU] with each row marked as
 * usable or not, and the key that does the same beside it.
 *
 * @param actions this app's actions
 * @param serverActions the actions the server offers
 * @param isAvailable whether one of this app's actions can run now
 * @param hasTerminal whether the pane is a terminal that is there, which the server's actions need
 * @param hasSelection whether text is selected in it
 * @param zoomed whether the pane is zoomed, when the row that zooms says it will undo that
 * @param shortcutFor the key bound to an action, as it is shown
 */
internal fun paneMenuRows(
    actions: List<ClientAction>,
    serverActions: List<RexAction>,
    isAvailable: (ClientAction) -> Boolean,
    hasTerminal: Boolean,
    hasSelection: Boolean,
    zoomed: Boolean,
    shortcutFor: (action: String) -> String?,
): List<List<PaneMenuRow>> {
    val byName = actions.associateBy { it.name }
    return PANE_MENU.map { group ->
        group.mapNotNull { item ->
            val enabled = when {
                item.server -> if (serverActions.any { it.name == item.action }) hasTerminal else return@mapNotNull null
                else -> isAvailable(byName[item.action] ?: return@mapNotNull null) && (hasSelection || !item.needsSelection)
            }
            val title = if (item.action == "pane.zoom" && zoomed) "Unzoom Pane" else item.title
            PaneMenuRow(item.action, title, shortcutFor(item.action), enabled)
        }
    }.filter { it.isNotEmpty() }
}

/** The menu for the pane with the keyboard, read from the app as it is now. */
internal fun paneMenuRows(ui: RexUiState, commands: RexCommands, keymap: Keymap, hasSelection: Boolean): List<List<PaneMenuRow>> = paneMenuRows(
    actions = commands.actions,
    serverActions = keymap.serverActions,
    isAvailable = commands::isAvailable,
    hasTerminal = ui.focusedTerminal != null,
    hasSelection = hasSelection,
    zoomed = ui.window?.zoomedBlockId != null,
    shortcutFor = { keymap.shortcutFor(it)?.display() },
)

/**
 * The row the keyboard moves to from [from], going down or up through the rows that can be
 * used and round at either end. From no row (-1), down is the first and up the last. Null
 * when no row can be used.
 */
internal fun stepMenuRow(enabled: List<Boolean>, from: Int, down: Boolean): Int? {
    if (enabled.none { it }) return null
    var index = from
    repeat(enabled.size) {
        index = when {
            index !in enabled.indices -> if (down) 0 else enabled.lastIndex
            else -> (index + if (down) 1 else -1).mod(enabled.size)
        }
        if (enabled[index]) return index
    }
    return null
}

/**
 * Where a menu goes: its corner at the point asked for, opening to the right and downwards.
 * With no room to the right it opens to the left of the point, and with none below, above
 * it, as a desktop's menus do, so that the pointer is at a corner of the menu and not on
 * one of its rows. With room neither below nor above it is moved up until it fits. Never
 * off the top or the left.
 */
internal fun menuPosition(at: IntOffset, menu: IntSize, window: IntSize): IntOffset {
    val x = if (at.x + menu.width <= window.width) at.x else at.x - menu.width
    val y = when {
        at.y + menu.height <= window.height -> at.y
        at.y - menu.height >= 0 -> at.y - menu.height
        else -> window.height - menu.height
    }
    return IntOffset(x.coerceAtMost(window.width - menu.width).coerceAtLeast(0), y.coerceAtLeast(0))
}

/**
 * The open menu of the pane this is called in, drawn over the app at the point it was asked
 * for. It has the keyboard while it is open: the arrows move through the rows that can be
 * used, Enter runs one, and Escape, a click elsewhere, or the key that opened it closes it.
 *
 * Every row acts on the pane the menu was opened on. Actions act on the pane with the
 * keyboard, and a pane that is right-clicked is given it, which is the server's to say; until
 * it has, no row can be used.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun PaneMenuPopup(menu: PaneMenu, ui: RexUiState, commands: RexCommands) {
    val keymap by ui.client.keymap.collectAsState()
    val state by menu.host.state.collectAsState()
    val onTarget = ui.host === menu.host && state.window?.keyboardBlockId == menu.blockId
    val terminal = state.terminals[menu.blockId]
    // Asked once, as the menu opens: reading a selection is the terminal's own thread's to do.
    val hasSelection by produceState(false, terminal) { value = terminal?.selectedText()?.isNotEmpty() == true }
    val groups = remember(state, keymap, hasSelection, onTarget) {
        paneMenuRows(ui, commands, keymap, hasSelection).map { group -> group.map { if (onTarget) it else it.copy(enabled = false) } }
    }
    val rows = groups.flatten()
    // Opened by a key, it starts on its first row that can be used; under a pointer, on none.
    var selected by remember(menu) { mutableIntStateOf(-1) }
    LaunchedEffect(menu, onTarget) {
        if (menu.at == null && selected < 0) selected = stepMenuRow(rows.map { it.enabled }, -1, down = true) ?: -1
    }

    fun close() {
        if (ui.paneMenu === menu) ui.closePaneMenu()
    }
    fun choose(row: PaneMenuRow) {
        if (!row.enabled) return
        // Closed first: the row may open the palette or a dialog. Only if it opened nothing does the keyboard go back to the terminal.
        ui.paneMenu = null
        commands.run(row.action, origin = "pane menu")
        ui.returnFocusToTerminal()
    }
    // The pane closed, or its tab went out of view, while the menu was open.
    DisposableEffect(menu) { onDispose { close() } }

    val density = LocalDensity.current
    val position = remember(menu, density) {
        val at = menu.at ?: with(density) { Offset(KEYBOARD_MENU_INSET.toPx(), KEYBOARD_MENU_INSET.toPx()) }
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset =
                menuPosition(IntOffset(anchorBounds.left + at.x.roundToInt(), anchorBounds.top + at.y.roundToInt()), popupContentSize, windowSize)
        }
    }
    val focus = remember { FocusRequester() }
    Popup(popupPositionProvider = position, onDismissRequest = ::close, properties = PopupProperties(focusable = true)) {
        LaunchedEffect(Unit) { focus.requestFocus() }
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
            modifier = Modifier
                .width(MENU_WIDTH)
                .semantics { contentDescription = "Pane menu" }
                .focusRequester(focus)
                .focusable()
                // Every key is the menu's while it is open, so that none is typed into the terminal under it.
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent true
                    val usable = rows.map { it.enabled }
                    when (event.key) {
                        Key.Escape -> close()
                        Key.DirectionDown -> stepMenuRow(usable, selected, down = true)?.let { selected = it }
                        Key.DirectionUp -> stepMenuRow(usable, selected, down = false)?.let { selected = it }
                        Key.MoveHome, Key.PageUp -> stepMenuRow(usable, -1, down = true)?.let { selected = it }
                        Key.MoveEnd, Key.PageDown -> stepMenuRow(usable, -1, down = false)?.let { selected = it }
                        Key.Enter, Key.NumPadEnter -> rows.getOrNull(selected)?.let(::choose)
                        // The key that opened the menu closes it again.
                        else -> if (keyChord(event)?.let { keymap.bindingsFor(it).firstOrNull()?.action } == "pane.actions") close()
                    }
                    true
                },
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
                var index = 0
                groups.forEachIndexed { groupIndex, group ->
                    if (groupIndex > 0) HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    for (row in group) {
                        val at = index++
                        PaneMenuRowView(row, selected = at == selected, onHover = { if (row.enabled) selected = at }, onClick = { choose(row) })
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun PaneMenuRowView(row: PaneMenuRow, selected: Boolean, onHover: () -> Unit, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            .clip(MaterialTheme.shapes.extraSmall)
            .background(if (selected && row.enabled) colors.secondaryContainer else Color.Transparent)
            .onPointerEvent(PointerEventType.Enter) { onHover() }
            .onPointerEvent(PointerEventType.Move) { onHover() }
            .clickable(enabled = row.enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = MENU_ROW_HEIGHT)
            .padding(horizontal = 10.dp),
    ) {
        Text(
            row.title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (row.enabled) colors.onSurface else colors.onSurface.copy(alpha = DISABLED_ROW_ALPHA),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        row.shortcut?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant.copy(alpha = if (row.enabled) 1f else DISABLED_ROW_ALPHA),
                maxLines = 1,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}

private val MENU_WIDTH = 264.dp
private val MENU_ROW_HEIGHT = 28.dp

/** How far in from its pane's corner a menu opens when a key asked for it. */
private val KEYBOARD_MENU_INSET = 12.dp

/** How faint a row that can't be used is: Material's own figure for something disabled. */
private const val DISABLED_ROW_ALPHA = 0.38f
