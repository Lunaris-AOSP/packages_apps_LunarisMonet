/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package org.lunaris.monet

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
fun Modifier.monetPress(interaction: MutableInteractionSource): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.96f else 1f,
        MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "press scale",
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

@Composable
fun MonetFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val corner by animateDpAsState(
        if (enabled && pressed) 8.dp else if (selected) 20.dp else 12.dp,
        MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "chip corner",
    )
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = label,
        modifier = modifier.monetPress(interaction),
        enabled = enabled,
        shape = RoundedCornerShape(corner),
        interactionSource = interaction,
    )
}

@Composable
private fun animatedPaletteColor(argb: Int): Color {
    val color by animateColorAsState(
        Color(argb),
        MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "preview color",
    )
    return color
}

@Composable
fun MonetToolbar(advanced: Boolean, onSelect: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val desiredWidth = 288.dp * LocalDensity.current.fontScale.coerceIn(1f, 1.3f)
    val width = minOf(desiredWidth, (LocalConfiguration.current.screenWidthDp - 48).dp)
    HorizontalFloatingToolbar(
        expanded = true,
        modifier = modifier,
        shape = CircleShape,
        contentPadding = PaddingValues(6.dp),
        expandedShadowElevation = 6.dp,
        colors =
            FloatingToolbarDefaults.standardFloatingToolbarColors(
                toolbarContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                toolbarContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
    ) {
        BoxWithConstraints(Modifier.width(width).height(52.dp)) {
            val itemWidth = maxWidth / 2
            val offset by
                animateDpAsState(
                    if (advanced) itemWidth else 0.dp,
                    MaterialTheme.motionScheme.defaultSpatialSpec(),
                    label = "section indicator",
                )
            Box(
                Modifier.offset(x = offset)
                    .width(itemWidth)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            )
            Row(Modifier.fillMaxSize().selectableGroup()) {
                listOf(false, true).forEach { isAdvanced ->
                    val selected = advanced == isAdvanced
                    val interaction = remember { MutableInteractionSource() }
                    val haptic = LocalHapticFeedback.current
                    val color by
                        animateColorAsState(
                            if (selected) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSecondaryContainer,
                            MaterialTheme.motionScheme.fastEffectsSpec(),
                            label = "section color",
                        )
                    val title = stringResource(if (isAdvanced) R.string.advanced else R.string.home)
                    Row(
                        Modifier.weight(1f)
                            .fillMaxHeight()
                            .monetPress(interaction)
                            .clip(CircleShape)
                            .selectable(
                                selected,
                                interactionSource = interaction,
                                indication = ripple(),
                                role = Role.Tab,
                                onClick = {
                                    if (!selected) {
                                        haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                        onSelect(isAdvanced)
                                    }
                                },
                            ),
                        horizontalArrangement =
                            Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (isAdvanced) Icons.Default.Tune else Icons.Default.Home,
                            null,
                            Modifier.size(22.dp),
                            tint = color,
                        )
                        Text(
                            title,
                            style = MaterialTheme.typography.labelLargeEmphasized,
                            color = color,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MonetGroup(
    title: String,
    summary: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            title,
            Modifier.padding(horizontal = 4.dp).semantics { heading() },
            style = MaterialTheme.typography.titleLargeEmphasized,
        )
        if (summary != null)
            Text(
                summary,
                Modifier.padding(horizontal = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Column(
                Modifier.fillMaxWidth()
                    .animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                content = content,
            )
        }
    }
}

@Composable
fun MonetSwitch(
    title: String,
    summary: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val haptic = LocalHapticFeedback.current
    Row(
        Modifier.fillMaxWidth()
            .monetPress(interaction)
            .clip(MaterialTheme.shapes.medium)
            .toggleable(
                checked,
                interactionSource = interaction,
                indication = ripple(),
                enabled = enabled,
                role = Role.Switch,
                onValueChange = {
                    haptic.performHapticFeedback(
                        if (it) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff
                    )
                    onChange(it)
                },
            )
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (summary != null)
                Text(
                    summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            thumbContent = if (checked) {
                { Icon(Icons.Default.Check, null, Modifier.size(SwitchDefaults.IconSize)) }
            } else null,
        )
    }
}

@Composable
fun MonetSlider(
    title: String,
    value: Int,
    range: IntRange,
    enabled: Boolean = true,
    unit: Int = R.string.percent_value,
    summary: String? = null,
    onChange: (Int) -> Unit,
) {
    var pending by remember { mutableFloatStateOf(value.toFloat()) }
    var dragging by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val badgeCorner by animateDpAsState(
        if (dragging) 12.dp else 20.dp,
        MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "slider value shape",
    )
    LaunchedEffect(value, enabled) { if (!dragging && enabled) pending = value.toFloat() }
    Column {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Surface(
                shape = RoundedCornerShape(badgeCorner),
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Text(
                    stringResource(unit, pending.roundToInt()),
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelLargeEmphasized,
                )
            }
            IconButton(
                shapes = IconButtonDefaults.shapes(),
                onClick = {
                    pending = 0f
                    haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    onChange(0)
                },
                enabled = enabled && value != 0,
            ) {
                Icon(
                    Icons.Default.RestartAlt,
                    stringResource(R.string.reset_adjustment, title),
                    Modifier.size(20.dp),
                )
            }
        }
        if (summary != null)
            Text(
                summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        Slider(
            value = pending,
            onValueChange = {
                dragging = true
                pending = it
            },
            onValueChangeFinished = {
                dragging = false
                haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                onChange(pending.roundToInt())
            },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = title },
        )
    }
}

@Composable
fun PaletteDisc(preview: PalettePreview, modifier: Modifier = Modifier, selected: Boolean = false) {
    val primaryContainer = animatedPaletteColor(preview.primaryContainer)
    val tertiaryContainer = animatedPaletteColor(preview.tertiaryContainer)
    val primary = animatedPaletteColor(preview.primary)
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            drawArc(primaryContainer, 180f, 180f, true)
            drawArc(tertiaryContainer, 90f, 90f, true)
            drawArc(primary, 0f, 90f, true)
        }
        AnimatedVisibility(
            selected,
            enter = fadeIn(MaterialTheme.motionScheme.fastEffectsSpec()) +
                scaleIn(MaterialTheme.motionScheme.fastSpatialSpec(), initialScale = 0.6f),
            exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()) +
                scaleOut(MaterialTheme.motionScheme.fastSpatialSpec(), targetScale = 0.6f),
        ) {
            Surface(shape = CircleShape, color = Color(preview.secondaryContainer)) {
                Icon(
                    Icons.Default.Check,
                    null,
                    Modifier.padding(8.dp).size(18.dp),
                    tint = Color(preview.onPrimaryContainer),
                )
            }
        }
    }
}

@Composable
fun MonetPreview(light: PalettePreview, dark: PalettePreview, initialDark: Boolean) {
    var night by rememberSaveable { mutableStateOf(initialDark) }
    val palette = if (night) dark else light
    MonetGroup(stringResource(R.string.preview)) {
        Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MonetFilterChip(
                selected = !night,
                onClick = { night = false },
                label = { Text(stringResource(R.string.light)) },
            )
            MonetFilterChip(
                selected = night,
                onClick = { night = true },
                label = { Text(stringResource(R.string.dark)) },
            )
        }
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = animatedPaletteColor(palette.surface),
            contentColor = animatedPaletteColor(palette.onSurface),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    stringResource(R.string.preview_title),
                    style = MaterialTheme.typography.titleLargeEmphasized,
                )
                Text(
                    stringResource(R.string.preview_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Surface(
                    shape = CircleShape,
                    color = animatedPaletteColor(palette.primaryContainer),
                    contentColor = animatedPaletteColor(palette.onPrimaryContainer),
                ) {
                    Text(
                        stringResource(R.string.preview_chip),
                        Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                Surface(
                    shape = CircleShape,
                    color = animatedPaletteColor(palette.primary),
                    contentColor = animatedPaletteColor(palette.onPrimary),
                ) {
                    Text(
                        stringResource(R.string.preview_button),
                        Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.labelLargeEmphasized,
                    )
                }
            }
        }
        Text(
            stringResource(R.string.contrast_readout, palette.textContrast, palette.buttonContrast),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (minOf(palette.textContrast, palette.buttonContrast) < 4.5) {
            Text(
                stringResource(R.string.contrast_low),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
