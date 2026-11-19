package org.example.kotlintest

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.example.kotlintest.rex.ActivityState
import org.example.kotlintest.rex.HostState
import org.example.kotlintest.rex.NotificationSwitches
import org.example.kotlintest.rex.RexHost
import org.example.kotlintest.rex.TabNews
import org.example.kotlintest.rex.TabPlace
import org.example.kotlintest.rex.shownPlace

/*
 * Desktop notifications: what a sound can't say, which is what happened and where. One is
 * shown when a program in a tab you aren't looking at starts waiting for you, fails or
 * finishes, and when a program asks for one itself (OSC 9, OSC 777).
 *
 * The rules are Rex Rune's (`src/app/activity.rs`, `src/app/notify.rs`): nothing for the
 * tab in front of you; a tab's newest notification takes the place of its last rather than
 * joining it; what is waiting for you or failed stays up until dismissed; going to a tab
 * takes its notifications down; clicking one goes to its tab; and each kind has a switch.
 * One thing differs. In Rex Rune what programs ask for pile up, each on its own. Here a tab
 * has one place for what its program is doing and one for what its program last said, so a
 * program that says something every minute leaves one notification and not sixty.
 */

/**
 * Something to put in front of the user.
 *
 * @param tag what it is about. One with the same tag as another still showing takes its place.
 * @param urgent whether it should stay up until dismissed, where the desktop can do that
 */
internal data class DesktopNote(val tag: String, val title: String, val body: String, val urgent: Boolean = false)

/**
 * Whether a tab's news is announced at all, by a sound or by a notification. This is the
 * one place that decides it for both, after the client has left out the tab in front of the
 * user (see `RexHost.isBeingWatched`), and before each asks its own switches. A muted tab
 * is turned away here. News that names no tab can't be from a muted one, and goes on.
 *
 * @param isMuted whether the tab at a place has been told to keep quiet on this client
 */
internal fun announces(news: TabNews, isMuted: (TabPlace) -> Boolean): Boolean = news.place?.let(isMuted) != true

/** Whether the switches let a notification through for [news]. */
internal fun NotificationSwitches.want(news: TabNews): Boolean = desktop && when (news) {
    is TabNews.Activity -> when (news.state) {
        ActivityState.Blocked -> blocked
        ActivityState.Error -> error
        ActivityState.Done -> done
        else -> false
    }
    is TabNews.Message -> programMessages
}

/**
 * The notification for [news], or null if there is to be none: its switch is off, or its
 * tab isn't known, and a notification that can't say where it is from, be replaced by the
 * next, or be taken down when its tab is seen is worse than none.
 *
 * What a program is doing is headed by the tab and the word for it, `claude - ~/rex · Needs
 * you`, over where the tab is, `work · studio`. What a program says itself is headed by its
 * own title over its text and the whole of where it came from; with no title, by the tab.
 *
 * @param state the state of the host the news is from, for what its tab and session are called
 */
internal fun noteFor(news: TabNews, switches: NotificationSwitches, state: HostState?): DesktopNote? {
    if (!switches.want(news)) return null
    val place = news.place ?: return null
    val view = state?.views?.get(place.sessionId)
    val tab = view?.windows?.firstOrNull { it.id == place.windowId }?.let { state.nameOf(it).inTitle() }?.clean(TITLE_LENGTH).orEmpty().ifEmpty { "Terminal" }
    val session = (state?.sessions?.firstOrNull { it.id == place.sessionId }?.label ?: view?.label)?.clean(TITLE_LENGTH).orEmpty()
    val where = listOf(session, place.host.clean(TITLE_LENGTH)).filter { it.isNotEmpty() }.joinToString(" · ")
    return when (news) {
        is TabNews.Activity -> {
            val word = activityWord(news.state) ?: return null
            DesktopNote(tagOf(ACTIVITY, place), "$tab · $word", where, urgent = news.state == ActivityState.Blocked || news.state == ActivityState.Error)
        }
        is TabNews.Message -> {
            val title = news.title.clean(TITLE_LENGTH)
            val body = news.body.clean(BODY_LENGTH, keepLines = true)
            if (title.isEmpty() && body.isEmpty()) return null
            val from = if (title.isEmpty()) where else "$tab - $where"
            DesktopNote(tagOf(MESSAGE, place), title.ifEmpty { tab }, listOf(body, from).filter { it.isNotEmpty() }.joinToString("\n"))
        }
    }
}

/** What to call a state that is news, in a notification's heading. Null for one that isn't. */
internal fun activityWord(state: ActivityState): String? = when (state) {
    ActivityState.Blocked -> "Needs you"
    ActivityState.Error -> "Failed"
    ActivityState.Done -> "Done"
    else -> null
}

/** The tags of the notifications a tab can have up: one for what its program is doing, one for what it said. */
internal fun tagsOf(place: TabPlace): List<String> = listOf(tagOf(ACTIVITY, place), tagOf(MESSAGE, place))

// The parts are ids and a label, any of which may hold any character, so each is given with its length.
private fun tagOf(kind: String, place: TabPlace): String =
    listOf(kind, place.host, place.sessionId, place.windowId).joinToString("") { "${it.length}:$it" }

/**
 * Text from a server or a program made fit to hand to the desktop: no control characters,
 * which a terminal's escape sequence could otherwise smuggle in, runs of blanks as one
 * space, and no longer than [limit].
 */
private fun String.clean(limit: Int, keepLines: Boolean = false): String {
    val lines = (if (keepLines) lineSequence() else sequenceOf(this))
        .map { line -> line.map { if (it.isISOControl() || it.isWhitespace()) ' ' else it }.joinToString("").replace(BLANKS, " ").trim() }
        .filter { it.isNotEmpty() }
    val text = lines.joinToString("\n")
    return if (text.length <= limit) text else text.take(limit - 1).trimEnd() + "…"
}

private val BLANKS = Regex(" {2,}")
private const val ACTIVITY = "activity"
private const val MESSAGE = "message"
private const val TITLE_LENGTH = 120
private const val BODY_LENGTH = 600

/** Whether the desktop will show this app's notifications. */
internal enum class NotificationAccess {
    Granted,
    /** A browser that hasn't been asked yet. It has to be asked while a click or a key press is being handled. */
    Undecided,
    /** A browser whose user said no. Only they can change that, in the browser's own settings. */
    Refused,
    /** There is nothing to show them with. */
    Unavailable,
}

/**
 * Shows the notifications, and takes them down again.
 *
 * Used from one thread: the one the app's interface runs on.
 */
internal class Notifier(private val ui: RexUiState) {
    /** The tags of the notifications shown and, as far as this app knows, not yet taken down. */
    private val up = HashSet<String>()
    private var saidWhyNot: NotificationAccess? = null

    private fun log(message: String, isError: Boolean = false) = ui.client.log.add("notify", message, isError = isError)

    /** Shows the notification for [news], if there is to be one. Returns at once. */
    fun announce(news: TabNews) {
        val place = news.place
        val state = ui.client.hosts.value.firstOrNull { it.saved.label == place?.host }?.state?.value
        val note = noteFor(news, ui.notifications, state) ?: return
        when (val access = notificationAccess()) {
            NotificationAccess.Granted -> show(note) { if (place != null) open(place) }
            // A browser shows its question only to someone who is there to answer it, so it
            // waits until they are back, and are asked whether they want to be asked.
            NotificationAccess.Undecided -> {
                if (!ui.notificationQuestion) log("a tab had news, and the browser hasn't been asked to allow notifications yet")
                ui.notificationQuestion = true
            }
            else -> if (saidWhyNot != access) {
                saidWhyNot = access
                log(if (access == NotificationAccess.Refused) "a tab had news, but the browser has been told not to allow notifications" else "a tab had news, but there is nothing here to show a notification with", isError = true)
            }
        }
    }

    /** Shows a notification that isn't about any tab. */
    fun show(note: DesktopNote, onClick: () -> Unit = {}) {
        val scope = ui.scope ?: return
        up += note.tag
        scope.launch {
            showNotification(note, { message, isError -> ui.client.log.add("notify", message, isError = isError) }) {
                // Wherever the desktop said it, the app is changed on its own thread.
                scope.launch { onClick() }
            }
        }
    }

    /**
     * Takes down what is showing about a tab, now that the user is looking at it. Asked for
     * whenever a tab comes in front, so the desktop is only troubled about a tab it was
     * given something for.
     */
    fun seen(place: TabPlace) {
        for (tag in tagsOf(place)) if (up.remove(tag)) closeNotification(tag)
    }

    /** A notification was clicked: bring the app's window forward and go to the tab it was about. */
    private fun open(place: TabPlace) {
        log("a notification was clicked: going to its tab")
        bringWindowToFront()
        val state = ui.client.hosts.value.firstOrNull { it.saved.label == place.host }?.state?.value ?: return
        val view = state.views[place.sessionId] ?: return
        // A tab that has closed since leaves its session to go to.
        ui.go(NavigationStop(place.host, place.sessionId, place.windowId.takeIf { id -> view.windows.any { it.id == id } }))
        if (ui.narrow) ui.sidebarVisible = false // as going to a tab from the sidebar does
        seen(place)
    }

    /**
     * Asks the browser to allow notifications. Call it while a click or a key press is being
     * handled: some browsers ignore the question otherwise. On the desktop there is nothing to ask.
     */
    fun askAccess() {
        ui.notificationQuestion = false
        ui.scope?.launch {
            val access = requestNotificationAccess()
            log("the browser was asked to allow notifications: ${access.name.lowercase()}", isError = access != NotificationAccess.Granted)
            if (access == NotificationAccess.Granted) showExample()
        }
    }

    /** Shows one, so you know what to look for and that it works here. As turning sounds on plays one. */
    fun showExample() = show(DesktopNote("tono", APP_NAME, "Notifications are on. You'll see one here when a tab you aren't looking at needs you, fails or finishes."))
}

/**
 * Takes down a tab's notifications when it comes in front of the user: it is the tab on
 * screen, on the host showing, in a window that has focus.
 */
@Composable
internal fun CloseSeenNotifications(ui: RexUiState, showing: RexHost?, inFront: Boolean) {
    if (showing == null || !inFront) return
    val state by showing.state.collectAsState()
    val place = state.shownPlace(showing.saved.label)
    LaunchedEffect(place) { place?.let(ui.notifier::seen) }
}

/**
 * In a browser that hasn't been asked yet: a line saying a tab had news, and a button that
 * has the browser ask. It appears once there has been something to show, never before, and
 * goes for good when answered either way. Pressing the button is what lets the browser ask.
 */
@Composable
internal fun NotificationQuestion(ui: RexUiState) {
    if (!ui.notificationQuestion) return
    Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "A tab you weren't looking at had news. $APP_NAME can show a notification when that happens.",
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { ui.notifier.askAccess(); ui.returnFocusToTerminal() }) { Text("Allow Notifications") }
        TextButton(
            onClick = { ui.notifications = ui.notifications.copy(desktop = false); ui.notificationQuestion = false; ui.returnFocusToTerminal() },
        ) { Text("No Thanks", color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
