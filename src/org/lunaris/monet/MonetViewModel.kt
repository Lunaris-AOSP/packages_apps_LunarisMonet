/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.monet

import android.app.Application
import android.app.UiModeManager
import android.app.WallpaperManager
import android.database.ContentObserver
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.android.systemui.monet.MonetSettings
import com.android.systemui.monet.WallpaperPalette
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class MonetUiState(
    val snapshot: MonetSnapshot? = null,
    val busy: Boolean = false,
    val message: Int? = null,
    val loadFailed: Boolean = false,
)

data class PickerImage(
    val bitmap: Bitmap? = null,
    val colors: List<Int> = emptyList(),
    val loading: Boolean = false,
    val failed: Boolean = false,
)

class MonetViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = MonetRepository(application)
    private val mutex = Mutex()
    private val _state = MutableStateFlow(MonetUiState())
    val state = _state.asStateFlow()
    private val _image = MutableStateFlow(PickerImage())
    val image = _image.asStateFlow()
    private var imageJob: Job? = null
    private val wallpaper = WallpaperManager.getInstance(application)
    private val uiMode = application.getSystemService(UiModeManager::class.java)
    private val observer =
        object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = refresh()
        }
    private val wallpaperListener = WallpaperManager.OnColorsChangedListener { _, _ -> refresh() }
    private val contrastListener = UiModeManager.ContrastChangeListener { refresh() }

    init {
        application.contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.THEME_CUSTOMIZATION_OVERLAY_PACKAGES),
            false,
            observer,
        )
        wallpaper.addOnColorsChangedListener(wallpaperListener, Handler(Looper.getMainLooper()))
        uiMode.addContrastChangeListener(application.mainExecutor, contrastListener)
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            mutex.withLock {
                try {
                    val snapshot = withContext(Dispatchers.Default) { repository.load() }
                    _state.update { it.copy(snapshot = snapshot, loadFailed = false) }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.e("LunarisMonet", "Could not load colors", e)
                    _state.update { it.copy(message = R.string.load_failed, loadFailed = true) }
                }
            }
        }
    }

    private fun edit(message: Int? = null, change: (JSONObject) -> Unit) {
        viewModelScope.launch {
            mutex.withLock {
                _state.update { it.copy(busy = true) }
                try {
                    val snapshot =
                        withContext(Dispatchers.Default) {
                            repository.update(change)
                            repository.load()
                        }
                    _state.update { it.copy(snapshot = snapshot, message = message) }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.e("LunarisMonet", "Could not apply colors", e)
                    _state.update { it.copy(message = R.string.apply_failed) }
                } finally {
                    _state.update { it.copy(busy = false) }
                }
            }
        }
    }

    fun setValue(key: String, value: Any?) = edit { json ->
        if (value == null) json.remove(key) else json.put(key, value)
        if (key == MonetSettings.STYLE || key == MonetSettings.FIDELITY)
            json.remove(MonetSettings.PALETTE_INDEX)
    }

    fun useWallpaper(enabled: Boolean) {
        if (enabled) source(MonetSettings.HOME)
        else {
            val color = _state.value.snapshot?.seed ?: 0xff6750a4.toInt()
            seed(color, secondary = false)
        }
    }

    fun source(source: String) = edit { json ->
        json.put(MonetSettings.SOURCE, source)
        json.remove(MonetSettings.SYSTEM_PALETTE)
        json.remove(MonetSettings.ACCENT_COLOR)
        json.remove("android.theme.customization.dynamic_color")
    }

    fun palette(palette: WallpaperPalette) = edit { json ->
        if (
            MonetSettings.normalizeSource(json.optString(MonetSettings.SOURCE)) ==
                MonetSettings.PRESET
        ) {
            json.put(MonetSettings.SOURCE, MonetSettings.HOME)
        }
        json.remove(MonetSettings.SYSTEM_PALETTE)
        json.remove(MonetSettings.ACCENT_COLOR)
        json.remove("android.theme.customization.dynamic_color")
        json.put(MonetSettings.STYLE, android.content.theming.ThemeStyle.toString(palette.style))
        json.put(MonetSettings.SEED_INDEX, palette.seedIndex)
        json.put(MonetSettings.PALETTE_INDEX, palette.index)
        json.put(MonetSettings.FIDELITY, false)
    }

    fun secondary(enabled: Boolean) = edit { json ->
        if (enabled) {
            if (MonetSettings.parseColor(json.optString(MonetSettings.SECONDARY_SEED)) == null) {
                json.put(MonetSettings.SECONDARY_SEED, "FF5500")
            }
        } else json.remove(MonetSettings.SECONDARY_SEED)
    }

    fun seed(argb: Int, secondary: Boolean) = edit { json ->
        val hex = String.format(Locale.ROOT, "%06X", argb and 0xffffff)
        if (secondary) json.put(MonetSettings.SECONDARY_SEED, hex)
        else {
            json.put(MonetSettings.BASE_SEED, hex)
            json.put(MonetSettings.SYSTEM_PALETTE, hex)
            json.put(MonetSettings.ACCENT_COLOR, hex)
            json.put(MonetSettings.SOURCE, MonetSettings.PRESET)
            json.remove(MonetSettings.PALETTE_INDEX)
            json.remove(MonetSettings.SEED_INDEX)
            json.remove("android.theme.customization.dynamic_color")
        }
    }

    fun reset() = edit(R.string.reset_done, repository::reset)

    fun restore(look: SavedLook) = edit { repository.restoreLook(look, it) }

    fun save(name: String) = savedOperation(R.string.preset_saved) { repository.saveLook(name) }

    fun delete(id: String) = savedOperation { repository.deleteLook(id) }

    private fun savedOperation(message: Int? = null, action: () -> Unit) {
        viewModelScope.launch {
            mutex.withLock {
                try {
                    val snapshot =
                        withContext(Dispatchers.IO) {
                            action()
                            repository.load()
                        }
                    _state.update { it.copy(snapshot = snapshot, message = message) }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    _state.update { it.copy(message = R.string.saved_looks_failed) }
                }
            }
        }
    }

    fun loadImage(uri: Uri?) {
        imageJob?.cancel()
        imageJob =
            viewModelScope.launch {
                _image.value = PickerImage(loading = true)
                try {
                    val lock = _state.value.snapshot?.settings?.source == MonetSettings.LOCK
                    if (uri == null) {
                        val colors = withContext(Dispatchers.IO) {
                            runCatching { repository.wallpaperSeeds(lock) }.getOrDefault(emptyList())
                        }
                        _image.value = PickerImage(loading = true, colors = colors)
                    }
                    val bitmap =
                        withContext(Dispatchers.IO) {
                            repository.image(uri, lock)
                        }
                    _image.update { it.copy(bitmap = bitmap, loading = false) }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.w("LunarisMonet", "Could not load picker image", e)
                    _image.update { it.copy(loading = false, failed = true) }
                }
            }
    }

    fun clearImage() {
        imageJob?.cancel()
        _image.value = PickerImage()
    }

    fun clearMessage() {
        _state.update { it.copy(message = null) }
    }

    override fun onCleared() {
        getApplication<Application>().contentResolver.unregisterContentObserver(observer)
        wallpaper.removeOnColorsChangedListener(wallpaperListener)
        uiMode.removeContrastChangeListener(contrastListener)
        super.onCleared()
    }
}
