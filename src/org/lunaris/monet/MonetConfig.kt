/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.monet

import android.content.theming.ThemeStyle
import com.android.systemui.monet.MonetSettings
import java.util.Locale
import org.json.JSONObject

object MonetConfig {
    private const val DYNAMIC_OVERLAY = "android.theme.customization.dynamic_color"
    val keys =
        MonetSettings.CUSTOM_KEYS.toList() +
            listOf(
                MonetSettings.SOURCE,
                MonetSettings.STYLE,
                MonetSettings.SYSTEM_PALETTE,
                MonetSettings.ACCENT_COLOR,
                MonetSettings.COLOR_INDEX,
                MonetSettings.COLOR_BOTH,
                DYNAMIC_OVERLAY,
            )
    private val colors =
        setOf(
            MonetSettings.BASE_SEED,
            MonetSettings.SECONDARY_SEED,
            MonetSettings.TERTIARY_SEED,
            MonetSettings.SYSTEM_PALETTE,
            MonetSettings.ACCENT_COLOR,
        )
    private val booleans =
        setOf(
            MonetSettings.FIDELITY,
            MonetSettings.NEUTRAL_TEXT,
            MonetSettings.MODE_SATURATION,
        )
    private val ranges =
        buildMap<String, ClosedFloatingPointRange<Double>> {
            put(MonetSettings.VERSION, 1.0..3.0)
            put(MonetSettings.CHROMA, 0.0..100.0)
            MonetSettings.PALETTE_CHROMA.forEach { put(it, 0.0..100.0) }
            put(MonetSettings.HUE, -180.0..180.0)
            put(MonetSettings.TONE_LIGHT, -50.0..50.0)
            put(MonetSettings.TONE_DARK, -50.0..50.0)
            put(MonetSettings.CONTRAST, -1.0..1.0)
            put(MonetSettings.SEED_INDEX, -1.0..5.0)
            put(MonetSettings.PALETTE_INDEX, -1.0..5.0)
            (listOf(MonetSettings.ACCENT_SATURATION, MonetSettings.BACKGROUND_SATURATION) +
                    MonetSettings.LIGHT_SATURATION +
                    MonetSettings.DARK_SATURATION)
                .forEach {
                    put(it, 0.0..200.0)
                }
        }

    fun normalize(input: JSONObject): JSONObject {
        val json = JSONObject(input.toString())
        if (json.optInt(MonetSettings.VERSION, 1) < 2) {
            MonetSettings.PALETTE_CHROMA.forEach {
                if (json.optDouble(it, 0.0) == 0.0) json.remove(it)
            }
        }
        json.put(MonetSettings.VERSION, 3)
        val settings = MonetSettings.fromJson(json)
        if (settings.source != MonetSettings.PRESET) json.remove(MonetSettings.BASE_SEED)
        json.put(MonetSettings.SOURCE, settings.source)
        json.put(MonetSettings.STYLE, ThemeStyle.toString(settings.style))
        json.put(MonetSettings.COLOR_INDEX, json.optString(MonetSettings.COLOR_INDEX, "0"))
        if (settings.source == MonetSettings.PRESET) json.remove(MonetSettings.COLOR_BOTH)
        else json.put(MonetSettings.COLOR_BOTH, "0")
        colors.forEach { key ->
            val color = MonetSettings.parseColor(json.optString(key))
            if (color == null) json.remove(key)
            else json.put(key, String.format(Locale.ROOT, "%06X", color and 0xffffff))
        }
        booleans.forEach {
            if (json.optBoolean(it, false)) json.put(it, true) else json.remove(it)
        }
        ranges.forEach { (key, range) ->
            if (json.has(key)) {
                val value = json.optDouble(key, Double.NaN)
                if (!value.isFinite()) json.remove(key)
                else {
                    val bounded = value.coerceIn(range.start, range.endInclusive)
                    if (bounded % 1.0 == 0.0) json.put(key, bounded.toInt())
                    else json.put(key, bounded)
                }
            }
        }
        listOf(
                MonetSettings.CHROMA,
                MonetSettings.HUE,
                MonetSettings.TONE_LIGHT,
                MonetSettings.TONE_DARK,
            )
            .forEach { if (json.optDouble(it, 0.0) == 0.0) json.remove(it) }
        listOf(MonetSettings.ACCENT_SATURATION, MonetSettings.BACKGROUND_SATURATION).forEach {
            if (json.optDouble(it, 100.0) == 100.0) json.remove(it)
        }
        return json
    }

    fun owned(json: JSONObject): JSONObject =
        JSONObject().also { result ->
            keys.forEach { if (it != DYNAMIC_OVERLAY && json.has(it)) result.put(it, json.get(it)) }
        }

    fun merge(base: JSONObject, changes: Map<String, Any?>): JSONObject =
        JSONObject(base.toString()).also { json ->
            changes.forEach { (key, value) ->
                require(key in keys)
                if (value == null) json.remove(key) else json.put(key, value)
            }
        }

    fun difference(base: JSONObject, edited: JSONObject): Map<String, Any?> {
        val before = normalize(base)
        val after = normalize(edited)
        return buildMap {
            MonetConfig.keys.forEach { key ->
                val a = before.opt(key)
                val b = after.opt(key)
                val equal = if (a is Number && b is Number) a.toDouble() == b.toDouble() else a == b
                if (!equal) put(key, b)
            }
        }
    }

    fun imported(json: JSONObject): JSONObject {
        require(json.length() <= keys.size)
        json.keys().forEach { key ->
            require(key in keys && key != DYNAMIC_OVERLAY && !json.isNull(key))
            val value = json.get(key)
            when {
                key in colors -> require(value is String && MonetSettings.parseColor(value) != null)
                key in booleans -> require(value is Boolean)
                key in ranges -> {
                    require(
                        value is Number &&
                            value.toDouble().isFinite() &&
                            value.toDouble() in ranges.getValue(key)
                    )
                    if (
                        key in
                            listOf(
                                MonetSettings.VERSION,
                                MonetSettings.SEED_INDEX,
                                MonetSettings.PALETTE_INDEX,
                            )
                    ) {
                        require(value.toDouble() % 1.0 == 0.0)
                    }
                }
                key == MonetSettings.SOURCE ->
                    require(
                        value is String &&
                            value in
                                listOf(
                                    MonetSettings.HOME,
                                    MonetSettings.LOCK,
                                    MonetSettings.PRESET,
                                    "home",
                                    "lock",
                                )
                    )
                key == MonetSettings.STYLE ->
                    require(
                        value is String &&
                            ThemeStyle.toString(MonetSettings.parseStyle(value)) == value
                    )
                key == MonetSettings.COLOR_INDEX ->
                    require((value.toString().toIntOrNull() ?: -1) in 0..5)
                key == MonetSettings.COLOR_BOTH -> require(value.toString() in listOf("0", "1"))
                else -> require(value is String && value.length <= 200)
            }
        }
        return owned(normalize(json))
    }
}
