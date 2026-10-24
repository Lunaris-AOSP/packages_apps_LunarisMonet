/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.monet

import android.app.UiModeManager
import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import android.provider.Settings
import com.android.internal.graphics.ColorUtils
import com.android.systemui.monet.DynamicColors
import com.android.systemui.monet.MonetScheme
import com.android.systemui.monet.MonetSettings
import com.android.systemui.monet.WallpaperPalette
import com.google.ux.material.libmonet.dynamiccolor.MaterialDynamicColors
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

data class PalettePreview(
    val surface: Int,
    val onSurface: Int,
    val primary: Int,
    val onPrimary: Int,
    val primaryContainer: Int,
    val onPrimaryContainer: Int,
    val secondaryContainer: Int,
    val tertiaryContainer: Int,
    val outline: Int,
) {
    val textContrast: Double
        get() = ColorUtils.calculateContrast(onSurface, surface)

    val buttonContrast: Double
        get() = ColorUtils.calculateContrast(onPrimary, primary)
}

data class WallpaperChoice(
    val palette: WallpaperPalette,
    val light: PalettePreview,
    val dark: PalettePreview,
)

data class SavedLook(val id: String, val name: String, val settings: String)

data class MonetSnapshot(
    val json: String,
    val settings: MonetSettings,
    val systemContrast: Double,
    val seed: Int,
    val choices: List<WallpaperChoice>,
    val light: PalettePreview,
    val dark: PalettePreview,
    val saved: List<SavedLook>,
    val lockFallback: Boolean,
)

class MonetRepository(private val context: Context) {
    private val resolver = context.contentResolver
    private val wallpaper = WallpaperManager.getInstance(context)
    private val preferences = context.getSharedPreferences("monet_looks", Context.MODE_PRIVATE)

    fun readJson(): JSONObject {
        val value =
            Settings.Secure.getString(
                resolver,
                Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES,
            )
        return runCatching { JSONObject(value ?: "{}") }.getOrElse { JSONObject() }
    }

    fun load(): MonetSnapshot {
        val json = readJson()
        val settings = MonetSettings.fromJson(json)
        val systemContrast = context.getSystemService(UiModeManager::class.java).contrast.toDouble()
        val lock = settings.source == MonetSettings.LOCK
        val requested =
            wallpaper.getWallpaperColors(
                if (lock) WallpaperManager.FLAG_LOCK else WallpaperManager.FLAG_SYSTEM
            )
        val colors = requested ?: wallpaper.getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
        val seed = settings.seedColor ?: WallpaperPalette.seedFromWallpaper(colors, settings)
        val previewSettings =
            MonetSettings.fromJson(JSONObject(json.toString()).put(MonetSettings.FIDELITY, false))
        val choices =
            WallpaperPalette.fromWallpaper(colors).map {
                WallpaperChoice(
                    it,
                    preview(it.seed, false, it.style, systemContrast, previewSettings),
                    preview(it.seed, true, it.style, systemContrast, previewSettings),
                )
            }
        return MonetSnapshot(
            json.toString(),
            settings,
            systemContrast,
            seed,
            choices,
            preview(seed, false, settings.style, systemContrast, settings),
            preview(seed, true, settings.style, systemContrast, settings),
            savedLooks(),
            lock && requested == null,
        )
    }

    fun update(change: (JSONObject) -> Unit) {
        val json = readJson()
        if (json.optInt(MonetSettings.VERSION, 1) < 2) {
            MonetSettings.PALETTE_CHROMA.forEach {
                if (json.optDouble(it, 0.0) == 0.0) json.remove(it)
            }
        }
        change(json)
        json.put(MonetSettings.VERSION, 2)
        json.put(
            MonetSettings.SOURCE,
            MonetSettings.normalizeSource(json.optString(MonetSettings.SOURCE)),
        )
        if (!json.has(MonetSettings.STYLE)) json.put(MonetSettings.STYLE, "TONAL_SPOT")
        if (!json.has(MonetSettings.COLOR_INDEX)) json.put(MonetSettings.COLOR_INDEX, "0")
        if (json.optString(MonetSettings.SOURCE) == MonetSettings.PRESET) {
            json.remove(MonetSettings.COLOR_BOTH)
        } else {
            json.put(MonetSettings.COLOR_BOTH, "0")
        }
        json.put("_applied_timestamp", System.currentTimeMillis())
        validateColors(json)
        check(
            Settings.Secure.putString(
                resolver,
                Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES,
                json.toString(),
            )
        )
    }

    private fun validateColors(json: JSONObject) {
        val settings = MonetSettings.fromJson(json)
        val which =
            if (settings.source == MonetSettings.LOCK) WallpaperManager.FLAG_LOCK
            else WallpaperManager.FLAG_SYSTEM
        val colors =
            wallpaper.getWallpaperColors(which)
                ?: wallpaper.getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
        val seed = settings.seedColor ?: WallpaperPalette.seedFromWallpaper(colors, settings)
        val contrast = context.getSystemService(UiModeManager::class.java).contrast.toDouble()
        val tokens =
            DynamicColors.getAllAccentPalette() +
                DynamicColors.getAllNeutralPalette() +
                DynamicColors.getAllDynamicColorsMapped() +
                DynamicColors.getCustomColorsMapped()
        val light = MonetScheme(seed, false, settings.style, contrast, settings)
        val dark = MonetScheme(seed, true, settings.style, contrast, settings)
        tokens.forEach {
            light.getArgb(it.second)
            dark.getArgb(it.second)
        }
        DynamicColors.getFixedColorsMapped().forEach { light.getArgb(it.second) }
    }

    fun reset(json: JSONObject) {
        ownedKeys.forEach { json.remove(it) }
        json.put(MonetSettings.SOURCE, MonetSettings.HOME)
        json.put(MonetSettings.STYLE, "TONAL_SPOT")
    }

    fun saveLook(name: String) {
        val looks = savedLooks()
        check(looks.size < 20)
        val current = readJson()
        val snapshot = JSONObject()
        ownedKeys.forEach { if (current.has(it)) snapshot.put(it, current.get(it)) }
        writeLooks(
            looks +
                SavedLook(UUID.randomUUID().toString(), name.trim().take(40), snapshot.toString())
        )
    }

    fun deleteLook(id: String) = writeLooks(savedLooks().filterNot { it.id == id })

    fun restoreLook(look: SavedLook, json: JSONObject) {
        val saved = JSONObject(look.settings)
        ownedKeys.forEach { json.remove(it) }
        ownedKeys.forEach { if (saved.has(it)) json.put(it, saved.get(it)) }
        if (saved.optInt(MonetSettings.VERSION, 1) < 2) {
            MonetSettings.PALETTE_CHROMA.forEach {
                if (json.optDouble(it, 0.0) == 0.0) json.remove(it)
            }
        }
    }

    fun image(uri: Uri?, lock: Boolean): Bitmap {
        fun decode(openStream: () -> java.io.InputStream?): Bitmap {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            openStream()?.use { BitmapFactory.decodeStream(it, null, bounds) }
            require(bounds.outWidth > 0 && bounds.outHeight > 0)
            val options =
                BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    while (
                        maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > 1200
                    ) inSampleSize *= 2
                }
            return requireNotNull(openStream()?.use { BitmapFactory.decodeStream(it, null, options) })
        }
        if (uri != null) return decode { resolver.openInputStream(uri) }
        val which =
            if (lock && wallpaper.getWallpaperId(WallpaperManager.FLAG_LOCK) >= 0)
                WallpaperManager.FLAG_LOCK
            else WallpaperManager.FLAG_SYSTEM
        if (
            which == WallpaperManager.FLAG_SYSTEM &&
                wallpaper.getWallpaperId(which) < 0 && wallpaper.getWallpaperInfo(which) == null
        ) {
            val drawable = requireNotNull(
                wallpaper.getBuiltInDrawable(1200, 1200, false, 0.5f, 0.5f, which)
            )
            val width = drawable.intrinsicWidth.coerceIn(1, 1200)
            val height = drawable.intrinsicHeight.coerceIn(1, 1200)
            return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                drawable.setBounds(0, 0, width, height)
                drawable.draw(Canvas(it))
            }
        }
        return decode {
            wallpaper.getWallpaperFile(which)?.let {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(it)
            }
        }
    }

    fun wallpaperSeeds(lock: Boolean): List<Int> {
        val colors =
            wallpaper.getWallpaperColors(
                if (lock) WallpaperManager.FLAG_LOCK else WallpaperManager.FLAG_SYSTEM
            ) ?: wallpaper.getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
        return WallpaperPalette.fromWallpaper(colors).map { it.seed }.distinct()
    }

    private fun savedLooks(): List<SavedLook> =
        runCatching {
                val array = JSONArray(preferences.getString("looks", "[]"))
                (0 until array.length()).map { index ->
                    val entry = array.getJSONObject(index)
                    SavedLook(
                        entry.getString("id"),
                        entry.getString("name"),
                        entry.getJSONObject("settings").toString(),
                    )
                }
            }
            .getOrDefault(emptyList())

    private fun writeLooks(looks: List<SavedLook>) {
        val array = JSONArray()
        looks.forEach {
            array.put(
                JSONObject()
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("settings", JSONObject(it.settings))
            )
        }
        check(preferences.edit().putString("looks", array.toString()).commit())
    }

    companion object {
        private val ownedKeys =
            MonetSettings.CUSTOM_KEYS.toList() +
                listOf(
                    MonetSettings.SOURCE,
                    MonetSettings.STYLE,
                    MonetSettings.SYSTEM_PALETTE,
                    MonetSettings.ACCENT_COLOR,
                    MonetSettings.COLOR_INDEX,
                    MonetSettings.COLOR_BOTH,
                    "android.theme.customization.dynamic_color",
                )

        private fun preview(
            seed: Int,
            dark: Boolean,
            style: Int,
            contrast: Double,
            settings: MonetSettings,
        ): PalettePreview {
            val scheme = MonetScheme(seed, dark, style, contrast, settings)
            val colors = MaterialDynamicColors()
            return PalettePreview(
                scheme.getArgb(colors.surface()),
                scheme.getArgb(colors.onSurface()),
                scheme.getArgb(colors.primary()),
                scheme.getArgb(colors.onPrimary()),
                scheme.getArgb(colors.primaryContainer()),
                scheme.getArgb(colors.onPrimaryContainer()),
                scheme.getArgb(colors.secondaryContainer()),
                scheme.getArgb(colors.tertiaryContainer()),
                scheme.getArgb(colors.outline()),
            )
        }
    }
}
