package org.example.kotlintest

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The pictures a host can have beside its name.
 *
 * A host in the home server's host list has an `icon`: a name, which Rex keeps and doesn't
 * interpret. The names in use are the ones Rex for Mac's "Add Remote Host" form offers, which
 * are these seven. Rex Rune doesn't read the field; it draws [Desktop] for a Unix socket and
 * [Server] for anything else, which is also what a host without an icon gets here.
 */
internal enum class HostIcon {
    Globe, Server, Cloud, Laptop, Mini, Studio, Desktop;

    companion object {
        /**
         * The picture a host list's icon name stands for, or null for a name this app has no
         * picture for (and for an emoji or an address of an image, which the field isn't for).
         *
         * Rex for Mac names its seven `globe`, `server.rack`, `cloud`, `macbook`, `macmini`,
         * `macstudio` and `desktopcomputer`. Case and punctuation are set aside, and the plain
         * words someone would give `rex hosts add --icon` are taken too.
         */
        fun named(name: String?): HostIcon? = when (name?.lowercase()?.filter { it in 'a'..'z' || it in '0'..'9' }) {
            "globe", "world", "web", "internet", "network" -> Globe
            "server", "serverrack", "rack" -> Server
            "cloud" -> Cloud
            "macbook", "laptop", "laptopcomputer", "notebook" -> Laptop
            "macmini", "mini", "minipc" -> Mini
            "macstudio", "studio" -> Studio
            "desktopcomputer", "desktop", "display", "monitor", "imac", "computer", "pc" -> Desktop
            else -> null
        }

        /**
         * The picture for a host: the one its icon names, or failing that the one every host
         * had before icons were read, a screen for this machine and a rack for any other.
         */
        fun of(name: String?, isHome: Boolean): HostIcon = named(name) ?: if (isHome) Desktop else Server
    }
}

/**
 * A small picture of a machine, for a host's heading and for a host in the palette. Drawn
 * here, in lines the weight of the text beside it, so it needs no icon font and takes
 * whatever color the theme gives it: dimmed while a host is being reached, the error color
 * when it is down.
 */
@Composable
internal fun MachineIcon(icon: HostIcon, tint: Color, size: Dp = 16.dp) {
    Canvas(Modifier.size(size)) {
        val unit = this.size.width / 16f
        val width = 1.5f * unit
        val line = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val corner = CornerRadius(1.6f * unit)
        fun at(x: Float, y: Float) = Offset(x * unit, y * unit)
        fun box(x: Float, y: Float, w: Float, h: Float) = drawRoundRect(tint, at(x, y), Size(w * unit, h * unit), corner, line)
        fun dot(x: Float, y: Float) = drawCircle(tint, 0.9f * unit, at(x, y))
        fun stroke(fromX: Float, fromY: Float, toX: Float, toY: Float, cap: StrokeCap = StrokeCap.Round) =
            drawLine(tint, at(fromX, fromY), at(toX, toY), width, cap)
        fun oval(left: Float, top: Float, right: Float, bottom: Float) = Rect(left * unit, top * unit, right * unit, bottom * unit)
        when (icon) {
            // A pair of stacked units, as in a rack.
            HostIcon.Server -> for (top in listOf(2.5f, 9f)) {
                box(1.5f, top, 13f, 4.5f)
                dot(4.4f, top + 2.25f)
            }
            // A screen on its stand.
            HostIcon.Desktop -> {
                box(1.5f, 2.5f, 13f, 8.5f)
                stroke(8f, 11f, 8f, 13.5f, StrokeCap.Butt)
                stroke(4.5f, 13.75f, 11.5f, 13.75f)
            }
            // The world: its outline, the equator and one meridian.
            HostIcon.Globe -> {
                drawCircle(tint, 6.25f * unit, at(8f, 8f), style = line)
                drawOval(tint, at(5.25f, 1.75f), Size(5.5f * unit, 12.5f * unit), style = line)
                stroke(1.75f, 8f, 14.25f, 8f, StrokeCap.Butt)
            }
            // Three bumps over a flat base.
            HostIcon.Cloud -> drawPath(
                Path().apply {
                    moveTo(4.5f * unit, 12.5f * unit)
                    arcTo(oval(2f, 7.5f, 7f, 12.5f), 90f, 180f, false)
                    arcTo(oval(4.5f, 3.25f, 11.5f, 10.25f), 190f, 174f, false)
                    arcTo(oval(8.75f, 7f, 14.25f, 12.5f), 270f, 180f, false)
                    close()
                },
                tint, style = line,
            )
            // An open lid over the edge of its base.
            HostIcon.Laptop -> {
                box(3f, 3f, 10f, 7.5f)
                stroke(1.5f, 13f, 14.5f, 13f)
            }
            // A low, wide box with its light at the front.
            HostIcon.Mini -> {
                box(1.5f, 5.75f, 13f, 4.5f)
                dot(12f, 8f)
            }
            // A squat box with a slot and a light on its face.
            HostIcon.Studio -> {
                box(2.5f, 3.5f, 11f, 9f)
                stroke(5f, 9.75f, 8f, 9.75f)
                dot(11f, 9.75f)
            }
        }
    }
}
