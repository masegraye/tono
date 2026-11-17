package org.example.kotlintest.server

import dev.mg.omarchy.theme.OmarchyAppearance
import dev.mg.omarchy.theme.bundledOmarchyPalettes
import dev.mg.omarchy.theme.ktor.omarchyThemeApi
import dev.mg.omarchy.theme.omarchyAppearanceFromJson
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.flow.MutableStateFlow
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class ApplicationTest {
    @Test
    fun `web app files are served from the web directory`() = testApplication {
        val webDir = Files.createTempDirectory("web").toFile()
        webDir.resolve("index.html").writeText("<html>showcase</html>")
        application { webApp(webDir) }

        assertEquals("<html>showcase</html>", client.get("/").bodyAsText())
        webDir.deleteRecursively()
    }

    @Test
    fun `nothing is served without a web directory`() = testApplication {
        application { webApp(null) }

        assertEquals(HttpStatusCode.NotFound, client.get("/").status)
    }

    // The theme API has its own tests in the library; this checks it sits beside the web app.
    @Test
    fun `theme api and web app are served together`() = testApplication {
        val webDir = Files.createTempDirectory("web").toFile()
        webDir.resolve("index.html").writeText("<html>showcase</html>")
        val tokyo = OmarchyAppearance(bundledOmarchyPalettes.first { it.slug == "tokyo-night" }, fontName = null)
        application {
            omarchyThemeApi(MutableStateFlow(tokyo))
            webApp(webDir)
        }

        assertEquals(tokyo, omarchyAppearanceFromJson(client.get("/api/theme").bodyAsText()))
        assertEquals("<html>showcase</html>", client.get("/").bodyAsText())
        webDir.deleteRecursively()
    }
}
