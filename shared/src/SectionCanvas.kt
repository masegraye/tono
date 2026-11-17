package org.example.kotlintest

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

@Composable
fun SectionCanvas() {
    val scheme = MaterialTheme.colorScheme
    SectionScroll {
        SectionCard(
            "Canvas drawing",
            "Immediate-mode drawing with the theme palette — an animated sine wave.",
        ) {
            val infinite = rememberInfiniteTransition(label = "wave")
            val phase by infinite.animateFloat(
                0f, (2 * PI).toFloat(),
                infiniteRepeatable(tween(2600, easing = LinearEasing)),
                label = "phase",
            )
            Canvas(modifier = Modifier.fillMaxWidth().height(110.dp)) {
                val wavelength = size.width / 2.5f
                fun wavePath(speed: Float, amplitude: Float): Path {
                    val path = Path()
                    var x = 0f
                    while (x <= size.width) {
                        val y = size.height / 2f +
                            amplitude * sin(x / wavelength * 2f * PI.toFloat() + phase * speed)
                        if (x == 0f) path.moveTo(x, y) else path.lineTo(x, y)
                        x += 2f
                    }
                    return path
                }
                drawPath(
                    wavePath(speed = 2f, amplitude = size.height * 0.22f),
                    scheme.tertiary.copy(alpha = 0.6f),
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                )
                drawPath(
                    wavePath(speed = 1f, amplitude = size.height * 0.36f),
                    scheme.primary,
                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
                )
            }
        }

        SectionCard(
            "Animated chart",
            "Bars animate from zero whenever the data changes.",
        ) {
            var values by remember {
                mutableStateOf(List(7) { Random.nextFloat() * 0.75f + 0.25f })
            }
            val animation = remember(values) { Animatable(0f) }
            LaunchedEffect(values) {
                animation.snapTo(0f)
                animation.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
            }
            val colors = listOf(
                scheme.primary, scheme.tertiary, scheme.secondary,
                scheme.primaryContainer, scheme.tertiaryContainer, scheme.secondaryContainer,
                scheme.outline,
            )
            Canvas(modifier = Modifier.fillMaxWidth().height(130.dp)) {
                val slot = size.width / values.size
                values.forEachIndexed { index, value ->
                    val barHeight = (size.height - 6f) * value * animation.value
                    drawRoundRect(
                        color = colors[index % colors.size],
                        topLeft = Offset(index * slot + slot * 0.22f, size.height - barHeight),
                        size = Size(slot * 0.56f, barHeight),
                        cornerRadius = CornerRadius(7f, 7f),
                    )
                }
            }
            OutlinedButton(onClick = { values = List(7) { Random.nextFloat() * 0.75f + 0.25f } }) {
                Icon(Icons.Filled.Refresh, contentDescription = null)
                Spacer(Modifier.size(4.dp))
                Text("Shuffle data")
            }
        }

        SectionCard(
            "Gestures",
            "Drag the ball; pinch/scroll to zoom, drag to pan, twist to rotate the card.",
        ) {
            // Drag to move
            var ballOffset by remember { mutableStateOf(Offset.Zero) }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(scheme.surfaceContainerLow)
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            ballOffset += dragAmount
                            change.consume()
                        }
                    },
            ) {
                Box(
                    modifier = Modifier
                        .offset { IntOffset(ballOffset.x.roundToInt(), ballOffset.y.roundToInt()) }
                        .size(44.dp)
                        .background(scheme.primary, CircleShape),
                )
                Text(
                    "drag me",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                )
            }

            Spacer(Modifier.height(12.dp))

            // Pinch zoom / pan / rotate
            var scale by remember { mutableStateOf(1f) }
            var angle by remember { mutableStateOf(0f) }
            var panOffset by remember { mutableStateOf(Offset.Zero) }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(190.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(scheme.surfaceContainerLow)
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, rotation ->
                            scale = (scale * zoom).coerceIn(0.4f, 4f)
                            angle += rotation
                            panOffset += pan
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Card(
                    modifier = Modifier
                        .size(88.dp)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            rotationZ = angle
                            translationX = panOffset.x
                            translationY = panOffset.y
                        },
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Filled.Star,
                            contentDescription = null,
                            modifier = Modifier.size(42.dp),
                            tint = scheme.primary,
                        )
                    }
                }
                Text(
                    "zoom ${(scale * 100).toInt()}% · ${angle.roundToInt()}°",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
                )
            }
            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedButton(onClick = {
                    scale = 1f; angle = 0f; panOffset = Offset.Zero
                }) { Text("Reset") }
            }
        }

        SectionCard(
            "Everything else",
            "The Canvas API also covers arcs, gradients, blend modes, image drawing and custom text layout — the building blocks for charts, mini-maps and signature views.",
        ) {
            Canvas(modifier = Modifier.fillMaxWidth().height(90.dp)) {
                // A small arc gauge as a taste of custom drawing. A 240° arc starting at
                // 150° is 0.75 diameters tall, so size the circle to fit the canvas height.
                val stroke = 8.dp.toPx()
                val diameter = (size.height - stroke) / 0.75f
                val topLeft = Offset(size.width / 2f - diameter / 2f, stroke / 2f)
                val arcSize = Size(diameter, diameter)
                drawArc(
                    color = scheme.outlineVariant,
                    startAngle = 150f, sweepAngle = 240f, useCenter = false,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                    topLeft = topLeft, size = arcSize,
                )
                drawArc(
                    color = scheme.primary,
                    startAngle = 150f, sweepAngle = 240f * 0.72f, useCenter = false,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                    topLeft = topLeft, size = arcSize,
                )
            }
        }
    }
}
