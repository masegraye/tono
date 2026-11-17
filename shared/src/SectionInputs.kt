package org.example.kotlintest

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

private val TARGETS = listOf("Android", "iOS", "Desktop", "Web")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SectionInputs() {
    SectionScroll {
        SectionCard(
            "Text fields",
            "Outlined text fields with labels, icons, error state and password masking.",
        ) {
            var name by rememberSaveable { mutableStateOf("") }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Your name") },
                supportingText = { Text("Hello, ${name.ifBlank { "stranger" }}!") },
                trailingIcon = {
                    if (name.isNotEmpty()) {
                        IconButton(onClick = { name = "" }) {
                            Icon(Icons.Filled.Clear, contentDescription = "Clear")
                        }
                    }
                },
                singleLine = true,
            )
            var password by rememberSaveable { mutableStateOf("") }
            var hidden by rememberSaveable { mutableStateOf(true) }
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                visualTransformation = if (hidden) PasswordVisualTransformation() else VisualTransformation.None,
                trailingIcon = {
                    IconButton(onClick = { hidden = !hidden }) {
                        Icon(Icons.Filled.Lock, contentDescription = if (hidden) "Show" else "Hide")
                    }
                },
            )
            var email by rememberSaveable { mutableStateOf("") }
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email") },
                isError = email.isNotEmpty() && !email.contains('@'),
                supportingText = {
                    if (email.isNotEmpty() && !email.contains('@')) Text("That doesn't look like an email")
                    else Text("Validation state is just a parameter")
                },
            )
        }

        SectionCard(
            "Sliders, switches, checks & radios",
            "Stateful selection controls rendered identically everywhere.",
        ) {
            var volume by remember { mutableStateOf(0.6f) }
            Text("Volume: ${(volume * 100).toInt()}%")
            Slider(value = volume, onValueChange = { volume = it })

            var steps by remember { mutableStateOf(2) }
            Text("Steps: ${steps + 1}")
            Slider(
                value = steps.toFloat(),
                onValueChange = { steps = it.toInt() },
                valueRange = 0f..4f,
                steps = 3,
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Ship it", Modifier.weight(1f))
                Switch(checked = true, onCheckedChange = {})
            }

            val flavors = listOf("Vanilla", "Chocolate", "Pistachio")
            var picks by remember { mutableStateOf(setOf(0)) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                flavors.forEachIndexed { index, flavor ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = index in picks,
                            onCheckedChange = { checked ->
                                picks = if (checked) picks + index else picks - index
                            },
                        )
                        Text(flavor)
                    }
                }
            }

            var radio by remember { mutableStateOf(1) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TARGETS.forEachIndexed { index, target ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = radio == index,
                            onClick = { radio = index },
                        )
                        Text(target)
                    }
                }
            }
        }

        SectionCard(
            "Segmented buttons & dropdowns",
            "Single-choice segmented buttons and an exposed dropdown menu.",
        ) {
            var target by remember { mutableStateOf(0) }
            SingleChoiceSegmentedButtonRow {
                TARGETS.forEachIndexed { index, label ->
                    SegmentedButton(
                        selected = target == index,
                        onClick = { target = index },
                        shape = SegmentedButtonDefaults.itemShape(index, TARGETS.size),
                        icon = {
                            if (target == index) Icon(Icons.Filled.Done, contentDescription = null)
                            else Icon(Icons.Filled.Done, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
                        },
                        label = { Text(label) },
                    )
                }
            }

            var expanded by remember { mutableStateOf(false) }
            var favorite by remember { mutableStateOf(TARGETS[0]) }
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it },
            ) {
                OutlinedTextField(
                    value = favorite,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Favorite target") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    TARGETS.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = { favorite = option; expanded = false },
                        )
                    }
                }
            }
        }
    }
}
