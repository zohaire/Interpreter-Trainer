package com.interpretertrainer.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.interpretertrainer.app.data.database.PracticeLibraryEntity
import com.interpretertrainer.app.media.LibraryPracticeBridge
import com.interpretertrainer.app.media.MediaLinkResolver
import com.interpretertrainer.app.ui.Routes
import com.interpretertrainer.app.ui.theme.InterpreterBlue
import com.interpretertrainer.app.ui.theme.InterpreterNavy
import com.interpretertrainer.app.viewmodel.PracticeLibraryViewModel

private enum class LibraryTab { CURATED, PERSONAL }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PracticeLibraryScreen(
    onBack: () -> Unit,
    onNavigate: (String) -> Unit,
    viewModel: PracticeLibraryViewModel
) {
    val context = LocalContext.current
    val allItems by viewModel.items.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(LibraryTab.CURATED) }
    var query by rememberSaveable { mutableStateOf("") }
    var language by rememberSaveable { mutableStateOf("All") }
    var difficulty by rememberSaveable { mutableStateOf("All") }
    var pace by rememberSaveable { mutableStateOf("All") }
    var favoritesOnly by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf<PracticeLibraryEntity?>(null) }
    var showAddMenu by remember { mutableStateOf(false) }
    var showLinkDialog by remember { mutableStateOf(false) }
    var pendingLink by rememberSaveable { mutableStateOf("") }
    var pendingTitle by rememberSaveable { mutableStateOf("") }
    var importError by rememberSaveable { mutableStateOf<String?>(null) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        viewModel.addFile(uri.lastPathSegment?.substringAfterLast('/') ?: "Imported media", uri.toString())
        tab = LibraryTab.PERSONAL
    }

    val visibleItems = remember(allItems, tab, query, language, difficulty, pace, favoritesOnly) {
        allItems.filter { item ->
            item.isOfficial == (tab == LibraryTab.CURATED) &&
                (query.isBlank() || listOf(item.title, item.speaker, item.topic, item.institutionEvent)
                    .any { it.contains(query, ignoreCase = true) }) &&
                (language == "All" || item.language == language) &&
                (difficulty == "All" || item.difficulty == difficulty) &&
                (pace == "All" || item.speakingSpeed == pace) &&
                (!favoritesOnly || item.isFavorite)
        }.sortedWith(
            compareByDescending<PracticeLibraryEntity> { it.lastPracticedAt != null }
                .thenByDescending { it.lastPracticedAt ?: 0L }
                .thenBy { it.title }
        )
    }
    val recent = remember(allItems) {
        allItems.filter { it.lastPracticedAt != null }.sortedByDescending { it.lastPracticedAt }.take(5)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Practice Library", fontWeight = FontWeight.SemiBold)
                        Text("Authentic speeches for serious training", style = MaterialTheme.typography.labelSmall)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.Close, contentDescription = "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = InterpreterNavy,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        },
        floatingActionButton = {
            Box {
                FloatingActionButton(onClick = { showAddMenu = true }, containerColor = InterpreterBlue) {
                    Icon(Icons.Default.Add, contentDescription = "Add practice material", tint = Color.White)
                }
                DropdownMenu(expanded = showAddMenu, onDismissRequest = { showAddMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Import audio or video") },
                        leadingIcon = { Icon(Icons.Default.AudioFile, contentDescription = null) },
                        onClick = {
                            showAddMenu = false
                            filePicker.launch(arrayOf("audio/*", "video/*"))
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Add a media link") },
                        leadingIcon = { Icon(Icons.Default.Link, contentDescription = null) },
                        onClick = { showAddMenu = false; showLinkDialog = true }
                    )
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab.ordinal) {
                Tab(
                    selected = tab == LibraryTab.CURATED,
                    onClick = { tab = LibraryTab.CURATED },
                    text = { Text("Official collection") },
                    icon = { Icon(Icons.Default.Public, contentDescription = null) }
                )
                Tab(
                    selected = tab == LibraryTab.PERSONAL,
                    onClick = { tab = LibraryTab.PERSONAL },
                    text = { Text("My library") },
                    icon = { Icon(Icons.Default.AudioFile, contentDescription = null) }
                )
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = { Icon(Icons.Default.FilterList, contentDescription = null) },
                        placeholder = { Text("Search speakers, topics or events") },
                        shape = RoundedCornerShape(18.dp)
                    )
                }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            FilterChip(
                                selected = favoritesOnly,
                                onClick = { favoritesOnly = !favoritesOnly },
                                label = { Text("Favorites") },
                                leadingIcon = { Icon(Icons.Default.Favorite, contentDescription = null, modifier = Modifier.size(17.dp)) }
                            )
                        }
                        items(listOf("All", "English", "French", "Arabic", "Spanish")) { option ->
                            FilterChip(selected = language == option, onClick = { language = option }, label = { Text(option) })
                        }
                        items(listOf("All", "Intermediate", "Advanced", "Custom")) { option ->
                            FilterChip(
                                selected = difficulty == option,
                                onClick = { difficulty = option },
                                label = { Text(if (option == "All") "Any level" else option) }
                            )
                        }
                        items(listOf("All", "Measured", "Fast", "Original")) { option ->
                            FilterChip(
                                selected = pace == option,
                                onClick = { pace = option },
                                label = { Text(if (option == "All") "Any pace" else "$option pace") }
                            )
                        }
                    }
                }
                if (tab == LibraryTab.CURATED && query.isBlank() && recent.isNotEmpty()) {
                    item {
                        SectionHeading(Icons.Default.History, "Recently practiced", "Continue where you left off")
                        Spacer(Modifier.height(8.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(recent, key = { "recent-${it.id}" }) { item ->
                                RecentPracticeCard(item) { selected = item }
                            }
                        }
                    }
                }
                item {
                    SectionHeading(
                        if (tab == LibraryTab.CURATED) Icons.Default.CheckCircle else Icons.Default.AudioFile,
                        if (tab == LibraryTab.CURATED) "Verified UN sources" else "Your practice material",
                        if (tab == LibraryTab.CURATED) "${visibleItems.size} authentic four-minute practice windows" else "Files and links stored for quick access"
                    )
                }
                if (visibleItems.isEmpty()) {
                    item { EmptyLibrary(tab, hasFilters = query.isNotBlank() || favoritesOnly || language != "All" || difficulty != "All" || pace != "All") }
                } else {
                    items(visibleItems, key = { it.id }) { item ->
                        PracticeResourceCard(
                            item = item,
                            onOpen = { selected = item },
                            onFavorite = { viewModel.toggleFavorite(item) },
                            onDelete = if (item.isOfficial) null else ({ viewModel.deletePersonal(item) })
                        )
                    }
                }
            }
        }
    }

    selected?.let { item ->
        ResourceDetailsSheet(
            item = item,
            onDismiss = { selected = null },
            onFavorite = { viewModel.toggleFavorite(item); selected = item.copy(isFavorite = !item.isFavorite) },
            onMarkComplete = {
                viewModel.markCompleted(item)
                selected = item.copy(progressMillis = item.durationMillis, completionCount = item.completionCount + 1)
            },
            onPractice = { mode, route ->
                viewModel.markOpened(item)
                LibraryPracticeBridge.send(item, mode)
                selected = null
                onNavigate(route)
            }
        )
    }

    if (showLinkDialog) {
        AlertDialog(
            onDismissRequest = { showLinkDialog = false },
            title = { Text("Add a media link") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = pendingTitle,
                        onValueChange = { pendingTitle = it },
                        label = { Text("Title") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = pendingLink,
                        onValueChange = { pendingLink = it; importError = null },
                        label = { Text("Secure audio or video link") },
                        placeholder = { Text("https://…") },
                        singleLine = true
                    )
                    importError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                Button(onClick = {
                    MediaLinkResolver.resolve(pendingLink)
                        .onSuccess {
                            viewModel.addLink(pendingTitle.ifBlank { it.displayName }, it.normalizedUrl)
                            pendingTitle = ""
                            pendingLink = ""
                            showLinkDialog = false
                            tab = LibraryTab.PERSONAL
                        }
                        .onFailure { importError = it.message ?: "This link could not be added." }
                }) { Text("Save") }
            },
            dismissButton = { OutlinedButton(onClick = { showLinkDialog = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun SectionHeading(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(40.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null, tint = InterpreterBlue) }
        }
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PracticeResourceCard(
    item: PracticeLibraryEntity,
    onOpen: () -> Unit,
    onFavorite: () -> Unit,
    onDelete: (() -> Unit)?
) {
    var menu by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = if (item.isOfficial) Color(0xFFE2EDFF) else MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.size(54.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(if (item.isOfficial) Icons.Default.Public else Icons.Default.AudioFile, null, tint = InterpreterBlue)
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        if (item.isOfficial) {
                            Surface(shape = RoundedCornerShape(7.dp), color = Color(0xFF0B4DA2)) {
                                Text("OFFICIAL", color = Color.White, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp))
                            }
                        }
                    }
                    Text(item.speaker, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text(item.institutionEvent, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Resource actions") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(if (item.isFavorite) "Remove favorite" else "Add to favorites") },
                            leadingIcon = { Icon(if (item.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null) },
                            onClick = { menu = false; onFavorite() }
                        )
                        onDelete?.let { delete ->
                            DropdownMenuItem(
                                text = { Text("Remove from library") },
                                leadingIcon = { Icon(Icons.Default.DeleteOutline, null) },
                                onClick = { menu = false; delete() }
                            )
                        }
                    }
                }
            }
            Text(item.topic, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                MetadataPill(item.language)
                MetadataPill(item.difficulty)
                MetadataPill(item.speakingSpeed)
                MetadataPill("4 min")
            }
            if (item.progressMillis > 0) {
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { (item.progressMillis.toFloat() / item.durationMillis).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun MetadataPill(label: String) {
    Surface(shape = RoundedCornerShape(9.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .7f)) {
        Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp), maxLines = 1)
    }
}

@Composable
private fun RecentPracticeCard(item: PracticeLibraryEntity, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.width(230.dp), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = InterpreterBlue)
            Text(item.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.topic, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ResourceDetailsSheet(
    item: PracticeLibraryEntity,
    onDismiss: () -> Unit,
    onFavorite: () -> Unit,
    onMarkComplete: () -> Unit,
    onPractice: (String, String) -> Unit
) {
    val uriHandler = LocalUriHandler.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(item.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(item.speaker, style = MaterialTheme.typography.titleMedium, color = InterpreterBlue)
                }
                IconButton(onClick = onFavorite) {
                    Icon(if (item.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, contentDescription = "Favorite", tint = InterpreterBlue)
                }
            }
            HorizontalDivider()
            DetailRow("Institution / event", item.institutionEvent)
            DetailRow("Date", item.eventDate)
            DetailRow("Language", item.language)
            DetailRow("Topic", item.topic)
            DetailRow("Training window", "4 minutes · ${item.difficulty} · ${item.speakingSpeed} pace")
            DetailRow("Source", item.sourceLabel)
            if (item.isOfficial) {
                Surface(shape = RoundedCornerShape(14.dp), color = Color(0xFFE7F0FF)) {
                    Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF0B4DA2))
                        Text("Authentic UN source. The recording stays hosted by the United Nations; this app does not generate or repackage it.", color = Color(0xFF0B326A), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Text("Open in a training mode", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Button(
                onClick = { onPractice(LibraryPracticeBridgeMode.SIMULTANEOUS, Routes.SIMULTANEOUS) },
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                Icon(Icons.Default.Headphones, null)
                Spacer(Modifier.width(8.dp))
                Text("Simultaneous interpretation")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                FilledTonalButton(
                    onClick = { onPractice(LibraryPracticeBridgeMode.CONSECUTIVE, Routes.CONSECUTIVE) },
                    modifier = Modifier.weight(1f)
                ) { Text("Consecutive") }
                FilledTonalButton(
                    onClick = { onPractice(LibraryPracticeBridgeMode.SHADOWING, Routes.SHADOWING) },
                    modifier = Modifier.weight(1f)
                ) { Text("Shadowing") }
            }
            OutlinedButton(onClick = onMarkComplete, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.CheckCircle, null)
                Spacer(Modifier.width(8.dp))
                Text(if (item.progressMillis >= item.durationMillis) "Practiced ${item.completionCount} time(s)" else "Mark practice window complete")
            }
            item.sourceUrl?.let { source ->
                OutlinedButton(onClick = { uriHandler.openUri(source) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.OpenInNew, null)
                    Spacer(Modifier.width(8.dp))
                    Text("View original source")
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

private object LibraryPracticeBridgeMode {
    const val SIMULTANEOUS = "SIMULTANEOUS"
    const val CONSECUTIVE = "CONSECUTIVE"
    const val SHADOWING = "SHADOWING"
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, modifier = Modifier.width(120.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun EmptyLibrary(tab: LibraryTab, hasFilters: Boolean) {
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .45f)) {
        Column(
            Modifier.fillMaxWidth().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(if (hasFilters) Icons.Default.Search else Icons.Default.AudioFile, null, modifier = Modifier.size(38.dp), tint = InterpreterBlue)
            Text(if (hasFilters) "No matching resources" else if (tab == LibraryTab.PERSONAL) "Your library is ready" else "The catalog is loading", fontWeight = FontWeight.Bold)
            Text(
                if (hasFilters) "Try clearing a filter or using a broader search."
                else "Use + to import a recording or save an audio/video link.",
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
