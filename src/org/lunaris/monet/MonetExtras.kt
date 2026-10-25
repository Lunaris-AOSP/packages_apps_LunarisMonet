/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package org.lunaris.monet

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.systemui.monet.MonetSettings
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
fun MonetPreviewActions(enabled: Boolean, onApply: () -> Unit, onDiscard: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 3.dp,
        modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.preview_pending),
                Modifier.padding(horizontal = 12.dp),
                style = MaterialTheme.typography.labelLarge,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDiscard, enabled = enabled, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.discard))
                }
                Button(
                    onClick = onApply,
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Icon(Icons.Default.Check, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.apply))
                }
            }
        }
    }
}

@Composable
fun MonetExtras(
    snapshot: MonetSnapshot,
    enabled: Boolean,
    hasChanges: Boolean,
    viewModel: MonetViewModel,
    listState: LazyListState,
    onColor: () -> Unit,
    onSave: () -> Unit,
) {
    val settings = snapshot.settings
    val night = isSystemInDarkTheme()
    var editDark by rememberSaveable { mutableStateOf(night) }
    var inspector by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var actionId by rememberSaveable { mutableStateOf<String?>(null) }
    var replace by rememberSaveable { mutableStateOf(false) }
    var exportId by rememberSaveable { mutableStateOf<String?>(null) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val export =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/json")
        ) { uri ->
            if (uri != null) viewModel.export(uri, exportId)
        }
    val importDocument =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) viewModel.readBackup(uri)
        }
    fun exportLook(id: String?) {
        exportId = id
        try {
            export.launch(if (id == null) "Lunaris-Monet.json" else "Lunaris-Look.json")
        } catch (_: ActivityNotFoundException) {
            viewModel.filePickerUnavailable()
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        item("intro") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.extras_title),
                    style = MaterialTheme.typography.headlineLarge,
                )
                Text(
                    stringResource(R.string.extras_summary),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item("quick_colors") {
            MonetGroup(
                stringResource(R.string.quick_colors),
                stringResource(R.string.quick_colors_summary),
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(
                            0xff6750a4,
                            0xff1976d2,
                            0xff008577,
                            0xff388e3c,
                            0xffef6c00,
                            0xffc62828,
                            0xffad1457,
                            0xff546e7a,
                        )
                        .forEach { value ->
                            val color = value.toInt()
                            FilledTonalIconToggleButton(
                                checked = settings.seedColor == color,
                                onCheckedChange = { viewModel.seed(color, secondary = false) },
                                enabled = enabled,
                                modifier = Modifier.size(52.dp),
                                shapes = IconButtonDefaults.toggleableShapes(),
                            ) {
                                Surface(
                                    color = Color(color),
                                    shape = CircleShape,
                                    modifier = Modifier.size(32.dp),
                                ) {
                                    if (settings.seedColor == color)
                                        Icon(
                                            Icons.Default.Check,
                                            stringResource(R.string.sampled_color, colorHex(color)),
                                            tint =
                                                if (android.graphics.Color.luminance(color) > 0.4f)
                                                    Color.Black
                                                else Color.White,
                                        )
                                    else
                                        Icon(
                                            Icons.Default.Circle,
                                            stringResource(R.string.sampled_color, colorHex(color)),
                                            tint = Color(color),
                                        )
                                }
                            }
                        }
                }
                FilledTonalButton(
                    onClick = viewModel::randomColor,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Icon(Icons.Default.Shuffle, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.surprise_color))
                }
            }
        }
        item("tertiary") {
            MonetGroup(stringResource(R.string.tertiary_seed)) {
                MonetSwitch(
                    stringResource(R.string.tertiary_enabled),
                    stringResource(R.string.tertiary_summary),
                    settings.tertiarySeed != null,
                    enabled,
                    viewModel::tertiary,
                )
                AnimatedVisibility(settings.tertiarySeed != null) {
                    ColorRow(
                        stringResource(R.string.tertiary_seed),
                        settings.tertiarySeed ?: 0xff6750a4.toInt(),
                        enabled,
                        onColor,
                    )
                }
            }
        }
        item("saturation") {
            MonetGroup(
                stringResource(R.string.saturation),
                stringResource(R.string.saturation_summary),
            ) {
                MonetSwitch(
                    stringResource(R.string.mode_saturation),
                    stringResource(R.string.mode_saturation_summary),
                    settings.modeSaturation,
                    enabled,
                ) {
                    viewModel.setValue(MonetSettings.MODE_SATURATION, it)
                }
                AnimatedVisibility(settings.modeSaturation) {
                    MonetModeToggle(editDark, { editDark = it }, enabled)
                }
                val keys =
                    if (settings.modeSaturation) {
                        if (editDark) MonetSettings.DARK_SATURATION
                        else MonetSettings.LIGHT_SATURATION
                    } else
                        arrayOf(
                            MonetSettings.ACCENT_SATURATION,
                            MonetSettings.BACKGROUND_SATURATION,
                        )
                MonetSlider(
                    stringResource(R.string.accent_saturation),
                    settings.saturation(false, editDark).toInt(),
                    0..200,
                    enabled,
                    defaultValue = 100,
                    onPreview = { viewModel.previewValue(keys[0], it) },
                ) {
                    viewModel.setValue(keys[0], it)
                }
                MonetSlider(
                    stringResource(R.string.background_saturation),
                    settings.saturation(true, editDark).toInt(),
                    0..200,
                    enabled,
                    defaultValue = 100,
                    onPreview = { viewModel.previewValue(keys[1], it) },
                ) {
                    viewModel.setValue(keys[1], it)
                }
            }
        }
        item("neutral_text") {
            MonetGroup(stringResource(R.string.text_colors)) {
                MonetSwitch(
                    stringResource(R.string.neutral_text),
                    stringResource(R.string.neutral_text_summary),
                    settings.neutralText,
                    enabled,
                ) {
                    viewModel.setValue(MonetSettings.NEUTRAL_TEXT, it)
                }
            }
        }
        item("preview") { MonetPreview(snapshot.light, snapshot.dark, night) }
        item("inspector") {
            MonetGroup(
                stringResource(R.string.palette_inspector),
                stringResource(R.string.palette_inspector_summary),
            ) {
                FilledTonalButton(
                    onClick = { inspector = true },
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Icon(Icons.Default.Palette, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.browse_palette))
                }
            }
        }
        item("saved_header") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.saved_presets),
                    style = MaterialTheme.typography.titleLargeEmphasized,
                )
                Text(
                    stringResource(R.string.saved_extras_summary),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (snapshot.saved.isEmpty()) Text(stringResource(R.string.presets_empty))
            }
        }
        items(snapshot.saved, key = { "look_${it.id}" }) { look ->
            SavedLookCard(
                look,
                snapshot,
                enabled,
                onApply = { viewModel.restore(look) },
                onEdit = { editingId = look.id },
                onReplace = {
                    actionId = look.id
                    replace = true
                },
                onDelete = {
                    actionId = look.id
                    replace = false
                },
                onExport = { exportLook(look.id) },
                onMove = { viewModel.move(look.id, it) },
            )
        }
        item("save") {
            OutlinedButton(
                onClick = onSave,
                enabled = enabled && snapshot.saved.size < 20,
                modifier = Modifier.fillMaxWidth(),
                shapes = ButtonDefaults.shapes(),
            ) {
                Icon(Icons.Default.BookmarkAdd, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.save_preset))
            }
        }
        item("backup") {
            MonetGroup(
                stringResource(R.string.backup_title),
                stringResource(R.string.backup_summary),
            ) {
                if (hasChanges)
                    Text(
                        stringResource(R.string.backup_preview_hint),
                        style = MaterialTheme.typography.bodySmall,
                    )
                FilledTonalButton(
                    onClick = { exportLook(null) },
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(stringResource(R.string.export_backup))
                }
                OutlinedButton(
                    onClick = {
                        try {
                            importDocument.launch(
                                arrayOf(
                                    "application/json",
                                    "text/plain",
                                    "application/octet-stream",
                                )
                            )
                        } catch (_: ActivityNotFoundException) {
                            viewModel.filePickerUnavailable()
                        }
                    },
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(stringResource(R.string.import_backup))
                }
            }
        }
    }
    if (inspector) MonetPaletteInspector(snapshot) { inspector = false }
    snapshot.saved
        .find { it.id == editingId }
        ?.let { look ->
            EditLookDialog(look, onDismiss = { editingId = null }) { name, description ->
                editingId = null
                viewModel.rename(look.id, name, description)
            }
        }
    snapshot.saved
        .find { it.id == actionId }
        ?.let { look ->
            AlertDialog(
                onDismissRequest = { actionId = null },
                title = {
                    Text(
                        stringResource(
                            if (replace) R.string.replace_look else R.string.delete_preset,
                            look.name,
                        )
                    )
                },
                text = {
                    Text(
                        stringResource(
                            if (replace) R.string.replace_look_summary
                            else R.string.delete_look_summary
                        )
                    )
                },
                confirmButton = {
                    TextButton(
                        enabled = enabled,
                        onClick = {
                            actionId = null
                            if (replace) viewModel.replace(look.id) else viewModel.delete(look.id)
                        },
                    ) {
                        Text(stringResource(if (replace) R.string.update else R.string.delete))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { actionId = null }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }
    state.pendingImport?.let { backup ->
        AlertDialog(
            onDismissRequest = viewModel::dismissImport,
            title = { Text(stringResource(R.string.import_backup)) },
            text = { Text(stringResource(R.string.import_summary, backup.looks.size)) },
            confirmButton = {
                TextButton(onClick = viewModel::importBackup, enabled = enabled) {
                    Text(stringResource(R.string.import_action))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissImport) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun MonetModeToggle(dark: Boolean, onSelect: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MonetFilterChip(
            !dark,
            { onSelect(false) },
            { Text(stringResource(R.string.light)) },
            enabled = enabled,
        )
        MonetFilterChip(
            dark,
            { onSelect(true) },
            { Text(stringResource(R.string.dark)) },
            enabled = enabled,
        )
    }
}

@Composable
private fun SavedLookCard(
    look: SavedLook,
    snapshot: MonetSnapshot,
    enabled: Boolean,
    onApply: () -> Unit,
    onEdit: () -> Unit,
    onReplace: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
    onMove: (Int) -> Unit,
) {
    val night = isSystemInDarkTheme()
    val preview by
        produceState<PalettePreview?>(
            null,
            look.settings,
            snapshot.homeColors,
            snapshot.lockColors,
            night,
            snapshot.systemContrast,
        ) {
            value =
                withContext(Dispatchers.Default) {
                    runCatching { MonetRepository.savedPreview(look, snapshot, night) }.getOrNull()
                }
        }
    val selected =
        remember(look.settings, snapshot.json) {
            MonetConfig.difference(JSONObject(snapshot.json), JSONObject(look.settings)).isEmpty()
        }
    var menu by remember { mutableStateOf(false) }
    Surface(
        onClick = onApply,
        enabled = enabled,
        shape = RoundedCornerShape(28.dp),
        color =
            if (selected) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            preview?.let { PaletteDisc(it, Modifier.size(56.dp), selected) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    look.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (look.description.isNotBlank())
                    Text(
                        look.description,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
            }
            Box {
                IconButton(onClick = { menu = true }, enabled = enabled) {
                    Icon(Icons.Default.MoreVert, stringResource(R.string.look_options, look.name))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.edit_details)) },
                        onClick = {
                            menu = false
                            onEdit()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.update_from_preview)) },
                        onClick = {
                            menu = false
                            onReplace()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.export_look)) },
                        onClick = {
                            menu = false
                            onExport()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.move_up)) },
                        enabled = snapshot.saved.firstOrNull()?.id != look.id,
                        onClick = {
                            menu = false
                            onMove(-1)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.move_down)) },
                        enabled = snapshot.saved.lastOrNull()?.id != look.id,
                        onClick = {
                            menu = false
                            onMove(1)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete)) },
                        onClick = {
                            menu = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun EditLookDialog(
    look: SavedLook,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var name by rememberSaveable(look.id) { mutableStateOf(look.name) }
    var description by rememberSaveable(look.id) { mutableStateOf(look.description) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_details)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    name,
                    { name = it.take(40) },
                    label = { Text(stringResource(R.string.preset_name)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    description,
                    { description = it.take(160) },
                    label = { Text(stringResource(R.string.description)) },
                    maxLines = 4,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name, description) }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun MonetPaletteInspector(snapshot: MonetSnapshot, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val night = isSystemInDarkTheme()
    var dark by rememberSaveable { mutableStateOf(night) }
    var shades by rememberSaveable { mutableStateOf(true) }
    var search by rememberSaveable { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    val entries by
        produceState<List<PaletteEntry>?>(
            null,
            snapshot.json,
            snapshot.seed,
            snapshot.systemContrast,
        ) {
            failed = false
            val result =
                withContext(Dispatchers.Default) {
                    runCatching { MonetRepository.inspect(snapshot).distinctBy { it.name } }
                }
            value = result.getOrNull()
            failed = result.isFailure
        }
    val filtered =
        entries.orEmpty().filter {
            it.shades == shades && it.name.replace('_', ' ').contains(search.trim(), true)
        }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            modifier =
                Modifier.padding(16.dp).widthIn(max = 620.dp).fillMaxWidth().heightIn(max = 720.dp),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(R.string.palette_inspector),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    stringResource(R.string.palette_copy_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
                MonetModeToggle(dark, { dark = it })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MonetFilterChip(
                        shades,
                        { shades = true },
                        { Text(stringResource(R.string.palette_shades)) },
                    )
                    MonetFilterChip(
                        !shades,
                        { shades = false },
                        { Text(stringResource(R.string.color_roles)) },
                    )
                }
                OutlinedTextField(
                    search,
                    { search = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.search_colors)) },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                )
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (entries == null)
                        item {
                            if (failed) Text(stringResource(R.string.preview_failed))
                            else LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                    if (entries != null && filtered.isEmpty())
                        item { Text(stringResource(R.string.no_matching_colors)) }
                    items(filtered, key = { it.name }) { entry ->
                        val color = if (dark) entry.dark else entry.light
                        Surface(
                            onClick = {
                                context
                                    .getSystemService(ClipboardManager::class.java)
                                    ?.setPrimaryClip(
                                        ClipData.newPlainText(entry.name, "#${colorHex(color)}")
                                    )
                            },
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                        ) {
                            Row(
                                Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Surface(
                                    color = Color(color),
                                    shape = CircleShape,
                                    modifier = Modifier.size(36.dp),
                                ) {}
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        entry.name.replace('_', ' '),
                                        style = MaterialTheme.typography.labelLarge,
                                    )
                                    Text(
                                        "#${colorHex(color)}",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                Icon(
                                    Icons.Default.ContentCopy,
                                    stringResource(R.string.copy_color),
                                    Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.close))
                }
            }
        }
    }
}

private fun colorHex(color: Int): String = String.format(Locale.ROOT, "%06X", color and 0xffffff)
