package com.interpretertrainer.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.SettingsInputComponent
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.interpretertrainer.app.media.AudioRouteKind
import com.interpretertrainer.app.media.rememberAudioOutputRoute
import com.interpretertrainer.app.model.LanguageOption

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrainerScaffold(title: String, onBack: () -> Unit, content: @Composable (PaddingValues) -> Unit) {
    val audioRoute = rememberAudioOutputRoute()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (audioRoute.isExternal) {
                        val icon = when (audioRoute.kind) {
                            AudioRouteKind.BLUETOOTH -> Icons.Default.Bluetooth
                            AudioRouteKind.WIRED -> Icons.Default.Headphones
                            AudioRouteKind.EXTERNAL -> Icons.Default.SettingsInputComponent
                            AudioRouteKind.SPEAKER -> Icons.Default.Headphones
                        }
                        IconButton(onClick = {}) {
                            Icon(
                                imageVector = icon,
                                contentDescription = buildString {
                                    append(audioRoute.label)
                                    audioRoute.deviceName?.let { append(": $it") }
                                },
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            )
        },
        content = content
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguageSelector(label: String, selected: LanguageOption, onSelected: (LanguageOption) -> Unit) {
    var expanded = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded.value, onExpandedChange = { expanded.value = it }) {
        OutlinedTextField(
            value = selected.label,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded.value) },
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded.value, onDismissRequest = { expanded.value = false }) {
            LanguageOption.entries.forEach { option ->
                DropdownMenuItem(text = { Text("${option.label} (${option.tag})") }, onClick = {
                    onSelected(option); expanded.value = false
                })
            }
        }
    }
}

@Composable
fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content
        )
    }
}
