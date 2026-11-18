package org.example.kotlintest.rex

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive

/** A session as the server lists it. */
data class SessionSummary(val id: String, val label: String)

/**
 * What a session looks like: its windows (shown as tabs), and where each block sits.
 * The server owns this; clients draw it.
 */
data class SessionView(
    val sessionId: String,
    val label: String,
    /** Goes up by one with every change, so a newer view always has a higher revision. */
    val revision: Long,
    /**
     * The window the session itself counts as active: the last one any client went to. It is
     * where a command that names no window acts. It is not what this app shows; see
     * [HostState.shownWindow].
     */
    val activeWindowId: String?,
    val windows: List<WindowView>,
) {
    val activeWindow: WindowView? get() = windows.firstOrNull { it.id == activeWindowId } ?: windows.firstOrNull()
}

data class WindowView(
    val id: String,
    val label: String,
    /** The block that receives typing while this window is active. */
    val focusedBlockId: String?,
    /** A block to draw over the whole window instead of the layout, if any. */
    val zoomedBlockId: String?,
    /** Every block to draw, back to front: the tiled layout first, then floating layers. */
    val panes: List<PaneView>,
) {
    /** The pane that has the keyboard in this tab: the zoomed one, else the session's focused one, else the first. */
    val keyboardBlockId: String?
        get() = zoomedBlockId ?: focusedBlockId?.takeIf { id -> panes.any { it.blockId == id } } ?: panes.firstOrNull()?.blockId
}

data class PaneView(
    val blockId: String,
    /** Where the block sits, as fractions of the window. */
    val rect: PaneRect,
    val label: String?,
    /** The client the block takes its size and colors from, if it has one. */
    val ownerClientId: String?,
    val isTerminal: Boolean,
    val floating: Boolean,
)

data class PaneRect(val x: Float, val y: Float, val width: Float, val height: Float) {
    companion object {
        val Full = PaneRect(0f, 0f, 1f, 1f)
    }
}

internal fun parseSessions(list: JsonArray?): List<SessionSummary> =
    list.orEmpty().mapNotNull { element ->
        val session = element as? JsonObject ?: return@mapNotNull null
        val id = session.string("session_id") ?: return@mapNotNull null
        SessionSummary(id, session.string("label") ?: id)
    }

internal fun parseSessionView(view: JsonObject): SessionView = SessionView(
    sessionId = view.string("session_id").orEmpty(),
    label = view.string("label").orEmpty(),
    revision = view.long("revision") ?: 0,
    activeWindowId = view.string("active_window_id"),
    windows = (view["windows"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::parseWindow) },
)

private fun parseWindow(window: JsonObject): WindowView? {
    val id = window.string("window_id") ?: return null
    val panes = ArrayList<PaneView>()
    for (layerElement in (window["layers"] as? JsonArray).orEmpty()) {
        val layer = layerElement as? JsonObject ?: continue
        if (layer.string("state") == "minimized") continue // its blocks keep running, but it isn't drawn
        val blocks = (layer["blocks"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.associateBy { it.string("block_id") }
        // Anything that isn't the tiled layer is drawn floating, including kinds this client predates.
        val floating = layer.string("kind") != "tiled"
        collectPanes(layer["layout"] as? JsonObject, blocks, floating, panes)
    }
    return WindowView(
        id = id,
        label = window.string("label") ?: id,
        focusedBlockId = window.string("focused_block_id"),
        zoomedBlockId = window.string("zoomed_block_id"),
        panes = panes,
    )
}

/** Walks the tree of splits. Every node carries its own rectangle, so only the leaves matter. */
private fun collectPanes(node: JsonObject?, blocks: Map<String?, JsonObject>, floating: Boolean, into: MutableList<PaneView>) {
    if (node == null) return
    val blockId = node.string("block_id")
    if (blockId != null) {
        val block = blocks[blockId]
        into += PaneView(
            blockId = blockId,
            rect = parseRect(node["rect"] as? JsonArray),
            label = block?.string("label"),
            ownerClientId = block?.string("owner"),
            isTerminal = block?.string("creator_name") == TERMINAL_CREATOR,
            floating = floating,
        )
        return
    }
    val split = node["split"] as? JsonObject ?: return
    collectPanes(split["before"] as? JsonObject, blocks, floating, into)
    collectPanes(split["after"] as? JsonObject, blocks, floating, into)
}

private fun parseRect(values: JsonArray?): PaneRect {
    val numbers = values.orEmpty().mapNotNull { if (it is JsonNull) null else runCatching { it.jsonPrimitive.double.toFloat() }.getOrNull() }
    return if (numbers.size == 4) PaneRect(numbers[0], numbers[1], numbers[2], numbers[3]) else PaneRect.Full
}

internal const val TERMINAL_CREATOR = "com.superlogical.terminal"
