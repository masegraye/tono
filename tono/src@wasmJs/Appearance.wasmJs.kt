@file:OptIn(ExperimentalWasmJsInterop::class)

package org.example.kotlintest

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.isSuccess
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import org.example.kotlintest.rex.pageUrl
import kotlin.js.ExperimentalWasmJsInterop

/*
 * A page has only the fonts it is given. Besides the app's own, it can have the monospace
 * fonts installed on the machine that serves it: the server lists them, and hands over the
 * files of the one asked for. A page served by something else has just the app's own.
 */

/** The monospace fonts the page's server has, or none if it isn't a server that says. */
internal actual suspend fun installedTerminalFonts(): List<TerminalFontInfo> = runCatching {
    val http = HttpClient(Js)
    try {
        val response = http.get(pageUrl("api/tono/fonts"))
        if (!response.status.isSuccess()) return@runCatching emptyList()
        Json.parseToJsonElement(response.bodyAsText()).jsonArray.mapNotNull { element ->
            val font = element as? JsonObject ?: return@mapNotNull null
            val family = (font["family"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
            TerminalFontInfo(family, (font["weights"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.intOrNull })
        }
    } finally {
        http.close()
    }
}.getOrDefault(emptyList())

internal actual fun installedFontIfReady(family: String, weight: Int): FontFamily? = fetched[family to weight]

/**
 * Fetches a family's four styles from the server. The regular one decides whether there is
 * such a font; a style the server can't give is drawn from the regular, made heavier or slanted.
 */
internal actual suspend fun loadInstalledFont(family: String, weight: Int): FontFamily? {
    fetched[family to weight]?.let { return it }
    val http = HttpClient(Js)
    try {
        suspend fun style(name: String, fontWeight: Int, fontStyle: FontStyle): Font? = runCatching {
            val response = http.get(pageUrl("api/tono/font/$name")) {
                parameter("family", family)
                parameter("weight", weight)
            }
            if (response.status.isSuccess()) Font("$family-$weight-$name", response.readRawBytes(), FontWeight(fontWeight), fontStyle) else null
        }.getOrNull()
        val bold = boldWeightFor(weight)
        val fonts = coroutineScope {
            listOf(
                async { style("regular", weight, FontStyle.Normal) },
                async { style("bold", bold, FontStyle.Normal) },
                async { style("italic", weight, FontStyle.Italic) },
                async { style("bolditalic", bold, FontStyle.Italic) },
            ).awaitAll()
        }
        if (fonts.first() == null) return null
        return FontFamily(fonts.filterNotNull()).also { fetched[family to weight] = it }
    } finally {
        http.close()
    }
}

private val fetched = HashMap<Pair<String, Int>, FontFamily>()

/** The page behind the app, and the color a phone's browser tints its own bars with, follow the theme. */
internal actual fun themeApplied(background: String, dark: Boolean): Unit = pageColor(background, if (dark) "dark" else "light")

private fun pageColor(color: String, scheme: String): Unit = js(
    """{
        document.documentElement.style.background = color;
        document.body.style.background = color;
        document.documentElement.style.colorScheme = scheme;
        const meta = document.querySelector('meta[name="theme-color"]');
        if (meta) meta.content = color;
    }"""
)

internal actual val INSTALLED_FONTS_ARE: String = "On the server"

@OptIn(org.jetbrains.compose.resources.ExperimentalResourceApi::class)
@androidx.compose.runtime.Composable
internal actual fun resourceFont(resource: org.jetbrains.compose.resources.FontResource, weight: FontWeight, style: FontStyle): Font? =
    org.jetbrains.compose.resources.preloadFont(resource, weight, style).value
