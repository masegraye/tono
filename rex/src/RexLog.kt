package org.example.kotlintest.rex

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * A short history of what the client has been doing: connections, the events servers send,
 * the requests that change something, and the actions the user runs. For the app's debug
 * panel. It never records what is typed into a terminal.
 */
class RexLog(private val capacity: Int = 600) {
    data class Entry(
        val timeMillis: Long,
        /** Where it came from: a host's label, `app`, or `keys`. */
        val source: String,
        val message: String,
        val isError: Boolean = false,
    )

    private val mutableEntries = MutableStateFlow<List<Entry>>(emptyList())

    /** Oldest first. */
    val entries: StateFlow<List<Entry>> get() = mutableEntries

    fun add(source: String, message: String, isError: Boolean = false) {
        val entry = Entry(nowMillis(), source, message, isError)
        mutableEntries.update { current -> (if (current.size >= capacity) current.drop(current.size - capacity + 1) else current) + entry }
    }

    fun clear() {
        mutableEntries.value = emptyList()
    }
}
