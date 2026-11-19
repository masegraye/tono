package org.example.kotlintest.rex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Somewhere a workspace, or one of its two halves, can be kept: a file on the desktop, the browser's own storage on the web. */
interface WorkspaceStorage {
    /** What was last written, or null if nothing was. Throws if something is there and can't be read. */
    fun read(): String?

    fun write(text: String)

    /**
     * Writes [text] only where nothing is kept yet, and says whether it did. Never replaces
     * what is there: it is how a move from an older place marks itself done.
     */
    fun writeNew(text: String): Boolean {
        if (read() != null) return false
        write(text)
        return true
    }

    /** Where that is, in words a person can use to find it: a file's path, say. Null if there is nothing to say. */
    val location: String? get() = null
}

/** A Rex server the app connects to every time it starts. */
data class SavedHost(val label: String, val endpoint: String)

/**
 * Which things the desktop is asked to show a notification for. They are told apart as Rex
 * Rune tells them, and are written to the workspace under its names.
 *
 * @param desktop whether any are shown at all; the rest say which
 * @param blocked a program in a tab out of view started waiting for the user
 * @param error one failed
 * @param done one finished
 * @param programMessages a program asked for one itself, with OSC 9 or OSC 777
 */
data class NotificationSwitches(
    val desktop: Boolean = true,
    val blocked: Boolean = true,
    val error: Boolean = true,
    val done: Boolean = true,
    val programMessages: Boolean = true,
) {
    internal fun toJson(): JsonObject = buildJsonObject {
        put("system_notifications", desktop)
        put("blocked", blocked)
        put("error", error)
        put("done", done)
        put("program_messages", programMessages)
    }

    companion object {
        /** Reads what [toJson] writes. A switch that is missing, or isn't a yes or a no, is on. */
        internal fun parse(json: JsonObject?): NotificationSwitches {
            fun on(key: String) = (json?.get(key) as? JsonPrimitive)?.content != "false"
            return NotificationSwitches(on("system_notifications"), on("blocked"), on("error"), on("done"), on("program_messages"))
        }
    }
}

/**
 * What selecting text with the pointer does besides selecting it. Rex Rune's two switches,
 * written to the workspace under its names, and off unless turned on, as there.
 *
 * @param copyOnSelection a selection the pointer has finished goes to the clipboard
 * @param primarySelection it becomes the desktop's primary selection, and a middle click
 *   pastes that selection. A browser has no primary selection: there the clipboard stands in.
 */
data class SelectionSwitches(val copyOnSelection: Boolean = false, val primarySelection: Boolean = false) {
    internal fun toJson(): JsonObject = buildJsonObject {
        put("copy_on_selection", copyOnSelection)
        put("primary_selection", primarySelection)
    }

    companion object {
        /** Reads what [toJson] writes. A switch that is missing, or isn't a yes, is off. */
        internal fun parse(json: JsonObject?): SelectionSwitches {
            fun on(key: String) = (json?.get(key) as? JsonPrimitive)?.content == "true"
            return SelectionSwitches(on("copy_on_selection"), on("primary_selection"))
        }
    }
}

/**
 * How the app looks, where the person has said: a theme, and the terminal's font. Everything
 * left out is the app's to decide, which is the desktop's own look where the desktop has one
 * to follow (Omarchy), and the app's defaults elsewhere.
 *
 * @param theme the name of one of the app's own themes, such as `tokyo-night`. Null follows
 *   the desktop's theme where there is one, and is the app's default theme where there isn't.
 * @param fontFamily the family terminals are drawn in, as the desktop names it. Null is the
 *   desktop's own monospace font, or the app's where it has none to give.
 * @param fontSize the size of a terminal's text, which the font size keys change. Null is the default.
 * @param fontWeight how heavy text that isn't bold is, as a number from 100 to 900 where 400
 *   is regular. Null is regular.
 */
data class AppearanceChoice(
    val theme: String? = null,
    val fontFamily: String? = null,
    val fontSize: Int? = null,
    val fontWeight: Int? = null,
) {
    val isDefault: Boolean get() = this == AppearanceChoice()

    internal fun toJson(): JsonObject = buildJsonObject {
        theme?.let { put("theme", it) }
        fontFamily?.let { put("font_family", it) }
        fontSize?.let { put("font_size", it) }
        fontWeight?.let { put("font_weight", it) }
    }

    companion object {
        /** Reads what [toJson] writes. Anything missing, or not of its kind, is left to the app. */
        internal fun parse(json: JsonObject?): AppearanceChoice {
            fun text(key: String) = (json?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
            fun number(key: String) = (json?.get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull()
            return AppearanceChoice(text("theme"), text("font_family"), number("font_size")?.takeIf { it in 4..200 }, number("font_weight")?.takeIf { it in 100..900 })
        }
    }
}

/**
 * What the app remembers between runs, in two halves: the settings (what a person chose: the
 * hosts, the switches, the shortcuts, the look) and the state (where they were: the host,
 * session and tab showing, what is folded away, the tabs kept quiet). Stored as JSON, in the
 * same shape Rex Rune uses for these fields.
 *
 * Each half is kept in a storage of its own and written only when it changed, so that
 * switching tabs never rewrites the settings. Given one storage for both, everything is
 * kept there together: that is `--workspace FILE`, and what Tono wrote before there were two.
 *
 * Two promises about what is kept:
 * - what couldn't be read is never written over. The app runs on defaults for that half and
 *   leaves the storage alone until it is started again; [notices] says so.
 * - keys this app doesn't know are written back as they were read.
 */
class Workspace(
    /** Where the settings are kept, or null for ones that are never saved. */
    private val storage: WorkspaceStorage?,
    hosts: List<SavedHost> = emptyList(),
    /** The label of the host that was showing. */
    var lastHost: String? = null,
    /** For each host label, the session that was selected on it. */
    sessions: Map<String, String> = emptyMap(),
    /** The labels of the hosts folded away in the sidebar. */
    var collapsedHosts: Set<String> = emptySet(),
    /** The ids of the sessions folded away in the sidebar. */
    var collapsedSessions: Set<String> = emptySet(),
    /** For each session id, the tab this app was showing in it. */
    tabs: Map<String, String> = emptyMap(),
    /** Whether to play a sound when a program in a tab out of view wants the user, fails, or finishes. */
    var sounds: Boolean = true,
    /** What to show a desktop notification for. */
    var notifications: NotificationSwitches = NotificationSwitches(),
    /** What selecting with the pointer copies, and whether the middle button pastes. */
    var selection: SelectionSwitches = SelectionSwitches(),
    /** The tabs this app keeps quiet. This client's alone: the servers never hear of them. */
    var mutedTabs: List<MutedTab> = emptyList(),
    /** The shortcuts changed in this app, for this app alone: see Shortcuts.kt. */
    var shortcuts: ShortcutOverrides = ShortcutOverrides(),
    /** The theme and terminal font the person chose, where they did. */
    var appearance: AppearanceChoice = AppearanceChoice(),
    /**
     * The order the hosts are listed in, by label, where someone has arranged them. Rex's
     * host list has no order of its own (it is sorted by label), so this is this app's alone.
     * Empty until they are arranged; a host not named here comes after those that are.
     */
    var hostOrder: List<String> = emptyList(),
    /** What this app calls itself in a server's list of clients, where someone chose that. Null leaves it as the app has it. */
    var clientLabel: String? = null,
    /** Where the state is kept. The same place as the settings unless told otherwise. */
    private val stateStorage: WorkspaceStorage? = storage,
) {
    /** Something to tell the person about what is kept: a move from an older place, or a file that couldn't be read. */
    data class Notice(val message: String, val isError: Boolean = false)

    val tabs: MutableMap<String, String> = tabs.toMutableMap()
    var hosts: List<SavedHost> = hosts
    val sessions: MutableMap<String, String> = sessions.toMutableMap()

    /** Whether settings and state are kept together in one place. */
    private val together: Boolean get() = stateStorage === storage

    /** Where the settings are kept, for showing: the file's path on the desktop. Null for ones that are never saved. */
    val location: String? get() = storage?.location

    /** Where the state is kept. The same as [location] when both are kept together. */
    val stateLocation: String? get() = stateStorage?.location

    /** Why the settings aren't being saved, where they aren't: what was there couldn't be read. Null when they are. */
    var settingsProblem: String? = null
        private set

    /** The same for the state. */
    var stateProblem: String? = null
        private set

    /** What happened while this was read, oldest first, for the debug log. */
    var notices: List<Notice> = emptyList()
        private set

    // What was read and isn't this app's to understand, written back as it was.
    private var unknownSettings: JsonObject = EMPTY
    private var unknownState: JsonObject = EMPTY
    private var readNotifications: JsonObject? = null
    private var readTerminal: JsonObject? = null
    private var readAppearance: JsonObject? = null
    private var readHosts: Map<SavedHost, JsonObject> = emptyMap()

    // What each storage holds, as far as this knows: the text last written, or what the text read amounts to.
    private var keptSettings: String? = null
    private var keptState: String? = null

    private fun settingsEntries(): Map<String, JsonElement> = buildMap {
        put("hosts", buildJsonArray {
            for (host in hosts) add(buildJsonObject {
                put("label", host.label)
                put("endpoint", host.endpoint)
                readHosts[host]?.forEach { (key, value) -> if (key != "label" && key != "endpoint") put(key, value) }
            })
        })
        if (!sounds) put("sounds", JsonPrimitive(false))
        // Always written, every switch, so that the file shows what there is to set.
        put("notifications", notifications.toJson().withUnknownOf(readNotifications))
        // The same goes for these, which Rex Rune keeps under its terminal settings.
        put("terminal", selection.toJson().withUnknownOf(readTerminal))
        if (!shortcuts.isEmpty) put("shortcuts", shortcuts.toJson())
        // Only what was chosen: a file without it follows the desktop, as before there was a choice.
        val look = appearance.toJson().withUnknownOf(readAppearance, APPEARANCE_KEYS)
        if (look.isNotEmpty()) put("appearance", look)
        clientLabel?.let { put("client_label", JsonPrimitive(it)) }
        if (hostOrder.isNotEmpty()) put("host_order", buildJsonArray { hostOrder.forEach { add(JsonPrimitive(it)) } })
    }

    private fun stateEntries(): Map<String, JsonElement> = buildMap {
        lastHost?.let { put("last_host", JsonPrimitive(it)) }
        put("sessions", buildJsonObject { for ((host, session) in sessions) put(host, session) })
        if (tabs.isNotEmpty()) put("tabs", buildJsonObject { for ((session, tab) in tabs.entries.sortedBy { it.key }) put(session, tab) })
        if (collapsedHosts.isNotEmpty()) put("collapsed_hosts", buildJsonArray { collapsedHosts.sorted().forEach { add(JsonPrimitive(it)) } })
        if (collapsedSessions.isNotEmpty()) put("collapsed_sessions", buildJsonArray { collapsedSessions.sorted().forEach { add(JsonPrimitive(it)) } })
        if (mutedTabs.isNotEmpty()) put("muted_tabs", buildJsonArray {
            for (tab in mutedTabs) add(buildJsonObject {
                put("endpoint", tab.endpoint)
                put("session", tab.session)
                put("window", tab.window)
            })
        })
    }

    /** The text of one file: this app's keys in the order they have always been written, then the ones it doesn't know. */
    private fun text(known: Map<String, JsonElement>, unknown: JsonObject): String {
        val json = buildJsonObject {
            for (key in KEY_ORDER) known[key]?.let { put(key, it) }
            for ((key, value) in unknown) if (key !in known) put(key, value)
        }
        return PRETTY.encodeToString(JsonObject.serializer(), json) + "\n"
    }

    private fun settingsText(): String =
        if (together) text(settingsEntries() + stateEntries(), unknownSettings) else text(settingsEntries(), unknownSettings)

    private fun stateText(): String = text(stateEntries(), unknownState)

    /**
     * Writes out what changed since it was last written or read: the settings, the state, or
     * neither. Several threads may ask at once; one writes at a time. A storage whose
     * contents couldn't be read is left as it is.
     */
    fun save() {
        locked(this) {
            val settings = storage
            if (settings != null && settingsProblem == null) {
                val text = settingsText()
                if (text != keptSettings && runCatching { settings.write(text) }.isSuccess) keptSettings = text
            }
            val state = stateStorage
            if (state != null && !together && stateProblem == null) {
                val text = stateText()
                if (text != keptState && runCatching { state.write(text) }.isSuccess) keptState = text
            }
        }
    }

    companion object {
        private val PRETTY = Json { prettyPrint = true }
        private val EMPTY = JsonObject(emptyMap())

        /** The keys of the settings: what a person chose. */
        val SETTINGS_KEYS: Set<String> = setOf("hosts", "sounds", "notifications", "terminal", "shortcuts", "appearance", "client_label", "host_order")

        /** The keys of the state: where they were. */
        val STATE_KEYS: Set<String> = setOf("last_host", "sessions", "tabs", "collapsed_hosts", "collapsed_sessions", "muted_tabs")

        /** The order the keys are written in when all are in one file, which each half's order follows. */
        private val KEY_ORDER = listOf(
            "hosts", "last_host", "sessions", "sounds", "notifications", "terminal", "tabs", "collapsed_hosts", "collapsed_sessions",
            "muted_tabs", "shortcuts", "appearance", "client_label", "host_order",
        )
        private val APPEARANCE_KEYS = setOf("theme", "font_family", "font_size", "font_weight")

        /** [this], and whatever [read] holds under keys this doesn't write. [mine] names keys that are this app's even when left out. */
        private fun JsonObject.withUnknownOf(read: JsonObject?, mine: Set<String> = keys): JsonObject =
            if (read == null || read.keys.all { it in mine }) this else JsonObject(this + read.filterKeys { it !in mine })

        /** What one storage held: an object, nothing, or something that couldn't be read. */
        private class Read(val root: JsonObject?, val problem: String?)

        private fun read(storage: WorkspaceStorage): Read {
            val what = storage.location ?: "What was kept"
            val text = try {
                storage.read()
            } catch (failure: Exception) {
                return Read(null, "$what couldn't be read (${failure.message ?: failure::class.simpleName}). It is left as it is, and nothing is saved over it.")
            }
            if (text.isNullOrBlank()) return Read(null, null)
            val root = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
                ?: return Read(null, "$what isn't JSON that can be read. It is left as it is, and nothing is saved over it: correct it and start again, or move it away.")
            return Read(root, null)
        }

        /**
         * Reads the workspace: settings from [storage], and state from [stateStorage], which
         * is the same place unless another is given. Where there is nothing, it starts empty
         * and saves there. Where there is something that can't be read, it starts empty for
         * that half and never saves over it.
         */
        fun load(storage: WorkspaceStorage, stateStorage: WorkspaceStorage = storage): Workspace {
            val together = stateStorage === storage
            val settingsRead = read(storage)
            val stateRead = if (together) settingsRead else read(stateStorage)
            val settings = settingsRead.root ?: EMPTY
            val state = stateRead.root ?: EMPTY

            val readHosts = mutableMapOf<SavedHost, JsonObject>()
            val hosts = (settings["hosts"] as? JsonArray).orEmpty().mapNotNull { element ->
                val host = element as? JsonObject ?: return@mapNotNull null
                SavedHost(host.string("label") ?: return@mapNotNull null, host.string("endpoint") ?: return@mapNotNull null)
                    .also { if (host.size > 2) readHosts[it] = host }
            }
            fun map(key: String) = state.obj(key)?.mapNotNull { (name, value) ->
                (value as? JsonPrimitive)?.takeIf { it.isString }?.let { name to it.content }
            }?.toMap().orEmpty()
            val sessions = map("sessions")
            fun strings(key: String) = (state[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content }.toSet()
            val workspace = Workspace(storage, hosts, state.string("last_host"), sessions, strings("collapsed_hosts"), strings("collapsed_sessions"), map("tabs"),
                sounds = (settings["sounds"] as? JsonPrimitive)?.content != "false",
                notifications = NotificationSwitches.parse(settings.obj("notifications")),
                selection = SelectionSwitches.parse(settings.obj("terminal")),
                mutedTabs = (state["muted_tabs"] as? JsonArray).orEmpty().mapNotNull { element ->
                    val tab = element as? JsonObject ?: return@mapNotNull null
                    MutedTab(tab.string("endpoint") ?: return@mapNotNull null, tab.string("session") ?: return@mapNotNull null, tab.string("window") ?: return@mapNotNull null)
                }.takeLast(MUTED_TABS_LIMIT),
                shortcuts = ShortcutOverrides.parse(settings.obj("shortcuts")),
                appearance = AppearanceChoice.parse(settings.obj("appearance")),
                clientLabel = settings.string("client_label")?.trim()?.takeIf { it.isNotEmpty() },
                hostOrder = (settings["host_order"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content }.distinct(),
                stateStorage = stateStorage,
            )
            // In a file of its own, a key of the other half's is as unknown as any other.
            workspace.unknownSettings = JsonObject(settings.filterKeys { it !in SETTINGS_KEYS && !(together && it in STATE_KEYS) })
            workspace.unknownState = if (together) EMPTY else JsonObject(state.filterKeys { it !in STATE_KEYS })
            workspace.readNotifications = settings.obj("notifications")
            workspace.readTerminal = settings.obj("terminal")
            workspace.readAppearance = settings.obj("appearance")
            workspace.readHosts = readHosts
            workspace.settingsProblem = settingsRead.problem
            workspace.stateProblem = if (together) null else stateRead.problem
            workspace.notices = listOfNotNull(settingsRead.problem, stateRead.problem.takeIf { !together }).map { Notice(it, isError = true) }
            // What was read counts as kept, however it was written: only a change made here rewrites it.
            if (settingsRead.root != null) workspace.keptSettings = workspace.settingsText()
            if (!together && stateRead.root != null) workspace.keptState = workspace.stateText()
            return workspace
        }

        /** One that runs on defaults and saves nothing, with the places it would have used still shown. */
        private fun notSaved(storage: WorkspaceStorage, stateStorage: WorkspaceStorage, why: String): Workspace = Workspace(storage, stateStorage = stateStorage).apply {
            settingsProblem = why
            stateProblem = why
            notices = listOf(Notice(why, isError = true))
        }

        /**
         * Reads the workspace from its two places, moving it there first from [old], the one
         * place everything was kept in before, if this is the first start since:
         *
         * 1. Settings already in [storage]: they are used, and [old] isn't looked at.
         * 2. Otherwise, something readable in [old]: its state keys are copied to
         *    [stateStorage] and the rest, keys this app doesn't know included, to [storage].
         *    The settings are written last, and only where there are none, because their
         *    being there is what marks the move as done: stopped halfway, the next start does
         *    it again. Both are read back and compared. [old] is never written to.
         * 3. Otherwise it starts fresh in the two places.
         *
         * If [old] is there and can't be read, or the copies don't read back as they were
         * written, this runs on defaults and saves nothing, so that the next start can try again.
         *
         * @param moved called once the move is done and checked, for the platform to leave a note where the old one is
         */
        fun loadMoving(storage: WorkspaceStorage, stateStorage: WorkspaceStorage, old: WorkspaceStorage?, moved: () -> Unit = {}): Workspace {
            // Something unreadable there is still something there: the reader says so and leaves it be.
            val settled = runCatching { storage.read() != null }.getOrDefault(true)
            if (settled || old == null) return load(storage, stateStorage)
            val from = old.location ?: "the place settings were kept before"
            val text = try {
                old.read()
            } catch (failure: Exception) {
                return notSaved(storage, stateStorage, "$from couldn't be read (${failure.message ?: failure::class.simpleName}), so nothing was moved from it. It is left as it is, and nothing is saved until it can be read or is moved away.")
            }
            if (text.isNullOrBlank()) return load(storage, stateStorage)
            val root = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
                ?: return notSaved(storage, stateStorage, "$from isn't JSON that can be read, so nothing was moved from it. It is left as it is, and nothing is saved until it is corrected or moved away.")

            val state = JsonObject(root.filterKeys { it in STATE_KEYS })
            val settings = JsonObject(root.filterKeys { it !in STATE_KEYS })
            fun textOf(json: JsonObject) = PRETTY.encodeToString(JsonObject.serializer(), json) + "\n"
            fun holds(kept: WorkspaceStorage, json: JsonObject) = kept.read()?.let { Json.parseToJsonElement(it) } == json
            try {
                // State that is already there is from a move stopped halfway, or newer: either way it stays.
                val stateWritten = runCatching { stateStorage.read() == null }.getOrDefault(false)
                if (stateWritten) stateStorage.write(textOf(state))
                // False when another Tono started at the same moment and got there first: its settings stand.
                val settingsWritten = storage.writeNew(textOf(settings))
                if (stateWritten && !holds(stateStorage, state)) return notSaved(storage, stateStorage, "Moving from $from: ${stateStorage.location ?: "the state"} doesn't read back as it was written. Nothing is saved; $from is as it was.")
                if (settingsWritten && !holds(storage, settings)) return notSaved(storage, stateStorage, "Moving from $from: ${storage.location ?: "the settings"} don't read back as they were written. Nothing is saved; $from is as it was.")
                if (!settingsWritten) return load(storage, stateStorage)
            } catch (failure: Exception) {
                return notSaved(storage, stateStorage, "Moving from $from failed (${failure.message ?: failure::class.simpleName}). Nothing is saved; $from is as it was.")
            }
            runCatching(moved)
            return load(storage, stateStorage).apply {
                notices = listOf(Notice("Settings moved from $from to ${storage.location ?: "their new place"}, and where you were to ${stateStorage.location ?: "its new place"}. $from is left as it was, as a backup.")) + notices
            }
        }
    }
}
