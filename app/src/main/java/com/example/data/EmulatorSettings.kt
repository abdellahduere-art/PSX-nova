package com.example.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class AspectRatioMode(val label: String) {
    RATIO_4_3("4:3 Clásico"),
    RATIO_16_9("16:9 Panorámico"),
    STRETCH("Pantalla completa")
}

enum class RegionMode(val label: String) {
    AUTO("Automático"),
    NTSC("NTSC (60 Hz)"),
    PAL("PAL (50 Hz)")
}

data class EmulatorSettings(
    val aspectRatio: AspectRatioMode = AspectRatioMode.RATIO_4_3,
    val scanlines: Boolean = true,
    val bilinearFilter: Boolean = true,
    val showFpsHud: Boolean = true,
    val frameSkip: Int = 0,
    val audioEnabled: Boolean = true,
    val hapticsEnabled: Boolean = true,
    val analogPadMode: Boolean = true,
    val controllerOpacity: Float = 0.78f,
    val fastForwardMultiplier: Int = 2,
    val useExternalBios: Boolean = false,
    val externalBiosName: String = "",
    val regionMode: RegionMode = RegionMode.AUTO
)

private val Context.dataStore by preferencesDataStore(name = "psx_nova_settings")

class SettingsStore(private val context: Context) {
    private object Keys {
        val ASPECT_RATIO = stringPreferencesKey("aspect_ratio")
        val SCANLINES = booleanPreferencesKey("scanlines")
        val BILINEAR = booleanPreferencesKey("bilinear")
        val SHOW_FPS = booleanPreferencesKey("show_fps")
        val FRAME_SKIP = intPreferencesKey("frame_skip")
        val AUDIO_ENABLED = booleanPreferencesKey("audio_enabled")
        val HAPTICS = booleanPreferencesKey("haptics")
        val ANALOG_MODE = booleanPreferencesKey("analog_mode")
        val OPACITY = floatPreferencesKey("controller_opacity")
        val FF_MULT = intPreferencesKey("ff_mult")
        val USE_EXT_BIOS = booleanPreferencesKey("use_ext_bios")
        val EXT_BIOS_NAME = stringPreferencesKey("ext_bios_name")
        val REGION_MODE = stringPreferencesKey("region_mode")
    }

    val settings: Flow<EmulatorSettings> = context.dataStore.data.map { prefs ->
        EmulatorSettings(
            aspectRatio = prefs[Keys.ASPECT_RATIO]
                ?.let { runCatching { AspectRatioMode.valueOf(it) }.getOrNull() }
                ?: AspectRatioMode.RATIO_4_3,
            scanlines = prefs[Keys.SCANLINES] ?: true,
            bilinearFilter = prefs[Keys.BILINEAR] ?: true,
            showFpsHud = prefs[Keys.SHOW_FPS] ?: true,
            frameSkip = (prefs[Keys.FRAME_SKIP] ?: 0).coerceIn(0, 3),
            audioEnabled = prefs[Keys.AUDIO_ENABLED] ?: true,
            hapticsEnabled = prefs[Keys.HAPTICS] ?: true,
            analogPadMode = prefs[Keys.ANALOG_MODE] ?: true,
            controllerOpacity = (prefs[Keys.OPACITY] ?: 0.78f).coerceIn(0.3f, 1.0f),
            fastForwardMultiplier = (prefs[Keys.FF_MULT] ?: 2).coerceIn(2, 4),
            useExternalBios = prefs[Keys.USE_EXT_BIOS] ?: false,
            externalBiosName = prefs[Keys.EXT_BIOS_NAME] ?: "",
            regionMode = prefs[Keys.REGION_MODE]
                ?.let { runCatching { RegionMode.valueOf(it) }.getOrNull() }
                ?: RegionMode.AUTO
        )
    }

    suspend fun update(transform: (EmulatorSettings) -> EmulatorSettings) {
        context.dataStore.edit { prefs ->
            val current = EmulatorSettings(
                aspectRatio = prefs[Keys.ASPECT_RATIO]?.let { runCatching { AspectRatioMode.valueOf(it) }.getOrNull() } ?: AspectRatioMode.RATIO_4_3,
                scanlines = prefs[Keys.SCANLINES] ?: true,
                bilinearFilter = prefs[Keys.BILINEAR] ?: true,
                showFpsHud = prefs[Keys.SHOW_FPS] ?: true,
                frameSkip = prefs[Keys.FRAME_SKIP] ?: 0,
                audioEnabled = prefs[Keys.AUDIO_ENABLED] ?: true,
                hapticsEnabled = prefs[Keys.HAPTICS] ?: true,
                analogPadMode = prefs[Keys.ANALOG_MODE] ?: true,
                controllerOpacity = prefs[Keys.OPACITY] ?: 0.78f,
                fastForwardMultiplier = prefs[Keys.FF_MULT] ?: 2,
                useExternalBios = prefs[Keys.USE_EXT_BIOS] ?: false,
                externalBiosName = prefs[Keys.EXT_BIOS_NAME] ?: "",
                regionMode = prefs[Keys.REGION_MODE]?.let { runCatching { RegionMode.valueOf(it) }.getOrNull() } ?: RegionMode.AUTO
            )
            val next = transform(current)
            prefs[Keys.ASPECT_RATIO] = next.aspectRatio.name
            prefs[Keys.SCANLINES] = next.scanlines
            prefs[Keys.BILINEAR] = next.bilinearFilter
            prefs[Keys.SHOW_FPS] = next.showFpsHud
            prefs[Keys.FRAME_SKIP] = next.frameSkip
            prefs[Keys.AUDIO_ENABLED] = next.audioEnabled
            prefs[Keys.HAPTICS] = next.hapticsEnabled
            prefs[Keys.ANALOG_MODE] = next.analogPadMode
            prefs[Keys.OPACITY] = next.controllerOpacity
            prefs[Keys.FF_MULT] = next.fastForwardMultiplier
            prefs[Keys.USE_EXT_BIOS] = next.useExternalBios
            prefs[Keys.EXT_BIOS_NAME] = next.externalBiosName
            prefs[Keys.REGION_MODE] = next.regionMode.name
        }
    }
}
