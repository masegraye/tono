package org.example.kotlintest.rex

import kotlin.test.Test
import kotlin.test.assertEquals

/** Which host each icon in the home server's host list belongs to. */
class HostIconsTest {
    private val shown = listOf(
        SavedHost("Local", "unix:///run/user/1000/rex/rex.sock"),
        SavedHost("studio", "https://studio.tail1234.ts.net"),
        SavedHost("Work", "https://work.tail1234.ts.net"),
        SavedHost("plain", "https://plain.tail1234.ts.net"),
    )

    private fun listed(label: String, endpoint: String, icon: String?) = ListedHost(null, label, endpoint, null, icon)

    @Test
    fun `a listed host's icon goes to the host shown with its label or its address`() {
        val icons = hostIconsByLabel(
            listOf(
                listed("STUDIO", "https://somewhere-else.example", "macstudio"),
                // Saved in the workspace under another name, and listed by its address.
                listed("office", "work.tail1234.ts.net", "macbook"),
                listed("plain", "https://plain.tail1234.ts.net", null),
                listed("gone", "https://gone.example", "cloud"),
            ),
            shown, homeLabel = "Local", ownAddresses = emptySet(),
        )
        assertEquals(mapOf("studio" to "macstudio", "Work" to "macbook"), icons)
    }

    @Test
    fun `the home server takes the icon of the entry that is itself`() {
        val icons = hostIconsByLabel(
            listOf(listed("omwork", "https://omwork.tail1234.ts.net", "desktopcomputer")),
            shown, homeLabel = "Local", ownAddresses = setOf(endpointKey("https://omwork.tail1234.ts.net")),
        )
        assertEquals(mapOf("Local" to "desktopcomputer"), icons)
    }

    @Test
    fun `an empty icon is no icon, and the first of two for one host counts`() {
        val icons = hostIconsByLabel(
            listOf(
                listed("plain", "https://plain.tail1234.ts.net", "  "),
                listed("studio", "https://studio.tail1234.ts.net", " globe "),
                listed("other", "https://studio.tail1234.ts.net", "cloud"),
            ),
            shown, homeLabel = "Local", ownAddresses = emptySet(),
        )
        assertEquals(mapOf("studio" to "globe"), icons)
    }
}
