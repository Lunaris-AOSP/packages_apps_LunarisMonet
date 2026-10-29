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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
    var color by rememberSaveable { mutableIntStateOf(initialColor or 0xff000000.toInt()) }
    var imageMode by rememberSaveable { mutableStateOf(false) }
    var imageUri by rememberSaveable { mutableStateOf<String?>(null) }
    var rgbMode by rememberSaveable { mutableStateOf(false) }
    val image by viewModel.image.collectAsStateWithLifecycle()
    val scroll = rememberScrollState()
    val keyboard = LocalSoftwareKeyboardController.current
    var importFailed by remember { mutableStateOf(false) }
    val parsed = parsePickerHex(hex)
    val originalHsv = remember(initialColor) {
        FloatArray(3).also { AndroidColor.colorToHSV(initialColor, it) }
    }
    var hsv by rememberSaveable { mutableStateOf(originalHsv.copyOf()) }
    fun selectColor(value: Int) {
        color = value or 0xff000000.toInt()
        hex = hex(color)
        hsv =
            FloatArray(3).also {
                AndroidColor.colorToHSV(color, it)
                if (it[1] == 0f) it[0] = hsv[0]
                if (it[2] == 0f) it[1] = hsv[1]
            }
    }
    fun changeHsv(value: FloatArray) {
        hsv = value
        color = AndroidColor.HSVToColor(value)
        hex = hex(color)
    }
    val importImage =
        rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) {
                if (imageMode && imageUri == uri.toString()) viewModel.loadImage(uri)
                imageUri = uri.toString()
                hex = hex(color)
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
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier =
                Modifier.padding(horizontal = 16.dp, vertical = 24.dp)
                    .widthIn(max = 520.dp)
                    .fillMaxWidth(),
            shape = RoundedCornerShape(32.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 760.dp).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    stringResource(
                        if (!imageMode) R.string.choose_color
                        else if (imageUri == null) R.string.pick_wallpaper
                        else R.string.image_palette
                    ),
                    style = MaterialTheme.typography.headlineSmallEmphasized,
                    modifier = Modifier.semantics { heading() },
                )
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(scroll),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    PickerColorPreview(initialColor, color)
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
                        AdvancedColorPicker(hsv, ::changeHsv)
                        OutlinedTextField(
                            value = hex,
                            onValueChange = {
                                hex = it.trim().removePrefix("#").uppercase(Locale.ROOT)
                                parsePickerHex(hex)?.let(::selectColor)
                            },
                            label = { Text(stringResource(R.string.hex_color)) },
                            prefix = { Text("#") },
                            singleLine = true,
                            isError = parsed == null,
                            supportingText = {
                                if (parsed == null) Text(stringResource(R.string.hex_invalid))
                            },
                            shape = RoundedCornerShape(16.dp),
                            keyboardOptions =
                                KeyboardOptions(
                                    capitalization = KeyboardCapitalization.Characters,
                                    autoCorrectEnabled = false,
                                    keyboardType = KeyboardType.Ascii,
                                    imeAction = ImeAction.Done,
                                ),
                            keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        PickerChannelControls(
                            hsv = hsv,
                            color = color,
                            rgbMode = rgbMode,
                            onModeChange = { rgbMode = it },
                            onHsvChange = ::changeHsv,
                            onColorChange = ::selectColor,
                        )
                        TextButton(
                            onClick = {
                                selectColor(initialColor)
                                hsv = originalHsv.copyOf()
                            },
                            enabled =
                                color != (initialColor or 0xff000000.toInt()) ||
                                    !hsv.contentEquals(originalHsv) || parsed == null,
                            modifier = Modifier.align(Alignment.End),
                        ) {
                            Text(stringResource(R.string.reset_picker_color))
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        FilledTonalButton(
                            shapes = ButtonDefaults.shapes(),
                            onClick = {
                                imageUri = null
                                hex = hex(color)
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
                    }
                    if (importFailed) {
                        Text(
                            stringResource(R.string.image_import_unavailable),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
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

internal fun parsePickerHex(value: String): Int? =
    value.takeIf { it.length == 6 && it.all { digit -> digit in "0123456789abcdefABCDEF" } }
        ?.toIntOrNull(16)
        ?.or(0xff000000.toInt())

private fun hex(color: Int): String = String.format(Locale.ROOT, "%06X", color and 0xffffff)
