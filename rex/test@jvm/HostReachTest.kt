package org.example.kotlintest.rex

import kotlin.test.Test
import kotlin.test.assertEquals

/** What a host's state says about how far along reaching it is, through a first attempt, a failure and a lost connection. */
class HostReachTest {
    @Test
    fun `a host is being connected to until its first attempt ends`() {
        assertEquals(HostReach.Connecting, HostState().reach)
    }

    @Test
    fun `one that has never answered is down, between tries and during them`() {
        val failed = HostState().copy(connection = HostConnection.Disconnected("no answer"), failed = true)
        assertEquals(HostReach.Down, failed.reach)
        assertEquals(HostReach.Down, failed.copy(connection = HostConnection.Connecting).reach, "trying again isn't a first attempt")
    }

    @Test
    fun `one that was connected and is tried again is reconnecting, until it answers`() {
        val connected = HostState(connection = HostConnection.Connected, reached = true)
        assertEquals(HostReach.Connected, connected.reach)
        val lost = connected.copy(connection = HostConnection.Disconnected("it went away"), failed = true)
        assertEquals(HostReach.Reconnecting, lost.reach)
        assertEquals(HostReach.Reconnecting, lost.copy(connection = HostConnection.Connecting).reach)
        assertEquals(HostReach.Connected, lost.copy(connection = HostConnection.Connected, failed = false).reach)
    }

    @Test
    fun `an address that can't be read is down, and a state made by hand without the flag is too`() {
        assertEquals(HostReach.Down, HostState(connection = HostConnection.Disconnected("Invalid endpoint", permanent = true), failed = true).reach)
        assertEquals(HostReach.Down, HostState(connection = HostConnection.Disconnected("no answer")).reach)
    }
}
