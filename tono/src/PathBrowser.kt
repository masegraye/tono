package org.example.kotlintest

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.example.kotlintest.rex.DirectoryListing
import org.example.kotlintest.rex.LISTING_TIMED_OUT
import org.example.kotlintest.rex.LISTING_TIMEOUT_MILLIS
import org.example.kotlintest.rex.PathRow
import org.example.kotlintest.rex.REFUSED_CHARACTERS
import org.example.kotlintest.rex.RexHost
import org.example.kotlintest.rex.TRUNCATED_LISTING
import org.example.kotlintest.rex.fieldTextFor
import org.example.kotlintest.rex.goToDirectoryText
import org.example.kotlintest.rex.insertPathText
import org.example.kotlintest.rex.listDirectory
import org.example.kotlintest.rex.listingFailure
import org.example.kotlintest.rex.listingRefusal
import org.example.kotlintest.rex.pathFieldRefusal
import org.example.kotlintest.rex.pathRows
import org.example.kotlintest.rex.splitPath

/**
 * The terminal a path is being found for: the one that had the keyboard when the browser
 * opened. It is that terminal's machine that is listed, and that terminal that is typed into.
 */
internal class PathTarget(val host: RexHost, val sessionId: String, val blockId: String) {
    /** Whether it is still there to type into: its session is the one showing, and it hasn't closed. */
    val isThere: Boolean
        get() = host.state.value.let { it.selectedSessionId == sessionId && blockId in it.terminals }

    companion object {
        fun of(ui: RexUiState): PathTarget? {
            val host = ui.host ?: return null
            val blockId = ui.focusedBlockId ?: return null
            if (ui.focusedTerminal == null) return null
            return PathTarget(host, host.state.value.selectedSessionId ?: return null, blockId)
        }
    }
}

/** What the browser says about a row, beside its name. */
internal fun pathRowDetail(row: PathRow, foldersOnly: Boolean): String = when (row.kind) {
    PathRow.Kind.Typed -> if (foldersOnly) "Go to typed folder" else "Insert typed path"
    PathRow.Kind.Folder -> "Folder"
    PathRow.Kind.File -> "File"
}

/**
 * Insert Path and Go to Directory: the files and folders of the focused terminal's machine,
 * in the palette's frame. The field holds a path. What comes after its last slash narrows
 * the folder before it. It starts in the terminal's working directory, which the server
 * reads from the program in it.
 *
 * Up and Down choose, Tab goes into a folder, Enter or a click types the choice into the
 * terminal: the quoted path, or `cd` and the quoted folder. Enter is never typed there.
 * The rules, and what is refused, are Rex Rune's: see `rex/src/PathBrowser.kt`.
 */
@Composable
internal fun PathBrowser(ui: RexUiState, mode: PaletteMode) {
    val foldersOnly = mode == PaletteMode.GoToDirectory
    val keymap by ui.client.keymap.collectAsState()
    val target = remember(mode) { PathTarget.of(ui) }
    PathBrowserPanel(
        foldersOnly = foldersOnly,
        list = target?.let { { text -> it.host.listDirectory(it.sessionId, it.blockId, text, files = !foldersOnly) } },
        type = { text ->
            if (target == null || !target.isThere) return@PathBrowserPanel false
            ui.palette = null
            // Never the path itself: the log is something people paste elsewhere.
            ui.client.log.add("app", "typed ${if (foldersOnly) "cd and a folder" else "a path"} into the terminal (${text.length} characters, no Enter)")
            target.host.typeText(target.blockId, text)
            ui.returnFocusToTerminal()
            true
        },
        onClose = ui::closePalette,
        // The key that opens the palette closes this too.
        closesIt = { event -> keyChord(event)?.let { keymap.bindingsFor(it).firstOrNull()?.action } == "client.palette.toggle" },
    )
}

/**
 * The browser, apart from the app it is in.
 *
 * @param list reads what is typed in the field from the terminal's machine, or null when there is no terminal
 * @param type types the chosen text into the terminal and closes the browser. False if the terminal is gone.
 * @param closesIt whether a key, other than Escape, closes the browser
 */
@Composable
internal fun PathBrowserPanel(
    foldersOnly: Boolean,
    list: (suspend (text: String) -> DirectoryListing)?,
    type: (text: String) -> Boolean,
    onClose: () -> Unit,
    closesIt: (KeyEvent) -> Boolean = { false },
) {
    var field by remember { mutableStateOf(TextFieldValue("")) }
    var rows by remember { mutableStateOf(emptyList<PathRow>()) }
    var selected by remember { mutableIntStateOf(0) }
    /** Why there is nothing to choose, or why the choice wasn't typed. */
    var message by remember { mutableStateOf<String?>(null) }
    var truncated by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var home by remember { mutableStateOf("") }
    /** The text the rows are the listing of. An edit to anything else lists again. */
    var listedFor by remember { mutableStateOf<String?>(null) }
    /** Whether the field has been filled in with where the terminal is, which happens once. */
    var started by remember { mutableStateOf(false) }

    fun put(text: String) {
        field = TextFieldValue(text, TextRange(text.length))
    }

    // One listing at a time, for the newest text: an edit abandons the one before it, and
    // its answer, if it comes, is dropped. Rows go while reading, so an old path can't be chosen.
    LaunchedEffect(field.text) {
        val text = field.text
        if (text == listedFor) return@LaunchedEffect
        listedFor = null
        rows = emptyList()
        selected = 0
        truncated = false
        message = null
        loading = false
        if (list == null) {
            message = "There is no terminal to browse from."
            return@LaunchedEffect
        }
        pathFieldRefusal(text)?.let {
            message = it
            return@LaunchedEffect
        }
        loading = true
        // Typing a name is several edits close together; ask once it pauses.
        if (started) delay(EDIT_PAUSE_MILLIS)
        val listing = try {
            withTimeoutOrNull(LISTING_TIMEOUT_MILLIS) { list(text) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            loading = false
            message = listingFailure(failure)
            return@LaunchedEffect
        }
        loading = false
        if (listing == null) {
            message = LISTING_TIMED_OUT
            return@LaunchedEffect
        }
        listingRefusal(listing)?.let {
            message = it
            return@LaunchedEffect
        }
        home = listing.home
        rows = pathRows(listing, splitPath(text).prefix, foldersOnly)
        truncated = listing.truncated
        if (!started && text.isEmpty()) {
            // Show where the terminal is, ready to be added to or changed.
            val here = fieldTextFor(listing.path, listing.home)
            listedFor = here
            put(here)
        } else {
            listedFor = text
        }
        started = true
    }

    val listState = rememberLazyListState()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(selected, rows.size) { if (selected in rows.indices) listState.scrollToItem(selected) }

    fun choose(row: PathRow) {
        val text = if (foldersOnly) goToDirectoryText(row.path) else insertPathText(row.path)
        if (text == null) {
            message = REFUSED_CHARACTERS
        } else if (!type(text)) {
            rows = emptyList()
            message = "Nothing was typed: that terminal is no longer there."
        }
    }

    fun descend(row: PathRow) {
        if (row.isFolder) put(fieldTextFor(row.path, home))
    }

    PaletteFrame(onDismiss = onClose) {
        TextField(
            value = field,
            onValueChange = { field = it },
            singleLine = true,
            placeholder = { Text(if (foldersOnly) "Go to a folder on the terminal's machine" else "Insert the path of a file or folder on the terminal's machine") },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
            ),
            modifier = Modifier.fillMaxWidth().focusRequester(focus).onPreviewKeyEvent { event ->
                // Tab belongs to the browser on its way down and up, or it would move the keyboard off the field.
                if (event.key == Key.Tab) {
                    if (event.type == KeyEventType.KeyDown && !event.isShiftPressed) rows.getOrNull(selected)?.let(::descend)
                    return@onPreviewKeyEvent true
                }
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Escape -> onClose()
                    Key.DirectionDown -> if (rows.isNotEmpty()) selected = (selected + 1) % rows.size
                    Key.DirectionUp -> if (rows.isNotEmpty()) selected = (selected - 1).mod(rows.size)
                    Key.Enter, Key.NumPadEnter -> rows.getOrNull(selected)?.let(::choose)
                    else -> if (closesIt(event)) onClose() else return@onPreviewKeyEvent false
                }
                true
            },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        LazyColumn(state = listState, modifier = Modifier.heightIn(max = 420.dp).padding(vertical = 4.dp)) {
            itemsIndexed(rows) { index, row ->
                PaletteRow(
                    row.label, pathRowDetail(row, foldersOnly),
                    // On the row Tab would go into, say so, where a command shows its key.
                    shortcut = if (index == selected && row.isFolder && row.kind != PathRow.Kind.Typed) "Tab to open" else null,
                    selected = index == selected,
                    onClick = { choose(row) },
                )
            }
        }
        (message ?: when {
            loading -> "Reading the folder…"
            truncated -> TRUNCATED_LISTING
            else -> null
        })?.let { note ->
            Text(
                note,
                style = MaterialTheme.typography.bodyMedium,
                color = if (message != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}

/** How long the field must rest before its text is asked about. */
private const val EDIT_PAUSE_MILLIS = 60L
