/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.monet

import android.app.UiModeManager
import android.app.WallpaperColors
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

data class SavedLook(
    val id: String,
    val name: String,
    val settings: String,
    val description: String = "",
)

data class MonetBackup(val current: String, val looks: List<SavedLook>)

data class PaletteEntry(val name: String, val light: Int, val dark: Int, val shades: Boolean)

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
    val homeColors: WallpaperColors?,
    val lockColors: WallpaperColors?,
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

    fun load(json: JSONObject = readJson()): MonetSnapshot {
        val settings = MonetSettings.fromJson(json)
        val systemContrast = context.getSystemService(UiModeManager::class.java).contrast.toDouble()
        val lock = settings.source == MonetSettings.LOCK
        val homeColors = wallpaper.getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
        val lockColors = wallpaper.getWallpaperColors(WallpaperManager.FLAG_LOCK)
        val requested = if (lock) lockColors else homeColors
        val colors = requested ?: homeColors
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
            homeColors,
            lockColors,
        )
    }

    fun apply(changes: Map<String, Any?>): MonetSnapshot {
        val json =
            MonetConfig.normalize(MonetConfig.merge(MonetConfig.normalize(readJson()), changes))
        json.put("_applied_timestamp", System.currentTimeMillis())
        validateColors(json)
        val snapshot = load(json)
        check(
            Settings.Secure.putString(
                resolver,
                Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES,
                json.toString(),
            )
        )
        return snapshot
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
        MonetConfig.keys.forEach { json.remove(it) }
        json.put(MonetSettings.SOURCE, MonetSettings.HOME)
        json.put(MonetSettings.STYLE, "TONAL_SPOT")
    }

    fun saveLook(name: String, json: JSONObject) {
        val looks = savedLooks()
        check(looks.size < 20)
        require(name.isNotBlank())
        val snapshot = MonetConfig.owned(MonetConfig.normalize(json))
        writeLooks(
            looks +
                SavedLook(UUID.randomUUID().toString(), name.trim().take(40), snapshot.toString())
        )
    }

    fun deleteLook(id: String) = writeLooks(savedLooks().filterNot { it.id == id })

    fun restoreLook(look: SavedLook, json: JSONObject) {
        val saved = JSONObject(look.settings)
        MonetConfig.keys.forEach { json.remove(it) }
        MonetConfig.keys.forEach { if (saved.has(it)) json.put(it, saved.get(it)) }
        if (saved.optInt(MonetSettings.VERSION, 1) < 2) {
            MonetSettings.PALETTE_CHROMA.forEach {
                if (json.optDouble(it, 0.0) == 0.0) json.remove(it)
            }
        }
    }

    fun renameLook(id: String, name: String, description: String) {
        require(name.isNotBlank())
        writeLooks(
            savedLooks().map {
                if (it.id == id)
                    it.copy(name = name.trim().take(40), description = description.trim().take(160))
                else it
            }
        )
    }

    fun replaceLook(id: String, json: JSONObject) {
        val settings = MonetConfig.owned(MonetConfig.normalize(json)).toString()
        writeLooks(savedLooks().map { if (it.id == id) it.copy(settings = settings) else it })
    }

    fun moveLook(id: String, direction: Int) {
        val looks = savedLooks().toMutableList()
        val from = looks.indexOfFirst { it.id == id }
        val to = from + direction
        if (from !in looks.indices || to !in looks.indices) return
        looks.add(to, looks.removeAt(from))
        writeLooks(looks)
    }

    fun export(uri: Uri, current: JSONObject, lookId: String?) {
        val looks = savedLooks()
        val look = lookId?.let { id -> requireNotNull(looks.find { it.id == id }) }
        val root =
            JSONObject()
                .put("format", "lunaris-monet")
                .put("version", 1)
                .put(
                    "current",
                    MonetConfig.owned(
                        MonetConfig.normalize(look?.let { JSONObject(it.settings) } ?: current)
                    ),
                )
                .put("looks", looksJson(if (look == null) looks else listOf(look)))
        requireNotNull(resolver.openOutputStream(uri, "wt")).bufferedWriter(Charsets.UTF_8).use {
            it.write(root.toString(2))
        }
    }

    fun readBackup(uri: Uri): MonetBackup {
        val bytes = requireNotNull(resolver.openInputStream(uri)).use { it.readNBytes(262145) }
        require(bytes.size <= 262144)
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        require(root.getString("format") == "lunaris-monet" && root.get("version") == 1)
        val current = MonetConfig.imported(root.getJSONObject("current"))
        val entries = root.getJSONArray("looks")
        require(entries.length() <= 20)
        val looks =
            (0 until entries.length()).map { index ->
                val entry = entries.getJSONObject(index)
                val name = entry.getString("name").trim()
                val description = entry.optString("description", "").trim()
                require(name.isNotEmpty() && name.length <= 40 && description.length <= 160)
                val settings = MonetConfig.imported(entry.getJSONObject("settings"))
                validateColors(settings)
                SavedLook(UUID.randomUUID().toString(), name, settings.toString(), description)
            }
        validateColors(current)
        return MonetBackup(current.toString(), looks)
    }

    fun importLooks(backup: MonetBackup) {
        val existing = savedLooks()
        val combined = existing.toMutableList()
        backup.looks.forEach { look ->
            if (
                combined.none {
                    it.name == look.name &&
                        it.description == look.description &&
                        MonetConfig.difference(JSONObject(it.settings), JSONObject(look.settings))
                            .isEmpty()
                }
            ) {
                combined.add(look)
            }
        }
        require(combined.size <= 20)
        writeLooks(combined)
    }

    fun image(uri: Uri?, lock: Boolean): Bitmap {
        fun decode(openStream: () -> java.io.InputStream?): Bitmap {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            openStream()?.use { BitmapFactory.decodeStream(it, null, bounds) }
            require(bounds.outWidth > 0 && bounds.outHeight > 0)
            val options =
                BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inSampleSize = 1
                    while (
                        maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > 1200
                    ) inSampleSize *= 2
                }
            return requireNotNull(
                openStream()?.use { BitmapFactory.decodeStream(it, null, options) }
            )
        }
        if (uri != null) return decode { resolver.openInputStream(uri) }
        val which =
            if (lock && wallpaper.getWallpaperId(WallpaperManager.FLAG_LOCK) >= 0)
                WallpaperManager.FLAG_LOCK
            else WallpaperManager.FLAG_SYSTEM
        if (
            which == WallpaperManager.FLAG_SYSTEM &&
                wallpaper.getWallpaperId(which) < 0 &&
                wallpaper.getWallpaperInfo(which) == null
        ) {
            val drawable =
                requireNotNull(wallpaper.getBuiltInDrawable(1200, 1200, false, 0.5f, 0.5f, which))
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

    private fun savedLooks(): List<SavedLook> = runCatching {
        val array = JSONArray(preferences.getString("looks", "[]"))
        (0 until array.length()).map { index ->
            val entry = array.getJSONObject(index)
            SavedLook(
                entry.getString("id"),
                entry.getString("name"),
                entry.getJSONObject("settings").toString(),
                entry.optString("description", ""),
            )
        }
    }
        .getOrDefault(emptyList())

    private fun writeLooks(looks: List<SavedLook>) {
        check(preferences.edit().putString("looks", looksJson(looks).toString()).commit())
    }

    private fun looksJson(looks: List<SavedLook>): JSONArray {
        val array = JSONArray()
        looks.forEach {
            array.put(
                JSONObject()
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("description", it.description)
                    .put(
                        "settings",
                        MonetConfig.owned(MonetConfig.normalize(JSONObject(it.settings))),
                    )
            )
        }
        return array
    }

    companion object {
        fun inspect(snapshot: MonetSnapshot): List<PaletteEntry> {
            val settings = snapshot.settings
            val light =
                MonetScheme(snapshot.seed, false, settings.style, snapshot.systemContrast, settings)
            val dark =
                MonetScheme(snapshot.seed, true, settings.style, snapshot.systemContrast, settings)
            val shades = DynamicColors.getAllAccentPalette() + DynamicColors.getAllNeutralPalette()
            val roles =
                DynamicColors.getAllDynamicColorsMapped() + DynamicColors.getCustomColorsMapped()
            return shades.map {
                PaletteEntry(it.first, light.getArgb(it.second), dark.getArgb(it.second), true)
            } +
                roles.map {
                    PaletteEntry(it.first, light.getArgb(it.second), dark.getArgb(it.second), false)
                } +
                DynamicColors.getFixedColorsMapped().map {
                    val color = light.getArgb(it.second)
                    PaletteEntry(it.first, color, color, false)
                }
        }

        fun savedPreview(look: SavedLook, snapshot: MonetSnapshot, dark: Boolean): PalettePreview {
            val settings = MonetSettings.fromJson(look.settings)
            val colors =
                if (settings.source == MonetSettings.LOCK)
                    snapshot.lockColors ?: snapshot.homeColors
                else snapshot.homeColors
            val seed = settings.seedColor ?: WallpaperPalette.seedFromWallpaper(colors, settings)
            return preview(
                seed,
                dark,
                settings.style,
                snapshot.systemContrast,
                settings,
            )
        }

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
