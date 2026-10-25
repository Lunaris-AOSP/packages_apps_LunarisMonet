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
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale

@Composable
fun MonetColorPicker(
    initialColor: Int,
    viewModel: MonetViewModel,
    onDismiss: () -> Unit,
    onApply: (Int) -> Unit,
) {
    var hex by rememberSaveable { mutableStateOf(hex(initialColor)) }
    var imageMode by rememberSaveable { mutableStateOf(false) }
    var imageUri by rememberSaveable { mutableStateOf<String?>(null) }
    val image by viewModel.image.collectAsStateWithLifecycle()
    val scroll = rememberScrollState()
    val keyboard = LocalSoftwareKeyboardController.current
    var importFailed by remember { mutableStateOf(false) }
    val parsed =
        hex.takeIf { it.matches(Regex("[0-9A-Fa-f]{6}")) }
            ?.toLongOrNull(16)
            ?.toInt()
            ?.or(0xff000000.toInt())
    val color = parsed ?: initialColor
    var hsv by rememberSaveable {
        mutableStateOf(FloatArray(3).also { AndroidColor.colorToHSV(initialColor, it) })
    }
    fun selectColor(value: Int) {
        hex = hex(value)
        hsv = FloatArray(3).also { AndroidColor.colorToHSV(value, it) }
    }
    fun changeHsv(index: Int, value: Float) {
        hsv = hsv.copyOf().also { it[index] = value }
        hex = hex(AndroidColor.HSVToColor(hsv))
    }
    val importImage =
        rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) {
                if (imageMode && imageUri == uri.toString()) viewModel.loadImage(uri)
                imageUri = uri.toString()
                imageMode = true
            }
        }
    fun openImage() {
        try {
            importFailed = false
            keyboard?.hide()
            importImage.launch("image/*")
        } catch (_: ActivityNotFoundException) {
            importFailed = true
        }
    }
    LaunchedEffect(imageMode, imageUri) {
        scroll.scrollTo(0)
        if (imageMode) {
            keyboard?.hide()
            viewModel.loadImage(imageUri?.let(Uri::parse))
        } else viewModel.clearImage()
    }
    DisposableEffect(viewModel) { onDispose { viewModel.clearImage() } }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 680.dp).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    stringResource(
                        if (!imageMode) R.string.choose_color
                        else if (imageUri == null) R.string.pick_wallpaper
                        else R.string.image_palette
                    ),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(scroll),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box(
                        Modifier.fillMaxWidth()
                            .height(64.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color(color))
                    )
                    Text(
                        stringResource(R.string.sampled_color, hex(color)),
                        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.labelLarge,
                    )
                    if (imageMode) {
                        if (image.loading || (image.bitmap == null && !image.failed)) {
                            Box(
                                Modifier.fillMaxWidth().height(240.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                LoadingIndicator(Modifier.size(56.dp))
                            }
                        }
                        image.bitmap?.let { bitmap ->
                            Text(
                                stringResource(R.string.image_pick_hint),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            PickerImageCanvas(bitmap, ::selectColor)
                        }
                        if (image.failed) {
                            Text(
                                stringResource(
                                    if (imageUri == null) R.string.wallpaper_image_unavailable
                                    else R.string.image_load_failed
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedButton(
                                shapes = ButtonDefaults.shapes(),
                                onClick = { viewModel.loadImage(imageUri?.let(Uri::parse)) },
                            ) {
                                Text(stringResource(R.string.retry))
                            }
                        }
                        if (image.colors.isNotEmpty()) {
                            Text(
                                stringResource(R.string.wallpaper_extracted_colors),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                image.colors.forEach { seed ->
                                    val description =
                                        stringResource(R.string.sampled_color, hex(seed))
                                    FilledTonalIconToggleButton(
                                        shapes = IconButtonDefaults.toggleableShapes(),
                                        checked = color == seed,
                                        onCheckedChange = { selectColor(seed) },
                                        modifier =
                                            Modifier.size(52.dp).semantics {
                                                contentDescription = description
                                            },
                                    ) {
                                        Box(
                                            Modifier.size(34.dp)
                                                .clip(CircleShape)
                                                .background(Color(seed))
                                        )
                                    }
                                }
                            }
                        }
                        FilledTonalButton(
                            shapes = ButtonDefaults.shapes(),
                            onClick = { imageMode = false },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.adjust_color))
                        }
                        OutlinedButton(
                            shapes = ButtonDefaults.shapes(),
                            onClick = ::openImage,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.pick_image))
                        }
                    } else {
                        FilledTonalButton(
                            shapes = ButtonDefaults.shapes(),
                            onClick = {
                                imageUri = null
                                imageMode = true
                            },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) {
                            Text(stringResource(R.string.pick_wallpaper))
                        }
                        OutlinedButton(
                            shapes = ButtonDefaults.shapes(),
                            onClick = ::openImage,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) {
                            Text(stringResource(R.string.pick_image))
                        }
                        OutlinedTextField(
                            value = hex,
                            onValueChange = {
                                hex = it.removePrefix("#").take(6).uppercase(Locale.ROOT)
                                if (hex.matches(Regex("[0-9A-F]{6}"))) {
                                    hsv =
                                        FloatArray(3).also { channels ->
                                            AndroidColor.colorToHSV(
                                                hex.toLong(16).toInt() or 0xff000000.toInt(),
                                                channels,
                                            )
                                        }
                                }
                            },
                            label = { Text(stringResource(R.string.hex_color)) },
                            prefix = { Text("#") },
                            singleLine = true,
                            isError = parsed == null,
                            supportingText = {
                                if (parsed == null) Text(stringResource(R.string.hex_invalid))
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        PickerSlider(stringResource(R.string.hue), hsv[0], 0f..360f) {
                            changeHsv(0, it)
                        }
                        PickerSlider(stringResource(R.string.saturation), hsv[1] * 100, 0f..100f) {
                            changeHsv(1, it / 100)
                        }
                        PickerSlider(stringResource(R.string.brightness), hsv[2] * 100, 0f..100f) {
                            changeHsv(2, it / 100)
                        }
                    }
                    if (importFailed) {
                        Text(
                            stringResource(R.string.image_import_unavailable),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        shapes = ButtonDefaults.shapes(),
                        onClick = { parsed?.let(onApply) },
                        enabled = parsed != null,
                    ) {
                        Text(stringResource(R.string.use_color))
                    }
                }
            }
        }
    }
}

@Composable
private fun PickerImageCanvas(bitmap: Bitmap, onColor: (Int) -> Unit) {
    var bounds by remember { mutableStateOf(IntSize.Zero) }
    var selectedPoint by remember(bitmap) { mutableStateOf<Offset?>(null) }
    val haptic = LocalHapticFeedback.current
    Box(
        Modifier.fillMaxWidth()
            .height(260.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .onSizeChanged { bounds = it }
            .pointerInput(bitmap, bounds) {
                detectTapGestures { point ->
                    if (bounds.width == 0 || bounds.height == 0) return@detectTapGestures
                    val scale =
                        minOf(
                            bounds.width.toFloat() / bitmap.width,
                            bounds.height.toFloat() / bitmap.height,
                        )
                    val x = (point.x - (bounds.width - bitmap.width * scale) / 2) / scale
                    val y = (point.y - (bounds.height - bitmap.height * scale) / 2) / scale
                    if (x >= 0 && y >= 0 && x < bitmap.width && y < bitmap.height) {
                        selectedPoint = Offset(x / bitmap.width, y / bitmap.height)
                        onColor(bitmap.getPixel(x.toInt(), y.toInt()))
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                }
            }
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = stringResource(R.string.image_palette),
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
        Canvas(Modifier.fillMaxSize()) {
            selectedPoint?.let { point ->
                val scale = minOf(size.width / bitmap.width, size.height / bitmap.height)
                val center =
                    Offset(
                        (size.width - bitmap.width * scale) / 2 + point.x * bitmap.width * scale,
                        (size.height - bitmap.height * scale) / 2 + point.y * bitmap.height * scale,
                    )
                drawCircle(Color.Black, 12.dp.toPx(), center, style = Stroke(5.dp.toPx()))
                drawCircle(Color.White, 12.dp.toPx(), center, style = Stroke(2.dp.toPx()))
            }
        }
    }
}

@Composable
private fun PickerSlider(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Column {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.semantics { contentDescription = title },
        )
    }
}

private fun hex(color: Int): String = String.format(Locale.ROOT, "%06X", color and 0xffffff)
