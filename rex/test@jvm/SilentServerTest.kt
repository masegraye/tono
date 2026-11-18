package org.example.kotlintest.rex

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** A server that has gone away without closing its connection is noticed, and one that is only quiet is not. */
@OptIn(ExperimentalCoroutinesApi::class)
class SilentServerTest {
    /** A server as [awaitSilence] sees it: how much it has sent, and whether it answers when asked. */
    private class Server(var answers: Boolean = true) {
        var sent = 0L
        var asked = 0

        fun ask() {
            asked++
            if (answers) sent++
        }
    }

    @Test
    fun aServerThatStopsAnsweringIsGivenUpOnAfterTwentySeconds() = runTest {
        val server = Server(answers = false)
        awaitSilence(heard = { server.sent }, ask = server::ask)
        assertEquals(20_000, currentTime)
        assertEquals(1, server.asked, "It is asked once, after ten quiet seconds")
    }

    @Test
    fun aQuietServerThatAnswersIsKept() = runTest {
        val server = Server(answers = true)
        val watch = launch { awaitSilence(heard = { server.sent }, ask = server::ask) }
        advanceTimeBy(600_000)
        runCurrent()
        assertTrue(watch.isActive, "Ten minutes of nothing but answers is a server that is there")
        assertEquals(54, server.asked, "Asked after each ten quiet seconds, which start again a second after each answer")
        watch.cancel()
    }

    @Test
    fun aBusyServerIsNeverAsked() = runTest {
        val server = Server()
        val watch = launch { awaitSilence(heard = { server.sent }, ask = server::ask) }
        repeat(120) {
            advanceTimeBy(5_000)
            server.sent++ // an event, or the answer to something the app asked
        }
        runCurrent()
        assertTrue(watch.isActive)
        assertEquals(0, server.asked)
        watch.cancel()
    }

    @Test
    fun anAnswerThatComesLateStillCounts() = runTest {
        val server = Server(answers = false)
        val watch = launch { awaitSilence(heard = { server.sent }, ask = server::ask) }
        advanceTimeBy(18_500) // asked at ten seconds, and nothing yet
        assertEquals(1, server.asked)
        server.sent++ // the answer, eight seconds on
        advanceTimeBy(15_000)
        runCurrent()
        assertTrue(watch.isActive, "It answered within its ten seconds, so the count starts over")
        assertEquals(2, server.asked)
        advanceTimeBy(20_000)
        runCurrent()
        assertFalse(watch.isActive, "Then it never answered again")
    }

    @Test
    fun runningOutOfTimeIsAFailureAndNotACancellation() = runTest {
        var tries = 0
        var reasons = listOf<String>()
        // What a host's connection does: try, say why it failed, try again, and pass a real cancellation on.
        val trying = launch {
            while (tries < 3) {
                tries++
                try {
                    within(10_000) { awaitCancellation() }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    reasons += failure.message.orEmpty()
                }
            }
        }
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(3, tries, "A server that accepts and then says nothing is tried again")
        assertEquals(List(3) { "The server didn't answer in time" }, reasons)
        assertTrue(trying.isCompleted)
    }

    @Test
    fun aRealCancellationStillStopsTheTrying() = runTest {
        var failures = 0
        val trying = launch {
            try {
                within(10_000) { awaitCancellation() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failures++
            }
        }
        advanceTimeBy(3_000)
        trying.cancel()
        runCurrent()
        assertTrue(trying.isCancelled)
        assertEquals(0, failures)
    }
}
