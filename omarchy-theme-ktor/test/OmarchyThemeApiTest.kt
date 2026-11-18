package dev.mg.omarchy.theme.ktor

import dev.mg.omarchy.theme.OmarchyAppearance
import dev.mg.omarchy.theme.bundledOmarchyPalettes
import dev.mg.omarchy.theme.omarchyAppearanceFromJson
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
import java.io.BufferedReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals

class OmarchyThemeApiTest {
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
        application { omarchyThemeApi(MutableStateFlow(retro)) }

        val response = client.get("/api/theme")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ContentType.Application.Json, response.contentType()?.withoutParameters())
        assertEquals(retro, omarchyAppearanceFromJson(response.bodyAsText()))
    }

    @Test
    fun `no theme on this machine is a 404`() = testApplication {
        application { omarchyThemeApi(MutableStateFlow(null)) }

        assertEquals(HttpStatusCode.NotFound, client.get("/api/theme").status)
    }

    @Test
    fun `the api can be mounted elsewhere`() = testApplication {
        application { omarchyThemeApi(MutableStateFlow(appearance("retro-82")), path = "/desktop/look") }

        assertEquals(HttpStatusCode.OK, client.get("/desktop/look").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/theme").status)
    }

    // Over a real socket: the stream never ends, which the in-memory test host can't tear down.
    @Test
    fun `event stream sends the current theme and then each change`() = runBlocking {
        val state = MutableStateFlow<OmarchyAppearance?>(appearance("retro-82"))
        val server = embeddedServer(Netty, port = 0, host = "127.0.0.1") { omarchyThemeApi(state) }
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
}
