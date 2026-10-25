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
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    val hasChanges: Boolean = false,
    val pendingImport: MonetBackup? = null,
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
    private var sliderJob: Job? = null
    private var sliderChange: Pair<String, Any>? = null
    private var pending = emptyMap<String, Any?>()
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
                    val snapshot = withContext(Dispatchers.Default) { loadDraft() }
                    _state.update {
                        it.copy(
                            snapshot = snapshot,
                            loadFailed = false,
                            hasChanges = pending.isNotEmpty(),
                        )
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.e("LunarisMonet", "Could not load colors", e)
                    _state.update { it.copy(message = R.string.load_failed, loadFailed = true) }
                }
            }
        }
    }

    private fun loadDraft(): MonetSnapshot {
        val base = MonetConfig.normalize(repository.readJson())
        val json = MonetConfig.normalize(MonetConfig.merge(base, pending))
        val snapshot = repository.load(json)
        pending = MonetConfig.difference(base, json)
        return snapshot
    }

    private fun edit(message: Int? = null, change: (JSONObject) -> Unit) {
        sliderJob?.cancel()
        sliderChange = null
        viewModelScope.launch {
            mutex.withLock {
                _state.update { it.copy(busy = true) }
                try {
                    val snapshot =
                        withContext(Dispatchers.Default) {
                            val base = MonetConfig.normalize(repository.readJson())
                            val candidate = MonetConfig.merge(base, pending)
                            change(candidate)
                            val json = MonetConfig.normalize(candidate)
                            val result = repository.load(json)
                            pending = MonetConfig.difference(base, json)
                            result
                        }
                    _state.update {
                        it.copy(
                            snapshot = snapshot,
                            message = message,
                            loadFailed = false,
                            hasChanges = pending.isNotEmpty(),
                        )
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.e("LunarisMonet", "Could not preview colors", e)
                    _state.update { it.copy(message = R.string.preview_failed) }
                } finally {
                    _state.update { it.copy(busy = false) }
                }
            }
        }
    }

    fun apply() = finishPreview(apply = true)

    fun discard() = finishPreview(apply = false)

    private fun finishPreview(apply: Boolean) {
        sliderJob?.cancel()
        sliderChange = null
        viewModelScope.launch {
            mutex.withLock {
                _state.update { it.copy(busy = true) }
                try {
                    val snapshot =
                        withContext(Dispatchers.Default) {
                            if (apply) repository.apply(pending) else repository.load()
                        }
                    pending = emptyMap()
                    _state.update {
                        it.copy(
                            snapshot = snapshot,
                            hasChanges = false,
                            loadFailed = false,
                            message =
                                if (apply) R.string.changes_applied else R.string.changes_discarded,
                        )
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.e("LunarisMonet", "Could not finish preview", e)
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

    fun previewValue(key: String, value: Any) {
        sliderChange = key to value
        if (sliderJob?.isActive == true) return
        sliderJob = viewModelScope.launch {
            while (sliderChange != null) {
                val change = sliderChange ?: break
                sliderChange = null
                mutex.withLock {
                    try {
                        val result =
                            withContext(Dispatchers.Default) {
                                val base = MonetConfig.normalize(repository.readJson())
                                val json =
                                    MonetConfig.normalize(
                                        MonetConfig.merge(base, pending)
                                            .put(change.first, change.second)
                                    )
                                repository.load(json) to MonetConfig.difference(base, json)
                            }
                        pending = result.second
                        _state.update {
                            it.copy(
                                snapshot = result.first,
                                hasChanges = pending.isNotEmpty(),
                                loadFailed = false,
                            )
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Log.w("LunarisMonet", "Could not preview slider", e)
                    }
                }
                delay(60)
            }
        }
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

    fun tertiary(enabled: Boolean) = edit { json ->
        if (enabled)
            json.put(
                MonetSettings.TERTIARY_SEED,
                json.optString(MonetSettings.TERTIARY_SEED, "6750A4"),
            )
        else json.remove(MonetSettings.TERTIARY_SEED)
    }

    fun tertiarySeed(argb: Int) =
        setValue(
            MonetSettings.TERTIARY_SEED,
            String.format(Locale.ROOT, "%06X", argb and 0xffffff),
        )

    fun randomColor() =
        seed(
            android.graphics.Color.HSVToColor(
                floatArrayOf(
                    Random.nextInt(360).toFloat(),
                    0.45f + Random.nextFloat() * 0.45f,
                    0.7f + Random.nextFloat() * 0.3f,
                )
            ),
            secondary = false,
        )

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

    fun save(name: String) =
        savedOperation(R.string.preset_saved) {
            repository.saveLook(name, JSONObject(requireNotNull(_state.value.snapshot).json))
        }

    fun delete(id: String) = savedOperation { repository.deleteLook(id) }

    fun rename(id: String, name: String, description: String) = savedOperation {
        repository.renameLook(id, name, description)
    }

    fun replace(id: String) =
        savedOperation(R.string.look_updated) {
            repository.replaceLook(id, JSONObject(requireNotNull(_state.value.snapshot).json))
        }

    fun move(id: String, direction: Int) = savedOperation { repository.moveLook(id, direction) }

    fun export(uri: Uri, lookId: String?) =
        savedOperation(R.string.backup_exported, R.string.backup_failed) {
            repository.export(uri, JSONObject(requireNotNull(_state.value.snapshot).json), lookId)
        }

    fun readBackup(uri: Uri) =
        savedOperation(failure = R.string.backup_invalid) {
            val backup = repository.readBackup(uri)
            _state.update { it.copy(pendingImport = backup) }
        }

    fun dismissImport() {
        _state.update { it.copy(pendingImport = null) }
    }

    fun importBackup() =
        savedOperation(R.string.backup_imported, R.string.backup_import_failed) {
            val backup = requireNotNull(_state.value.pendingImport)
            val base = MonetConfig.normalize(repository.readJson())
            val json = JSONObject(base.toString())
            repository.restoreLook(SavedLook("", "", backup.current), json)
            val normalized = MonetConfig.normalize(json)
            repository.load(normalized)
            repository.importLooks(backup)
            pending = MonetConfig.difference(base, normalized)
            _state.update { it.copy(pendingImport = null) }
        }

    private fun savedOperation(
        message: Int? = null,
        failure: Int = R.string.saved_looks_failed,
        action: () -> Unit,
    ) {
        viewModelScope.launch {
            mutex.withLock {
                _state.update { it.copy(busy = true) }
                try {
                    val snapshot =
                        withContext(Dispatchers.IO) {
                            action()
                            loadDraft()
                        }
                    _state.update {
                        it.copy(
                            snapshot = snapshot,
                            message = message,
                            hasChanges = pending.isNotEmpty(),
                            loadFailed = false,
                        )
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.w("LunarisMonet", "Could not update saved looks", e)
                    _state.update { it.copy(message = failure) }
                } finally {
                    _state.update { it.copy(busy = false) }
                }
            }
        }
    }

    fun loadImage(uri: Uri?) {
        imageJob?.cancel()
        imageJob = viewModelScope.launch {
            _image.value = PickerImage(loading = true)
            try {
                val lock = _state.value.snapshot?.settings?.source == MonetSettings.LOCK
                if (uri == null) {
                    val colors =
                        withContext(Dispatchers.IO) {
                            runCatching { repository.wallpaperSeeds(lock) }
                                .getOrDefault(emptyList())
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

    fun filePickerUnavailable() {
        _state.update { it.copy(message = R.string.file_picker_unavailable) }
    }

    override fun onCleared() {
        getApplication<Application>().contentResolver.unregisterContentObserver(observer)
        wallpaper.removeOnColorsChangedListener(wallpaperListener)
        uiMode.removeContrastChangeListener(contrastListener)
        super.onCleared()
    }
}
