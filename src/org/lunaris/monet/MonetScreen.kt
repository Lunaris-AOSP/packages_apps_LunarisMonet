/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package org.lunaris.monet

import android.content.theming.ThemeStyle
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.systemui.monet.MonetSettings
import java.util.Locale

@Composable
fun MonetScreen(viewModel: MonetViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var advanced by rememberSaveable { mutableStateOf(false) }
    var reset by rememberSaveable { mutableStateOf(false) }
    var save by rememberSaveable { mutableStateOf(false) }
    var picker by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val message = state.message?.let { stringResource(it) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val motion = MaterialTheme.motionScheme
    val homeList = rememberLazyListState()
    val advancedList = rememberLazyListState()
    BackHandler(advanced) { advanced = false }
    LaunchedEffect(message) {
        if (message != null) {
            snackbar.showSnackbar(message)
            viewModel.clearMessage()
        }
    }
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        snackbarHost = { SnackbarHost(snackbar, Modifier.padding(bottom = 96.dp)) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(36.dp)
                            .clip(MaterialShapes.Cookie9Sided.toShape())
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.Palette,
                            null,
                            Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    Text(
                        stringResource(R.string.app_name),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLargeEmphasized,
                    )
                    IconButton(
                        shapes = IconButtonDefaults.shapes(),
                        onClick = { save = true },
                        enabled = state.snapshot != null && !state.busy,
                    ) {
                        Icon(Icons.Default.BookmarkAdd, stringResource(R.string.save_preset))
                    }
                    FilledTonalIconButton(
                        shapes = IconButtonDefaults.shapes(),
                        onClick = { reset = true },
                        enabled = state.snapshot != null && !state.busy,
                    ) {
                        Icon(Icons.Default.RestartAlt, stringResource(R.string.reset))
                    }
                }
                val snapshot = state.snapshot
                if (snapshot == null) {
                    Column(
                        Modifier.fillMaxSize().padding(32.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (!state.loadFailed) {
                            LoadingIndicator(Modifier.size(56.dp))
                            Text(stringResource(R.string.loading))
                        } else {
                            Text(stringResource(R.string.load_failed))
                            Button(onClick = viewModel::refresh) {
                                Text(stringResource(R.string.retry))
                            }
                        }
                    }
                } else {
                    AnimatedContent(
                        targetState = advanced,
                        transitionSpec = {
                            val direction = if (targetState) 1 else -1
                            (fadeIn(motion.defaultEffectsSpec()) +
                                slideInHorizontally(motion.defaultSpatialSpec()) {
                                    direction * it / 8
                                }) togetherWith
                                (fadeOut(motion.fastEffectsSpec()) +
                                    slideOutHorizontally(motion.fastSpatialSpec()) {
                                        -direction * it / 8
                                    })
                        },
                        label = "Monet section",
                    ) { showAdvanced ->
                        if (showAdvanced)
                            MonetAdvanced(
                                snapshot,
                                !state.busy,
                                viewModel,
                                listState = advancedList,
                                onColor = { picker = "secondary" },
                            )
                        else
                            MonetHome(
                                snapshot,
                                !state.busy,
                                viewModel,
                                listState = homeList,
                                onColor = { picker = "primary" },
                                onSave = { save = true },
                            )
                    }
                }
            }
            AnimatedVisibility(
                visible = !WindowInsets.isImeVisible,
                modifier =
                    Modifier.align(Alignment.BottomCenter)
                        .padding(start = 18.dp, end = 18.dp, bottom = 16.dp),
                enter =
                    fadeIn(motion.defaultEffectsSpec()) +
                        slideInVertically(motion.defaultSpatialSpec()) { it / 2 },
                exit =
                    fadeOut(motion.fastEffectsSpec()) +
                        slideOutVertically(motion.fastSpatialSpec()) { it / 2 },
            ) {
                MonetToolbar(advanced, onSelect = { advanced = it })
            }
        }
    }
    if (reset)
        AlertDialog(
            onDismissRequest = { reset = false },
            icon = { Icon(Icons.Default.RestartAlt, null) },
            title = { Text(stringResource(R.string.reset_title)) },
            text = { Text(stringResource(R.string.reset_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        reset = false
                        viewModel.reset()
                    }
                ) {
                    Text(stringResource(R.string.reset))
                }
            },
            dismissButton = {
                TextButton(onClick = { reset = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    if (save)
        SaveLookDialog(state.snapshot?.saved?.size ?: 0, onDismiss = { save = false }) { name ->
            save = false
            viewModel.save(name)
        }
    picker?.let { target ->
        val snapshot = state.snapshot
        if (snapshot != null)
            MonetColorPicker(
                initialColor =
                    if (target == "secondary") snapshot.settings.secondarySeed ?: 0xffff5500.toInt()
                    else snapshot.seed,
                viewModel = viewModel,
                onDismiss = { picker = null },
                onApply = { color ->
                    picker = null
                    viewModel.seed(color, target == "secondary")
                },
            )
    }
}

@Composable
private fun MonetIntro(advanced: Boolean) {
    Column(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(if (advanced) R.string.advanced_title else R.string.hero_title),
            style = MaterialTheme.typography.displayMediumEmphasized,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            stringResource(if (advanced) R.string.advanced_summary else R.string.hero_summary),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MonetHome(
    snapshot: MonetSnapshot,
    enabled: Boolean,
    viewModel: MonetViewModel,
    listState: LazyListState,
    onColor: () -> Unit,
    onSave: () -> Unit,
) {
    val settings = snapshot.settings
    val night = isSystemInDarkTheme()
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 116.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        item("intro") { MonetIntro(false) }
        item("wallpaper") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.wallpaper_palettes),
                    style = MaterialTheme.typography.titleLargeEmphasized,
                )
                Text(
                    stringResource(R.string.wallpaper_palettes_summary),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (snapshot.choices.isEmpty())
                    Text(
                        stringResource(R.string.wallpaper_missing),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                else
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.selectableGroup(),
                    ) {
                        items(snapshot.choices, key = { it.palette.index }) { choice ->
                            val palette = choice.palette
                            val selected =
                                settings.source != MonetSettings.PRESET &&
                                    settings.paletteIndex == palette.index &&
                                    settings.style == palette.style &&
                                    settings.seedColor == null &&
                                    !settings.fidelity
                            val description =
                                stringResource(
                                    R.string.palette_number,
                                    palette.index + 1,
                                    stringResource(styleName(palette.style)),
                                )
                            val selectedText = stringResource(R.string.selected)
                            val interaction = remember { MutableInteractionSource() }
                            val pressed by interaction.collectIsPressedAsState()
                            val haptic = LocalHapticFeedback.current
                            val corner by animateDpAsState(
                                if (pressed) 18.dp else if (selected) 32.dp else 24.dp,
                                MaterialTheme.motionScheme.fastSpatialSpec(),
                                label = "palette corner",
                            )
                            val container by animateColorAsState(
                                if (selected) MaterialTheme.colorScheme.secondaryContainer
                                else MaterialTheme.colorScheme.surfaceContainerHigh,
                                MaterialTheme.motionScheme.defaultEffectsSpec(),
                                label = "palette container",
                            )
                            Surface(
                                modifier = Modifier.monetPress(interaction),
                                shape = RoundedCornerShape(corner),
                                color = container,
                            ) {
                                Column(
                                    Modifier.width(88.dp)
                                        .clip(RoundedCornerShape(corner))
                                        .selectable(
                                            selected,
                                            interactionSource = interaction,
                                            indication = ripple(),
                                            enabled = enabled,
                                            role = Role.RadioButton,
                                            onClick = {
                                                haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                                viewModel.palette(palette)
                                            },
                                        )
                                        .semantics {
                                            contentDescription = description
                                            if (selected) stateDescription = selectedText
                                        }
                                        .padding(horizontal = 10.dp, vertical = 14.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    PaletteDisc(
                                        if (night) choice.dark else choice.light,
                                        Modifier.size(68.dp),
                                        selected,
                                    )
                                    Text(
                                        stringResource(styleName(palette.style)),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.labelSmall,
                                        textAlign = TextAlign.Center,
                                    )
                                }
                            }
                        }
                    }
            }
        }
        item("everyday") {
            MonetGroup(stringResource(R.string.everyday_colors)) {
                MonetSwitch(
                    stringResource(R.string.use_wallpaper),
                    stringResource(R.string.use_wallpaper_summary),
                    settings.source != MonetSettings.PRESET,
                    enabled,
                    viewModel::useWallpaper,
                )
                AnimatedVisibility(settings.source != MonetSettings.PRESET) {
                    Column {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MonetFilterChip(
                                selected = settings.source == MonetSettings.HOME,
                                onClick = { viewModel.source(MonetSettings.HOME) },
                                enabled = enabled,
                                label = { Text(stringResource(R.string.home_wallpaper)) },
                            )
                            MonetFilterChip(
                                selected = settings.source == MonetSettings.LOCK,
                                onClick = { viewModel.source(MonetSettings.LOCK) },
                                enabled = enabled,
                                label = { Text(stringResource(R.string.lock_wallpaper)) },
                            )
                        }
                        if (snapshot.lockFallback)
                            Text(
                                stringResource(R.string.lock_wallpaper_fallback),
                                style = MaterialTheme.typography.bodySmall,
                            )
                    }
                }
                ColorRow(stringResource(R.string.custom_color), snapshot.seed, enabled, onColor)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    stringResource(R.string.theme_style),
                    style = MaterialTheme.typography.titleMedium,
                )
                if (settings.fidelity)
                    Text(
                        stringResource(R.string.fidelity_style),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge,
                    )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    styles.forEach { style ->
                        MonetFilterChip(
                            selected = !settings.fidelity && settings.style == style,
                            onClick = {
                                viewModel.setValue(MonetSettings.STYLE, ThemeStyle.toString(style))
                            },
                            enabled = enabled && !settings.fidelity,
                            label = { Text(stringResource(styleName(style))) },
                        )
                    }
                }
                MonetSwitch(
                    stringResource(R.string.fidelity),
                    stringResource(R.string.fidelity_summary),
                    settings.fidelity,
                    enabled,
                ) {
                    viewModel.setValue(MonetSettings.FIDELITY, it)
                }
                MonetSlider(
                    stringResource(R.string.global_chroma),
                    settings.chroma.toInt(),
                    0..100,
                    enabled,
                    summary = stringResource(R.string.global_chroma_summary),
                ) {
                    viewModel.setValue(MonetSettings.CHROMA, it)
                }
            }
        }
        item("preview") { MonetPreview(snapshot.light, snapshot.dark, night) }
        item("saved") {
            MonetGroup(stringResource(R.string.saved_presets)) {
                if (snapshot.saved.isEmpty())
                    Text(
                        stringResource(R.string.presets_empty),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                snapshot.saved.forEach { look ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        FilledTonalButton(
                            shapes = ButtonDefaults.shapes(),
                            onClick = { viewModel.restore(look) },
                            enabled = enabled,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(look.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = { viewModel.delete(look.id) }, enabled = enabled) {
                            Icon(
                                Icons.Default.DeleteOutline,
                                stringResource(R.string.delete_preset, look.name),
                            )
                        }
                    }
                }
                OutlinedButton(
                    shapes = ButtonDefaults.shapes(),
                    onClick = onSave,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Add, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.save_preset))
                }
            }
        }
        item("footer") {
            Text(
                stringResource(R.string.changes_apply),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MonetAdvanced(
    snapshot: MonetSnapshot,
    enabled: Boolean,
    viewModel: MonetViewModel,
    listState: LazyListState,
    onColor: () -> Unit,
) {
    val settings = snapshot.settings
    val night = isSystemInDarkTheme()
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 116.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        item("intro") { MonetIntro(true) }
        item("secondary") {
            MonetGroup(stringResource(R.string.secondary_color)) {
                MonetSwitch(
                    stringResource(R.string.secondary_seed),
                    stringResource(R.string.secondary_seed_summary),
                    settings.secondarySeed != null,
                    enabled,
                    viewModel::secondary,
                )
                AnimatedVisibility(settings.secondarySeed != null) {
                    ColorRow(
                        stringResource(R.string.secondary_color),
                        settings.secondarySeed ?: 0xffff5500.toInt(),
                        enabled,
                        onColor,
                    )
                }
            }
        }
        item("palettes") {
            MonetGroup(
                stringResource(R.string.palette_chroma),
                stringResource(R.string.palette_chroma_summary),
            ) {
                val names =
                    listOf(
                        R.string.primary_chroma,
                        R.string.secondary_chroma,
                        R.string.tertiary_chroma,
                        R.string.neutral_chroma,
                        R.string.neutral_variant_chroma,
                    )
                names.forEachIndexed { index, name ->
                    val inherited = settings.paletteChromaOverride(index) == null
                    val key = MonetSettings.PALETTE_CHROMA[index]
                    Column {
                        Text(
                            stringResource(name),
                            style = MaterialTheme.typography.titleMediumEmphasized,
                        )
                        MonetSwitch(
                            stringResource(R.string.inherit_global),
                            checked = inherited,
                            enabled = enabled,
                        ) {
                            viewModel.setValue(
                                key,
                                if (it) null else settings.paletteChroma(index).toInt(),
                            )
                        }
                        AnimatedVisibility(!inherited) {
                            MonetSlider(
                                stringResource(name),
                                settings.paletteChroma(index).toInt(),
                                0..100,
                                enabled,
                            ) {
                                viewModel.setValue(key, it)
                            }
                        }
                    }
                    if (index != names.lastIndex)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
        item("hue") {
            MonetGroup(stringResource(R.string.hue_lightness)) {
                MonetSlider(
                    stringResource(R.string.hue_rotation),
                    settings.hue.toInt(),
                    -180..180,
                    enabled,
                    unit = R.string.degree_value,
                    summary = stringResource(R.string.hue_rotation_summary),
                ) {
                    viewModel.setValue(MonetSettings.HUE, it)
                }
                Text(
                    stringResource(R.string.lightness_summary),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                MonetSlider(
                    stringResource(R.string.lightness_light),
                    settings.toneLight.toInt(),
                    -50..50,
                    enabled,
                    unit = R.string.tone_value,
                ) {
                    viewModel.setValue(MonetSettings.TONE_LIGHT, it)
                }
                MonetSlider(
                    stringResource(R.string.lightness_dark),
                    settings.toneDark.toInt(),
                    -50..50,
                    enabled,
                    unit = R.string.tone_value,
                ) {
                    viewModel.setValue(MonetSettings.TONE_DARK, it)
                }
            }
        }
        item("contrast") {
            MonetGroup(stringResource(R.string.contrast)) {
                MonetSwitch(
                    stringResource(R.string.system_contrast),
                    stringResource(R.string.system_contrast_summary),
                    settings.contrast == null,
                    enabled,
                ) {
                    viewModel.setValue(
                        MonetSettings.CONTRAST,
                        if (it) null else snapshot.systemContrast,
                    )
                }
                AnimatedVisibility(settings.contrast != null) {
                    MonetSlider(
                        stringResource(R.string.custom_contrast),
                        ((settings.contrast ?: snapshot.systemContrast) * 100).toInt(),
                        -100..100,
                        enabled,
                    ) {
                        viewModel.setValue(MonetSettings.CONTRAST, it / 100.0)
                    }
                }
            }
        }
        item("preview") { MonetPreview(snapshot.light, snapshot.dark, night) }
    }
}

@Composable
private fun ColorRow(title: String, color: Int, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        shapes = ButtonDefaults.shapes(shape = RoundedCornerShape(20.dp)),
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
    ) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(Color(color)))
        Column(Modifier.weight(1f).padding(start = 14.dp), horizontalAlignment = Alignment.Start) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                String.format(Locale.ROOT, "#%06X", color and 0xffffff),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun SaveLookDialog(count: Int, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.BookmarkAdd, null) },
        title = { Text(stringResource(R.string.save_preset)) },
        text = {
            if (count >= 20) Text(stringResource(R.string.preset_limit))
            else
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.preset_name)) },
                )
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name.trim()) },
                enabled = name.isNotBlank() && count < 20,
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

private val styles =
    listOf(
        ThemeStyle.TONAL_SPOT,
        ThemeStyle.VIBRANT,
        ThemeStyle.EXPRESSIVE,
        ThemeStyle.SPRITZ,
        ThemeStyle.RAINBOW,
        ThemeStyle.FRUIT_SALAD,
        ThemeStyle.MONOCHROMATIC,
    )

private fun styleName(style: Int): Int =
    when (style) {
        ThemeStyle.VIBRANT -> R.string.vibrant
        ThemeStyle.EXPRESSIVE -> R.string.expressive
        ThemeStyle.SPRITZ -> R.string.spritz
        ThemeStyle.RAINBOW -> R.string.rainbow
        ThemeStyle.FRUIT_SALAD -> R.string.fruit_salad
        ThemeStyle.MONOCHROMATIC -> R.string.monochromatic
        else -> R.string.tonal_spot
    }
