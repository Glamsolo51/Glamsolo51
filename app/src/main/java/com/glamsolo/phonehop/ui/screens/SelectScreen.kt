package com.glamsolo.phonehop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.glamsolo.phonehop.R
import com.glamsolo.phonehop.data.AppInfo
import com.glamsolo.phonehop.data.PickedFile
import com.glamsolo.phonehop.ui.Format
import com.glamsolo.phonehop.ui.components.AppIcon
import com.glamsolo.phonehop.ui.components.GlassCard
import com.glamsolo.phonehop.ui.components.GradientButton
import com.glamsolo.phonehop.ui.components.IconBubble
import com.glamsolo.phonehop.ui.components.ScreenHeader
import com.glamsolo.phonehop.ui.components.SegmentedTabs
import com.glamsolo.phonehop.ui.components.SelectionDot
import com.glamsolo.phonehop.ui.formatBytes
import com.glamsolo.phonehop.ui.theme.Brand
import com.glamsolo.phonehop.ui.theme.LocalGlass

data class SelectionSummary(val apps: Int, val files: Int, val bytes: Long) {
    val isEmpty: Boolean get() = apps == 0 && files == 0
}

data class SelectUiState(
    val tab: Int = 0,
    val apps: List<AppInfo>? = null,
    val query: String = "",
    val selectedApps: Set<String> = emptySet(),
    val files: List<PickedFile> = emptyList(),
    val readingFolder: Boolean = false,
    val summary: SelectionSummary = SelectionSummary(0, 0, 0),
)

interface SelectActions {
    fun onTab(tab: Int)
    fun onQuery(query: String)
    fun onToggleApp(packageName: String)
    fun onSelectAll(select: Boolean)
    fun onPickMedia()
    fun onPickFiles()
    fun onPickFolder()
    fun onRemoveFile(file: PickedFile)
    fun onNext()
    fun onBack()
}

@Composable
fun SelectScreen(state: SelectUiState, actions: SelectActions) {
    Box(Modifier.fillMaxSize().systemBarsPadding()) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(stringResource(R.string.select_title), onBack = actions::onBack)
            SegmentedTabs(
                options = listOf(
                    stringResource(R.string.tab_with_count, stringResource(R.string.tab_apps), state.summary.apps),
                    stringResource(R.string.tab_with_count, stringResource(R.string.tab_files), state.summary.files),
                ),
                selected = state.tab,
                onSelect = actions::onTab,
                modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            if (state.tab == 0) AppsTab(state, actions) else FilesTab(state, actions)
        }
        SelectionBar(
            summary = state.summary,
            onNext = actions::onNext,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun AppsTab(state: SelectUiState, actions: SelectActions) {
    val apps = state.apps
    Column(Modifier.fillMaxSize()) {
        SearchField(state.query, actions::onQuery, Modifier.padding(horizontal = 20.dp))
        when {
            apps == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(strokeCap = androidx.compose.ui.graphics.StrokeCap.Round)
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.apps_loading), style = MaterialTheme.typography.bodyMedium)
                }
            }
            apps.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.apps_empty), style = MaterialTheme.typography.bodyLarge)
            }
            else -> {
                val allSelected = apps.all { it.packageName in state.selectedApps }
                LazyColumn(
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize().testTag("apps_list"),
                ) {
                    item(key = "header") {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                stringResource(R.string.apps_note),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(12.dp))
                            TextButton(onClick = { actions.onSelectAll(!allSelected) }) {
                                Text(stringResource(if (allSelected) R.string.clear_selection else R.string.select_all))
                            }
                        }
                    }
                    items(apps, key = { it.packageName }) { app ->
                        AppRow(app, app.packageName in state.selectedApps) { actions.onToggleApp(app.packageName) }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppRow(app: AppInfo, selected: Boolean, onToggle: () -> Unit) {
    val context = LocalContext.current
    val glass = LocalGlass.current
    val shape = RoundedCornerShape(22.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(glass.fill)
            .border(1.dp, if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f) else glass.border, shape)
            .clickable(role = Role.Checkbox, onClick = onToggle)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(app.packageName)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(app.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val version = app.versionName?.let { "v$it" }
            Text(
                listOfNotNull(version, Format.bytes(context, app.sizeBytes)).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        SelectionDot(selected)
    }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit, modifier: Modifier = Modifier) {
    val glass = LocalGlass.current
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        singleLine = true,
        shape = CircleShape,
        placeholder = { Text(stringResource(R.string.search_apps)) },
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
        trailingIcon = if (query.isNotEmpty()) {
            { IconButton(onClick = { onQuery("") }) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.cancel)) } }
        } else {
            null
        },
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedContainerColor = glass.fill,
            focusedContainerColor = glass.fill,
            unfocusedBorderColor = glass.border,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun FilesTab(state: SelectUiState, actions: SelectActions) {
    LazyColumn(
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "pickers") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                PickerTile(Icons.Rounded.PhotoLibrary, stringResource(R.string.pick_media), listOf(Brand.Pink, Brand.Violet), actions::onPickMedia, Modifier.weight(1f))
                PickerTile(Icons.Rounded.Description, stringResource(R.string.pick_files), Brand.Aurora, actions::onPickFiles, Modifier.weight(1f))
                PickerTile(Icons.Rounded.Folder, stringResource(R.string.pick_folder), Brand.Receive, actions::onPickFolder, Modifier.weight(1f))
            }
        }
        if (state.readingFolder) {
            item(key = "reading") {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.reading_folder), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (state.files.isEmpty() && !state.readingFolder) {
            item(key = "empty") {
                GlassCard(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Rounded.CloudUpload,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(44.dp),
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(stringResource(R.string.files_empty_title), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.files_empty_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        items(state.files, key = { it.uri.toString() }) { file ->
            FileRow(file) { actions.onRemoveFile(file) }
        }
    }
}

@Composable
private fun PickerTile(icon: ImageVector, label: String, gradient: List<androidx.compose.ui.graphics.Color>, onClick: () -> Unit, modifier: Modifier) {
    GlassCard(modifier.height(118.dp), onClick = onClick, contentPadding = PaddingValues(14.dp)) {
        IconBubble(icon, gradient, size = 42.dp)
        Spacer(Modifier.weight(1f))
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 2)
    }
}

@Composable
private fun FileRow(file: PickedFile, onRemove: () -> Unit) {
    val glass = LocalGlass.current
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(glass.fill)
            .border(1.dp, glass.border, shape)
            .padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(mimeIcon(file.mimeType), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(file.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            val folder = file.relativePath.substringBeforeLast('/', "")
            val size = if (file.size >= 0) formatBytes(file.size) else stringResource(R.string.size_unknown)
            Text(
                listOf(folder, size).filter { it.isNotEmpty() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.cd_remove))
        }
    }
}

fun mimeIcon(mime: String?): ImageVector = when {
    mime == null -> Icons.AutoMirrored.Rounded.InsertDriveFile
    mime.startsWith("image/") -> Icons.Rounded.Image
    mime.startsWith("video/") -> Icons.Rounded.Movie
    mime.startsWith("audio/") -> Icons.Rounded.MusicNote
    mime == "application/vnd.android.package-archive" -> Icons.Rounded.Android
    mime == "application/pdf" || mime.startsWith("text/") -> Icons.Rounded.Description
    mime.contains("zip") || mime.contains("compressed") -> Icons.Rounded.FolderZip
    else -> Icons.AutoMirrored.Rounded.InsertDriveFile
}

@Composable
private fun SelectionBar(summary: SelectionSummary, onNext: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(32.dp)
    Row(
        modifier
            .padding(horizontal = 14.dp, vertical = 14.dp)
            .fillMaxWidth()
            .shadow(24.dp, shape, ambientColor = Brand.Violet, spotColor = Brand.Violet)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(start = 22.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            if (summary.isEmpty) {
                Text(stringResource(R.string.selection_empty), style = MaterialTheme.typography.titleSmall)
            } else {
                Text(
                    Format.counts(context, summary.apps, summary.files),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    Format.bytes(context, summary.bytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        GradientButton(
            text = stringResource(R.string.next),
            onClick = onNext,
            enabled = !summary.isEmpty,
            icon = Icons.AutoMirrored.Rounded.ArrowForward,
        )
    }
}
