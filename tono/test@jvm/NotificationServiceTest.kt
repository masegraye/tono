package org.example.kotlintest

import kotlinx.coroutines.runBlocking
import java.io.BufferedReader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The desktop's side of notifications, against a notification service of the test's own on
 * a D-Bus session of the test's own, so nothing is ever shown on the desktop the tests run
 * on. It needs `notify-send`, `gdbus`, `dbus-daemon` and Python with GObject; where one is
 * missing the tests say so and pass without checking anything.
 */
class NotificationServiceTest {
    private var bus: Process? = null
    private var service: Process? = null
    private var said: BufferedReader? = null
    private var script: Path? = null
    private val heard = CopyOnWriteArrayList<String>()
    private val log = CopyOnWriteArrayList<String>()

    /** Starts the session and the service. False where that can't be done. */
    private fun start(): Boolean {
        val missing = listOf("notify-send", "gdbus", "dbus-daemon", "python3").filterNot(::onPath)
        val python = missing.isEmpty() && ProcessBuilder("python3", "-c", "from gi.repository import Gio, GLib").redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor() == 0
        if (!python) {
            println("NotificationServiceTest skipped: needs notify-send, gdbus, dbus-daemon and python3 with gi (missing: ${missing.ifEmpty { listOf("gi") }})")
            return false
        }
        val session = ProcessBuilder("dbus-daemon", "--session", "--nofork", "--print-address").redirectError(ProcessBuilder.Redirect.DISCARD).start()
        bus = session
        val address = session.inputStream.bufferedReader().readLine()
        val script = Files.createTempFile("tono-notification-service", ".py").also { Files.writeString(it, SERVICE) }
        this.script = script
        val builder = ProcessBuilder("python3", "-u", "-W", "ignore", script.toString()).redirectError(ProcessBuilder.Redirect.INHERIT)
        builder.environment()["DBUS_SESSION_BUS_ADDRESS"] = address
        service = builder.start()
        said = service!!.inputStream.bufferedReader()
        assertEquals("ready", said!!.readLine())
        Thread { runCatching { said!!.forEachLine { heard += it } } }.apply { isDaemon = true }.start()
        notificationBus = address
        return true
    }

    @AfterTest
    fun stop() {
        notificationBus = null
        service?.destroyForcibly()
        bus?.destroyForcibly()
        script?.let(Files::deleteIfExists)
    }

    private fun onPath(name: String) = System.getenv("PATH").orEmpty().split(':').any { Files.isExecutable(Path.of(it, name)) }

    /** Tells the service to do what a person at the desktop would: `click 3`, `dismiss 3`. */
    private fun tell(command: String) = service!!.outputStream.run { write("$command\n".toByteArray()); flush() }

    /** Waits until the service has heard [count] things starting with [prefix], and returns them. */
    private fun heard(prefix: String, count: Int): List<String> {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < until) {
            heard.filter { it.startsWith(prefix) }.let { if (it.size >= count) return it }
            Thread.sleep(20)
        }
        error("waited for $count × '$prefix', heard $heard, logged $log")
    }

    private fun show(note: DesktopNote, onClick: () -> Unit = {}) = runBlocking { showNotification(note, { message, _ -> log += message }, onClick) }

    @Test
    fun `a tab's second notification takes the first one's place, and another tab's stands beside it`() {
        if (!start()) return
        show(DesktopNote("test-a", "agent · Needs you", "work · studio", urgent = true))
        show(DesktopNote("test-a", "agent · Done", "work · studio"))
        show(DesktopNote("test-b", "build · Done", "work · studio"))
        assertEquals(
            listOf(
                "notify id=1 replaces=0 urgency=2 app=Tono actions=default summary=agent · Needs you body=work · studio",
                "notify id=1 replaces=1 urgency=1 app=Tono actions=default summary=agent · Done body=work · studio",
                "notify id=2 replaces=0 urgency=1 app=Tono actions=default summary=build · Done body=work · studio",
            ),
            heard("notify", 3),
        )
        assertEquals(listOf("showed notification 1", "replaced notification 1", "showed notification 2"), log.toList())
        closeNotification("test-a")
        closeNotification("test-b")
        assertEquals(setOf("close 1", "close 2"), heard("close", 2).toSet())
    }

    @Test
    fun `what a program wrote reaches the service as text, not as markup or options`() {
        if (!start()) return
        show(DesktopNote("test-text", "--urgency=critical", "<b>1 & 2</b>\n-x"))
        // A service that reads markup turns this back into what the program wrote.
        assertEquals(listOf("notify id=1 replaces=0 urgency=1 app=Tono actions=default summary=--urgency=critical body=&lt;b&gt;1 &amp; 2&lt;/b&gt;\\n-x"), heard("notify", 1))
        closeNotification("test-text")
        heard("close", 1)
    }

    @Test
    fun `a click is heard once, on the notification that replaced the first, and the notification is taken down`() {
        if (!start()) return
        val firstClicked = AtomicInteger()
        val secondClicked = AtomicInteger()
        show(DesktopNote("test-click", "one", "")) { firstClicked.incrementAndGet() }
        show(DesktopNote("test-click", "two", "")) { secondClicked.incrementAndGet() }
        heard("notify", 2)
        tell("click 1")
        heard("close", 1)
        Thread.sleep(500)
        // Once by this app, and once more by notify-send itself where it is new enough to.
        val closes = heard.count { it == "close 1" }
        assertTrue(closes in 1..2, "closed $closes times")
        assertEquals(0, firstClicked.get())
        assertEquals(1, secondClicked.get())
        // It is over: the next for the same tab starts afresh, and there is nothing left to take down.
        show(DesktopNote("test-click", "three", ""))
        assertTrue(heard("notify", 3).last().startsWith("notify id=2 replaces=0 "))
        closeNotification("test-click")
        assertEquals("close 2", heard("close", closes + 1).last())
    }

    @Test
    fun `one that was dismissed at the desktop is not replaced, and not closed again`() {
        if (!start()) return
        show(DesktopNote("test-dismiss", "one", ""))
        heard("notify", 1)
        tell("dismiss 1")
        heard("dismissed", 1)
        Thread.sleep(500) // for the program waiting on it to notice and go
        closeNotification("test-dismiss")
        show(DesktopNote("test-dismiss", "two", ""))
        assertTrue(heard("notify", 2).last().startsWith("notify id=2 replaces=0 "))
        assertEquals(emptyList(), heard.filter { it.startsWith("close") })
        closeNotification("test-dismiss")
        heard("close", 1)
    }

    @Test
    fun `with no service to take it, showing says so and nothing is left waiting`() {
        if (!start()) return
        service!!.destroyForcibly().waitFor()
        show(DesktopNote("test-none", "one", ""))
        assertEquals(listOf("the desktop didn't take the notification: is a notification service running?"), log.toList())
    }

    private companion object {
        /** A notification service that shows nothing and says what it was asked. Clicks leave the notification up, as some desktops do. */
        val SERVICE = """
import sys, threading
from gi.repository import Gio, GLib

XML = '''<node><interface name="org.freedesktop.Notifications">
<method name="Notify"><arg type="s" direction="in"/><arg type="u" direction="in"/><arg type="s" direction="in"/><arg type="s" direction="in"/><arg type="s" direction="in"/><arg type="as" direction="in"/><arg type="a{sv}" direction="in"/><arg type="i" direction="in"/><arg type="u" direction="out"/></method>
<method name="CloseNotification"><arg type="u" direction="in"/></method>
<method name="GetCapabilities"><arg type="as" direction="out"/></method>
<method name="GetServerInformation"><arg type="s" direction="out"/><arg type="s" direction="out"/><arg type="s" direction="out"/><arg type="s" direction="out"/></method>
<signal name="NotificationClosed"><arg type="u"/><arg type="u"/></signal>
<signal name="ActionInvoked"><arg type="u"/><arg type="s"/></signal>
</interface></node>'''
PATH = '/org/freedesktop/Notifications'
NAME = 'org.freedesktop.Notifications'
last = 0
connection = None

def emit(signal, *values):
    kinds = {'NotificationClosed': '(uu)', 'ActionInvoked': '(us)'}
    connection.emit_signal(None, PATH, NAME, signal, GLib.Variant(kinds[signal], values))

def call(conn, sender, path, interface, method, params, invocation):
    global last
    if method == 'Notify':
        app, replaces, icon, summary, body, actions, hints, timeout = params.unpack()
        if replaces: number = replaces
        else:
            last += 1; number = last
        print('notify id=%d replaces=%d urgency=%s app=%s actions=%s summary=%s body=%s' % (number, replaces, hints.get('urgency'), app, ','.join(actions[::2]), summary, body.replace('\n', '\\n')), flush=True)
        invocation.return_value(GLib.Variant('(u)', (number,)))
    elif method == 'CloseNotification':
        number = params.unpack()[0]
        print('close %d' % number, flush=True)
        emit('NotificationClosed', number, 3)
        invocation.return_value(None)
    elif method == 'GetCapabilities':
        invocation.return_value(GLib.Variant('(as)', (['body', 'body-markup', 'actions'],)))
    else:
        invocation.return_value(GLib.Variant('(ssss)', ('test', 'tono', '1', '1.2')))

def owned(conn, name):
    global connection
    connection = conn
    conn.register_object(PATH, Gio.DBusNodeInfo.new_for_xml(XML).interfaces[0], call)

def acquired(conn, name):
    print('ready', flush=True)

def commands():
    for line in sys.stdin:
        what, number = line.split()
        def act(what=what, number=int(number)):
            if what == 'click': emit('ActionInvoked', number, 'default')
            else:
                emit('NotificationClosed', number, 2)
                print('dismissed %d' % number, flush=True)
            return False
        GLib.idle_add(act)

Gio.bus_own_name(Gio.BusType.SESSION, NAME, Gio.BusNameOwnerFlags.NONE, owned, acquired, None)
threading.Thread(target=commands, daemon=True).start()
GLib.MainLoop().run()
""".trimStart()
    }
}
