package org.example.kotlintest

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.kotlintest.resources.Res
import org.example.kotlintest.resources.compose_multiplatform
import org.jetbrains.compose.resources.painterResource

@Composable
fun SectionPlatform() {
    val platform = remember { getPlatform().name }
    SectionScroll {
        SectionCard(
            "This device",
            "Platform identity comes from a tiny expect/actual — the only per-platform code " +
                "in this app besides the Omarchy theme reader.",
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Image(
                    painterResource(Res.drawable.compose_multiplatform),
                    contentDescription = "Compose Multiplatform logo",
                    modifier = Modifier.size(96.dp),
                )
                Column {
                    Text(
                        platform,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        Greeting().greet(),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        SectionCard(
            "One codebase, four targets",
            "All of the UI you have browsed is compiled from this single shared module.",
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = {}, label = { Text("Android") })
                AssistChip(onClick = {}, label = { Text("iOS") })
                AssistChip(onClick = {}, label = { Text("Desktop (JVM)") })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = {}, label = { Text("Web (Kotlin/Wasm)") })
                AssistChip(onClick = {}, label = { Text("Compose Multiplatform") })
            }
        }

        SectionCard(
            "How it's built",
            "The Kotlin Toolchain (kotlin-toolchain.org) drives the build; Compose Multiplatform is the UI toolkit.",
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("product: kmp/lib — android, iosArm64, iosSimulatorArm64, jvm, wasmJs", style = MaterialTheme.typography.bodySmall)
                Text("./kotlin build · run · test — no Gradle files in the repo", style = MaterialTheme.typography.bodySmall)
                Text("JDK, Android SDK, node, pnpm and Chromium are provisioned automatically", style = MaterialTheme.typography.bodySmall)
                Text("The desktop app runs with Compose Hot Reload via @DevelopmentEntryPoint", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
