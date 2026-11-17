package org.example.kotlintest

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

@Composable
fun SectionMotion() {
    SectionScroll {
        SectionCard(
            "Animated visibility",
            "Enter and exit transitions compose from fade, slide, scale and expand.",
        ) {
            var visible by remember { mutableStateOf(true) }
            OutlinedButton(onClick = { visible = !visible }) {
                Text(if (visible) "Hide" else "Show")
            }
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn() + slideInVertically { -it / 2 },
                exit = fadeOut() + slideOutVertically { it / 2 },
            ) {
                Card(colors = wellCardColors()) {
                    Text(
                        "I appear and disappear with a shared animation on all four platforms.",
                        Modifier.padding(16.dp),
                    )
                }
            }
        }

        SectionCard(
            "Animated content",
            "Direction-aware counter: content swaps animate based on the change.",
        ) {
            var count by remember { mutableIntStateOf(0) }
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = { count-- }) { Text("-") }
                AnimatedContent(
                    targetState = count,
                    transitionSpec = {
                        if (targetState > initialState) {
                            (slideInVertically { -it } + fadeIn()) togetherWith
                                (slideOutVertically { it } + fadeOut())
                        } else {
                            (slideInVertically { it } + fadeIn()) togetherWith
                                (slideOutVertically { -it } + fadeOut())
                        }
                    },
                    label = "counter",
                ) { value ->
                    Text(
                        "$value",
                        style = MaterialTheme.typography.displayLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Button(onClick = { count++ }) { Text("+") }
            }
        }

        SectionCard(
            "Infinite transitions",
            "Pulsing size and color cycling, running forever on one animation clock.",
        ) {
            val infinite = rememberInfiniteTransition(label = "pulse")
            val scale by infinite.animateFloat(
                1f, 1.3f,
                infiniteRepeatable(tween(650), RepeatMode.Reverse),
                label = "scale",
            )
            val color by animateColorAsState(
                targetValue = if (scale > 1.15f) MaterialTheme.colorScheme.tertiary
                else MaterialTheme.colorScheme.primary,
                animationSpec = tween(600),
                label = "color",
            )
            Row(
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(
                    modifier = Modifier
                        .size(84.dp)
                        .graphicsLayer { scaleX = scale; scaleY = scale }
                        .background(color, CircleShape),
                )
            }
        }

        SectionCard(
            "Size & spring physics",
            "animateContentSize plus springy press feedback with bounce.",
        ) {
            var big by remember { mutableStateOf(false) }
            Card(
                colors = wellCardColors(),
                modifier = Modifier
                    .fillMaxWidth()
                    .animateContentSize(spring(stiffness = Spring.StiffnessLow))
                    .clickable { big = !big },
            ) {
                Text(
                    if (big) "Tap again to shrink" else "Tap to grow",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(16.dp),
                )
                if (big) {
                    Text(
                        "Cards can smoothly resize while their content changes — no manual " +
                            "layout animation, just one modifier.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    )
                }
            }

            var boing by remember { mutableStateOf(false) }
            val bounce by animateFloatAsState(
                targetValue = if (boing) 1.25f else 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow,
                ),
                label = "bounce",
            )
            Row(
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .scale(bounce)
                        .background(MaterialTheme.colorScheme.secondary, CircleShape)
                        .clickable { boing = !boing },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "boing",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSecondary,
                    )
                }
            }
        }
    }
}
