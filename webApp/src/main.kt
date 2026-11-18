package org.example.kotlintest

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.window.ComposeViewport
import dev.mg.omarchy.theme.compose.omarchyWebFont
import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.example.kotlintest.rex.RexClient
import org.example.kotlintest.rex.pageUrl

/**
 * Tono in a browser. It is the app the desktop runs, with what a page can't do for itself
 * done by the server that serves it: connections to Rex servers go through that server, and
 * the terminal library, the desktop's font and its sounds come from it.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    // Connecting starts at once, as on the desktop: every configured server together.
    val client = RexClient(CoroutineScope(SupervisorJob() + Dispatchers.Default))
    ComposeViewport {
        // Everything is drawn in the desktop's font, so it has to be here before anything is
        // drawn: a terminal laid out in another font and then switched would jump.
        var font by remember { mutableStateOf<FontFamily?>(null) }
        var fontTried by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            font = desktopFont()
            omarchyWebFont = font
            fontTried = true
        }
        if (!fontTried) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Tono") }
        } else {
            CompositionLocalProvider(LocalTerminalFont provides font) {
                TonoApp(client)
            }
        }
    }
}

/**
 * The desktop's monospace font in its four styles, from the server. Null if the server has
 * none to give, in which case text falls back to the browser's default and terminals, which
 * need every character the same width, will look wrong.
 */
private suspend fun desktopFont(): FontFamily? {
    val client = HttpClient(Js)
    return try {
        val styles = listOf(
            Triple("regular", FontWeight.Normal, FontStyle.Normal),
            Triple("bold", FontWeight.Bold, FontStyle.Normal),
            Triple("italic", FontWeight.Normal, FontStyle.Italic),
            Triple("bolditalic", FontWeight.Bold, FontStyle.Italic),
        )
        val fonts = coroutineScope {
            styles.map { (name, weight, style) ->
                async {
                    runCatching {
                        val response = client.get(pageUrl("api/tono/font/$name"))
                        if (response.status.isSuccess()) Font("desktop-$name", response.readRawBytes(), weight, style) else null
                    }.getOrNull()
                }
            }.awaitAll().filterNotNull()
        }
        if (fonts.isEmpty()) null else FontFamily(fonts)
    } finally {
        client.close()
    }
}
