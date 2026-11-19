package org.example.kotlintest

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import org.example.kotlintest.rex.HostState
import org.example.kotlintest.rex.RexClient
import org.example.kotlintest.rex.TabName

/*
 * What the app's window is called.
 *
 * A window list, a workspace overview and a browser's tab strip show a window by its title,
 * so the title says where the window is: the tab on screen, then its session and host. The
 * form is Rex Rune's, `tab - session · host`, and the host is always there, as it is in Rex
 * Rune, whether or not there is a second one. Rex for Mac has the first two parts and no host.
 */

/** What the app is called, and its window while it shows nothing. */
const val APP_NAME = "Tono"

/**
 * The window's title: `tab - session · host`. A part that isn't there is left out: a session
 * with no tab yet is `session · host`, and a host with no session, as while it connects, is
 * the app's name and the host. With no host at all it is the app's name alone.
 */
fun windowTitle(tab: String?, session: String?, host: String?): String {
    val place = listOfNotNull(tab.present(), session.present()).joinToString(" - ")
    val shownHost = host.present() ?: return place.ifEmpty { APP_NAME }
    return "${place.ifEmpty { APP_NAME }} · $shownHost"
}

/**
 * A tab's name as one line: a name someone gave it as it is, and a tab called by its program
 * with the directory after it, as `vim - ~/rex`, which is how Rex Rune writes the pair.
 */
fun TabName.inTitle(): String = listOfNotNull(text.present(), directory.present()).joinToString(" - ")

/** The title for a window showing [host], whose state is [state]. */
fun windowTitle(host: String?, state: HostState?): String {
    val session = state?.selectedSessionId?.let { id -> state.sessions.firstOrNull { it.id == id }?.label.present() ?: state.view?.label }
    // A tab belongs to its session: without the session there is nothing to name.
    val tab = if (session.present() == null) null else state?.window?.let { state.nameOf(it).inTitle() }
    return windowTitle(tab, session, host)
}

/**
 * The title for the app's window as things stand, kept current: it changes with the host
 * being shown, the session and tab on screen, and what that tab is called.
 */
@Composable
fun currentWindowTitle(client: RexClient): String {
    val hosts by client.hosts.collectAsState()
    val selectedLabel by client.selectedHost.collectAsState()
    // The same host the rest of the app shows: the selected one, or the first until one is.
    val host = hosts.firstOrNull { it.saved.label == selectedLabel } ?: hosts.firstOrNull() ?: return APP_NAME
    val state by host.state.collectAsState()
    return windowTitle(host.saved.label, state)
}

private fun String?.present(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
