package org.example.kotlintest

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.window.ComposeViewport
import dev.mg.omarchy.theme.compose.LocalOmarchyFont
import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import org.example.kotlintest.rex.RexClient
import org.example.kotlintest.rex.TonoClient
import org.example.kotlintest.rex.pageUrl

/**
 * Tono in a browser. It is the app the desktop runs, with what a page can't do for itself
 * done by the server that serves it: connections to Rex servers go through that server, and
 * the terminal library, the desktop's font and its sounds come from it.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport {
        var client by remember { mutableStateOf<RexClient?>(null) }
        var font by remember { mutableStateOf<FontFamily?>(null) }
        LaunchedEffect(Unit) {
            val http = HttpClient(Js)
            try {
                // Everything is drawn in the desktop's font, so its regular style has to be
                // here before anything is drawn: a terminal laid out in another font and
                // then switched would jump. The page stays as it loaded until then.
                val (device, regular) = coroutineScope {
                    val device = async { withTimeoutOrNull(DEVICE_TIMEOUT_MILLIS) { deviceName(http) } }
                    val regular = async { desktopFont(http, STYLES.first()) }
                    device.await() to regular.await()
                }
                font = regular?.let { FontFamily(it) }
                // Connecting starts now, as on the desktop: every configured server together.
                client = RexClient(CoroutineScope(SupervisorJob() + Dispatchers.Default), info = TonoClient.labelled(device))
                // Bold and italic are the same width as regular, so they can arrive after:
                // until they do, they are drawn as regular made heavier or slanted.
                if (regular != null) {
                    val others = coroutineScope { STYLES.drop(1).map { async { desktopFont(http, it) } }.awaitAll() }.filterNotNull()
                    if (others.isNotEmpty()) font = FontFamily(listOf(regular) + others)
                }
            } finally {
                http.close()
            }
        }
        val running = client
        if (running == null) {
            Box(Modifier.fillMaxSize())
        } else {
            CompositionLocalProvider(LocalOmarchyFont provides font) {
                TonoApp(running)
            }
        }
    }
}

private class Style(val name: String, val weight: FontWeight, val style: FontStyle)

private val STYLES = listOf(
    Style("regular", FontWeight.Normal, FontStyle.Normal),
    Style("bold", FontWeight.Bold, FontStyle.Normal),
    Style("italic", FontWeight.Normal, FontStyle.Italic),
    Style("bolditalic", FontWeight.Bold, FontStyle.Italic),
)

/**
 * One style of the desktop's monospace font, from the server. Null if the server has none
 * to give, in which case text falls back to the browser's default and terminals, which need
 * every character the same width, will look wrong.
 */
private suspend fun desktopFont(http: HttpClient, style: Style): Font? = runCatching {
    val response = http.get(pageUrl("api/tono/font/${style.name}"))
    if (response.status.isSuccess()) Font("desktop-${style.name}", response.readRawBytes(), style.weight, style.style) else null
}.getOrNull()

/**
 * The name of the device this page is open on, when that isn't the machine serving it. Rex
 * servers see every browser as that one machine connecting, since it makes the connections;
 * the name goes along as the client's label so that their lists of clients can tell a phone
 * from a laptop.
 */
private suspend fun deviceName(http: HttpClient): String? = runCatching {
    val who = Json.parseToJsonElement(http.get(pageUrl("api/tono/whoami")).bodyAsText()).jsonObject
    val local = (who["local"] as? JsonPrimitive)?.booleanOrNull == true
    (who["device"] as? JsonPrimitive)?.content?.takeIf { !local && it.isNotBlank() }
}.getOrNull()

private const val DEVICE_TIMEOUT_MILLIS = 3_000L
