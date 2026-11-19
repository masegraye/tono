package org.example.kotlintest.server

import dev.mg.omarchy.theme.readInstalledMonospaceFonts
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The fonts a page can ask the server for by name, and what it gets on a machine without Omarchy. */
class TonoFontsTest {
    @Test
    fun `the installed monospace fonts are listed, and one of them is served by name`() = testApplication {
        application { tonoAssets() }
        val listed = Json.parseToJsonElement(client.get("/api/tono/fonts").bodyAsText()).jsonArray.map { it.jsonObject.getValue("family").jsonPrimitive.content }
        assertEquals(readInstalledMonospaceFonts().map { it.family }, listed)
        // A machine with no fonts to list has nothing more to check.
        val family = listed.firstOrNull() ?: return@testApplication
        val response = client.get("/api/tono/font/regular") { url { parameters.append("family", family) } }
        // A font kept in a collection can't be handed to a page, and is refused; anything else is a font file.
        if (response.status == HttpStatusCode.OK) assertTrue(response.readRawBytes().size > 1_000)
        else assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `a font that isn't installed, and a style there isn't, are not found`() = testApplication {
        application { tonoAssets() }
        assertEquals(HttpStatusCode.NotFound, client.get("/api/tono/font/regular") { url { parameters.append("family", "No Such Font Anywhere") } }.status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/tono/font/regular") { url { parameters.append("family", "../../etc/passwd") } }.status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/tono/font/heavy").status)
    }

    @Test
    fun `without Omarchy there is no desktop font to give, and the installed ones can still be asked for`() = testApplication {
        application { tonoAssets() }
        val before = System.getProperty("omarchy.absent")
        System.setProperty("omarchy.absent", "true")
        try {
            assertEquals(HttpStatusCode.NotFound, client.get("/api/tono/font/regular").status)
            assertEquals(HttpStatusCode.OK, client.get("/api/tono/fonts").status)
        } finally {
            if (before == null) System.clearProperty("omarchy.absent") else System.setProperty("omarchy.absent", before)
        }
    }

    @Test
    fun `bold is bold, or heavier where regular text is nearly bold already`() {
        assertEquals(700, boldWeightFor(400))
        assertEquals(700, boldWeightFor(300))
        assertEquals(900, boldWeightFor(600))
    }
}
