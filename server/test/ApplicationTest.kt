package org.example.kotlintest.server

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import org.example.kotlintest.OmarchyAppearance
import org.example.kotlintest.bundledOmarchyPalettes
import org.example.kotlintest.omarchyAppearanceFromJson
import java.io.BufferedReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class ApplicationTest {
    private fun appearance(slug: String) =
        OmarchyAppearance(bundledOmarchyPalettes.first { it.slug == slug }, fontName = "JetBrainsMono Nerd Font")

    /** Reads the stream up to the next `data:` line and parses it. */
    private suspend fun BufferedReader.nextTheme(): OmarchyAppearance? = withTimeout(5_000) {
        runInterruptible(Dispatchers.IO) {
            val line = generateSequence { readLine() }.first { it.startsWith("data:") }
            omarchyAppearanceFromJson(line.removePrefix("data:").trim())
        }
    }

    @Test
    fun `current theme is served as json`() = testApplication {
        val retro = appearance("retro-82")
        application { module(MutableStateFlow(retro), webDir = null) }

        val response = client.get("/api/theme")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ContentType.Application.Json, response.contentType()?.withoutParameters())
        assertEquals(retro, omarchyAppearanceFromJson(response.bodyAsText()))
    }

    @Test
    fun `no theme on this machine is a 404`() = testApplication {
        application { module(MutableStateFlow(null), webDir = null) }

        assertEquals(HttpStatusCode.NotFound, client.get("/api/theme").status)
    }

    // Over a real socket: the stream never ends, which the in-memory test host can't tear down.
    @Test
    fun `event stream sends the current theme and then each change`() = runBlocking {
        val state = MutableStateFlow<OmarchyAppearance?>(appearance("retro-82"))
        val server = embeddedServer(Netty, port = 0, host = "127.0.0.1") { module(state, webDir = null) }
        server.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().first().port
            val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/theme/events")).build()
            val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofInputStream())
            assertEquals("text/event-stream", response.headers().firstValue("Content-Type").orElse("").substringBefore(';'))

            response.body().bufferedReader().use { stream ->
                assertEquals("retro-82", stream.nextTheme()?.palette?.slug)

                state.value = appearance("tokyo-night") // the user switches theme on the desktop

                assertEquals(appearance("tokyo-night"), stream.nextTheme())
            }
        } finally {
            server.stop(gracePeriodMillis = 100, timeoutMillis = 1_000)
        }
    }

    @Test
    fun `web app files are served from the web directory`() = testApplication {
        val webDir = Files.createTempDirectory("web").toFile()
        webDir.resolve("index.html").writeText("<html>showcase</html>")
        application { module(MutableStateFlow(null), webDir) }

        assertEquals("<html>showcase</html>", client.get("/").bodyAsText())
        webDir.deleteRecursively()
    }
}
