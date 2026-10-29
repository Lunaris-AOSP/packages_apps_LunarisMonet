/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package org.lunaris.monet

import android.graphics.Color as AndroidColor
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.roundToInt

private val HueSpectrum =
    listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)

@Composable
internal fun PickerColorPreview(original: Int, selected: Int) {
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        listOf(R.string.original_color to original, R.string.new_color to selected)
            .forEachIndexed { index, (label, argb) ->
                val color = Color(argb or 0xff000000.toInt())
                Surface(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    shape =
                        if (index == 0)
                            RoundedCornerShape(
                                topStart = 24.dp,
                                topEnd = 8.dp,
                                bottomStart = 24.dp,
                                bottomEnd = 8.dp,
                            )
                        else
                            RoundedCornerShape(
                                topStart = 8.dp,
                                topEnd = 24.dp,
                                bottomStart = 8.dp,
                                bottomEnd = 24.dp,
                            ),
                    color = color,
                    contentColor = if (color.luminance() > 0.179f) Color.Black else Color.White,
                ) {
                    Column(
                        Modifier.heightIn(min = 76.dp)
                            .padding(14.dp)
                            .semantics(mergeDescendants = true) {
                                if (index == 1) liveRegion = LiveRegionMode.Polite
                            },
                        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                    ) {
                        Text(stringResource(label), style = MaterialTheme.typography.labelMedium)
                        Text(
                            String.format(Locale.ROOT, "#%06X", argb and 0xffffff),
                            style = MaterialTheme.typography.titleMediumEmphasized,
                        )
                    }
                }
            }
    }
}

@Composable
internal fun AdvancedColorPicker(hsv: FloatArray, onChange: (FloatArray) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PickerColorField(hsv) { saturation, brightness ->
            onChange(floatArrayOf(hsv[0], saturation, brightness))
        }
        Text(
            stringResource(R.string.color_field_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PickerChannelSlider(
            title = stringResource(R.string.hue),
            value = hsv[0],
            range = 0f..360f,
            valueLabel = stringResource(R.string.degree_value, hsv[0].roundToInt()),
            colors = HueSpectrum,
        ) { onChange(hsv.copyOf().also { channels -> channels[0] = it }) }
    }
}

@Composable
internal fun PickerChannelControls(
    hsv: FloatArray,
    color: Int,
    rgbMode: Boolean,
    onModeChange: (Boolean) -> Unit,
    onHsvChange: (FloatArray) -> Unit,
    onColorChange: (Int) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.fine_tune_color),
                style = MaterialTheme.typography.titleSmallEmphasized,
            )
            FlowRow(
                Modifier.selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MonetFilterChip(
                    selected = !rgbMode,
                    onClick = { onModeChange(false) },
                    label = { Text(stringResource(R.string.color_mode_hsv)) },
                )
                MonetFilterChip(
                    selected = rgbMode,
                    onClick = { onModeChange(true) },
                    label = { Text(stringResource(R.string.color_mode_rgb)) },
                )
            }
            if (rgbMode) {
                val channels =
                    intArrayOf(
                        AndroidColor.red(color),
                        AndroidColor.green(color),
                        AndroidColor.blue(color),
                    )
                val labels = listOf(R.string.red_channel, R.string.green_channel, R.string.blue_channel)
                channels.forEachIndexed { index, channel ->
                    fun colorAt(value: Int): Int {
                        val next = channels.copyOf().also { it[index] = value }
                        return AndroidColor.rgb(next[0], next[1], next[2])
                    }
                    PickerChannelSlider(
                        title = stringResource(labels[index]),
                        value = channel.toFloat(),
                        range = 0f..255f,
                        valueLabel = stringResource(R.string.tone_value, channel),
                        colors = listOf(Color(colorAt(0)), Color(colorAt(255))),
                        steps = 254,
                    ) { onColorChange(colorAt(it.roundToInt())) }
                }
            } else {
                PickerChannelSlider(
                    title = stringResource(R.string.saturation),
                    value = hsv[1] * 100f,
                    range = 0f..100f,
                    valueLabel = stringResource(R.string.percent_value, (hsv[1] * 100f).roundToInt()),
                    colors =
                        listOf(
                            Color(AndroidColor.HSVToColor(floatArrayOf(hsv[0], 0f, hsv[2]))),
                            Color(AndroidColor.HSVToColor(floatArrayOf(hsv[0], 1f, hsv[2]))),
                        ),
                ) { onHsvChange(hsv.copyOf().also { channels -> channels[1] = it / 100f }) }
                PickerChannelSlider(
                    title = stringResource(R.string.brightness),
                    value = hsv[2] * 100f,
                    range = 0f..100f,
                    valueLabel = stringResource(R.string.percent_value, (hsv[2] * 100f).roundToInt()),
                    colors =
                        listOf(
                            Color.Black,
                            Color(AndroidColor.HSVToColor(floatArrayOf(hsv[0], hsv[1], 1f))),
                        ),
                ) { onHsvChange(hsv.copyOf().also { channels -> channels[2] = it / 100f }) }
            }
        }
    }
}

@Composable
private fun PickerColorField(hsv: FloatArray, onChange: (Float, Float) -> Unit) {
    val currentOnChange by rememberUpdatedState(onChange)
    val haptic = LocalHapticFeedback.current
    var dragging by remember { mutableStateOf(false) }
    val handleRadius by
        animateDpAsState(
            if (dragging) 14.dp else 10.dp,
            MaterialTheme.motionScheme.fastSpatialSpec(),
            label = "color handle size",
        )
    val description = stringResource(R.string.color_field)
    val position =
        stringResource(
            R.string.color_field_value,
            (hsv[1] * 100f).roundToInt(),
            (hsv[2] * 100f).roundToInt(),
        )
    val increaseSaturation = stringResource(R.string.increase_saturation)
    val decreaseSaturation = stringResource(R.string.decrease_saturation)
    val increaseBrightness = stringResource(R.string.increase_brightness)
    val decreaseBrightness = stringResource(R.string.decrease_brightness)
    val shape = RoundedCornerShape(24.dp)
    Canvas(
        Modifier.fillMaxWidth()
            .height(192.dp)
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .semantics {
                contentDescription = description
                stateDescription = position
                customActions =
                    listOf(
                        CustomAccessibilityAction(increaseSaturation) {
                            onChange((hsv[1] + 0.05f).coerceAtMost(1f), hsv[2])
                            true
                        },
                        CustomAccessibilityAction(decreaseSaturation) {
                            onChange((hsv[1] - 0.05f).coerceAtLeast(0f), hsv[2])
                            true
                        },
                        CustomAccessibilityAction(increaseBrightness) {
                            onChange(hsv[1], (hsv[2] + 0.05f).coerceAtMost(1f))
                            true
                        },
                        CustomAccessibilityAction(decreaseBrightness) {
                            onChange(hsv[1], (hsv[2] - 0.05f).coerceAtLeast(0f))
                            true
                        },
                    )
            }
            .pointerInput(Unit) {
                fun select(point: Offset) {
                    if (size.width > 0 && size.height > 0) {
                        val inset = minOf(18.dp.toPx(), size.width / 2f, size.height / 2f)
                        currentOnChange(
                            ((point.x - inset) / (size.width - 2f * inset).coerceAtLeast(1f))
                                .coerceIn(0f, 1f),
                            1f - ((point.y - inset) / (size.height - 2f * inset).coerceAtLeast(1f))
                                .coerceIn(0f, 1f),
                        )
                    }
                }
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    dragging = true
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    select(down.position)
                    try {
                        drag(down.id) { change ->
                            change.consume()
                            select(change.position)
                        }
                    } finally {
                        dragging = false
                    }
                }
            }
    ) {
        val hue = Color(AndroidColor.HSVToColor(floatArrayOf(hsv[0], 1f, 1f)))
        val inset = minOf(18.dp.toPx(), size.width / 2f, size.height / 2f)
        drawRect(
            Brush.horizontalGradient(
                listOf(Color.White, hue),
                startX = inset,
                endX = (size.width - inset).coerceAtLeast(inset + 1f),
            )
        )
        drawRect(
            Brush.verticalGradient(
                listOf(Color.Transparent, Color.Black),
                startY = inset,
                endY = (size.height - inset).coerceAtLeast(inset + 1f),
            )
        )
        val radius = handleRadius.toPx()
        val center =
            Offset(
                inset + hsv[1] * (size.width - 2f * inset),
                inset + (1f - hsv[2]) * (size.height - 2f * inset),
            )
        drawCircle(Color(AndroidColor.HSVToColor(hsv)), radius, center)
        drawCircle(Color.Black, radius, center, style = Stroke(5.dp.toPx()))
        drawCircle(Color.White, radius, center, style = Stroke(2.5.dp.toPx()))
    }
}

@Composable
private fun PickerChannelSlider(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueLabel: String,
    colors: List<Color>,
    steps: Int = 0,
    onChange: (Float) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val haptic = LocalHapticFeedback.current
    Column {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Text(
                    valueLabel,
                    Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLargeEmphasized,
                )
            }
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            onValueChangeFinished = { haptic.performHapticFeedback(HapticFeedbackType.SegmentTick) },
            interactionSource = interaction,
            modifier =
                Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                    contentDescription = title
                    stateDescription = valueLabel
                },
            thumb = {
                SliderDefaults.Thumb(
                    interactionSource = interaction,
                    colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.onSurface),
                    thumbSize = DpSize(6.dp, 44.dp),
                    modifier =
                        Modifier.border(
                            1.dp,
                            MaterialTheme.colorScheme.surface,
                            RoundedCornerShape(3.dp),
                        ),
                )
            },
            track = {
                Canvas(Modifier.fillMaxWidth().height(24.dp)) {
                    drawRoundRect(
                        brush =
                            Brush.horizontalGradient(
                                if (layoutDirection == LayoutDirection.Rtl) colors.reversed() else colors
                            ),
                        cornerRadius = CornerRadius(size.height / 2f),
                    )
                }
            },
        )
    }
}
