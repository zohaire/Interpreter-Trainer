package com.interpretertrainer.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.SettingsInputComponent
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.interpretertrainer.app.R
import com.interpretertrainer.app.media.AudioRouteKind
import com.interpretertrainer.app.media.rememberAudioInputRoute
import com.interpretertrainer.app.model.LanguageOption

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrainerScaffold(title: String, onBack: () -> Unit, content: @Composable (PaddingValues) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        if (title.contains("Coach", ignoreCase = true) || title.contains("Interpreter AI", ignoreCase = true)) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_interpreter_ai_face),
                                    contentDescription = "Interpreter AI",
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.padding(5.dp)
                                )
                            }
                        }
                        Text(title, fontWeight = FontWeight.SemiBold)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected.label,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            LanguageOption.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text("${option.label} (${option.tag})") },
                    onClick = {
                        onSelected(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
fun PracticeTopStrip(
    sourceLanguage: LanguageOption,
    targetLanguage: LanguageOption,
    onSourceLanguageChange: (LanguageOption) -> Unit,
    onTargetLanguageChange: (LanguageOption) -> Unit,
    sourceLabel: String = "Source",
    targetLabel: String = "Target"
) {
    val microphoneRoute = rememberAudioInputRoute()

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CompactLanguageCard(
            label = sourceLabel,
            language = sourceLanguage,
            onSelected = onSourceLanguageChange,
            modifier = Modifier.weight(1f)
        )
        CompactLanguageCard(
            label = targetLabel,
            language = targetLanguage,
            onSelected = onTargetLanguageChange,
            modifier = Modifier.weight(1f)
        )
        Surface(
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(18.dp),
            color = if (microphoneRoute.isExternal) {
                MaterialTheme.colorScheme.tertiaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
            }
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    val icon = when (microphoneRoute.kind) {
                        AudioRouteKind.BLUETOOTH -> Icons.Default.Bluetooth
                        AudioRouteKind.WIRED -> Icons.Default.Headphones
                        AudioRouteKind.EXTERNAL -> Icons.Default.SettingsInputComponent
                        AudioRouteKind.SPEAKER -> Icons.Default.Mic
                    }
                    Icon(icon, contentDescription = null, modifier = Modifier.size(15.dp))
                    Text("Mic", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    microphoneRoute.compactLabel,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                microphoneRoute.deviceName?.let { name ->
                    Text(
                        name,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactLanguageCard(
    label: String,
    language: LanguageOption,
    onSelected: (LanguageOption) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        Surface(
            modifier = Modifier.fillMaxWidth().clickable { expanded = true },
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        languageShort(language),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                    Text(
                        language.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Change $label language", modifier = Modifier.size(17.dp))
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            LanguageOption.entries.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(option.label, fontWeight = FontWeight.Medium)
                            Text(option.tag, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    onClick = {
                        onSelected(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
fun CompactMediaLinkField(
    value: String,
    onValueChange: (String) -> Unit,
    onLoad: () -> Unit,
    enabled: Boolean = true,
    placeholder: String = "Paste a media or webpage link"
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            enabled = enabled,
            placeholder = { Text(placeholder, maxLines = 1) },
            leadingIcon = { Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(18.dp)) },
            shape = RoundedCornerShape(18.dp)
        )
        FilledTonalIconButton(
            onClick = onLoad,
            enabled = enabled && value.isNotBlank(),
            modifier = Modifier.size(48.dp)
        ) {
            Icon(Icons.Default.ArrowForward, contentDescription = "Load link")
        }
    }
}

@Composable
fun ModernActionButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(7.dp))
        Text(text)
    }
}

@Composable
fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content
        )
    }
}

private fun languageShort(language: LanguageOption): String = when (language) {
    LanguageOption.ENGLISH_US -> "EN"
    LanguageOption.FRENCH_FRANCE -> "FR"
    LanguageOption.ARABIC_MOROCCO -> "AR"
}
